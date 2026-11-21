package com.sw.ck.agent.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.api.port.AgentGraphBusinessSources;
import com.sw.ck.agent.api.port.AgentGraphExecutionBrief;
import com.sw.ck.agent.api.port.AgentGraphInvocation;
import com.sw.ck.agent.api.port.AgentGraphInvocationResult;
import com.sw.ck.agent.api.port.AgentGraphInvokePort;
import com.sw.ck.agent.api.port.AgentGraphVersionBinding;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecutionRequest;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionRequestMapper;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.service.AgentGraphExecutionLifecycleService;
import com.sw.ck.agent.service.impl.AgentGraphSnapshotServiceImpl;
import com.sw.ck.agent.vo.AgentGraphExecutionAcceptedVO;
import com.sw.ck.common.exception.BaseException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Graph 执行器端口实现（1.2.0 P67）：流程服务节点 / 表单服务端动作的同步调用入口。
 *
 * <p>租户隔离全部依赖 MyBatis-Plus 租户拦截器按当前 {@code LoginUserHolder} 上下文
 * 强制（跨租户按不存在处理——发布期与运行期同一口径，E1-8）。同步等待为有界轮询
 * 受理行状态（250ms 间隔，上限 = 调用方超时与图自身执行超时的较小值），超时尽力
 * 取消受理。图进入人工等待（发布期已拒绝，此处为运行期兜底）按失败返回并取消。</p>
 */
@Component
public class AgentGraphInvokePortImpl implements AgentGraphInvokePort {

    /** 受理行终态/等待态（与 AgentGraphExecutionLifecycleServiceImpl 常量同值）。 */
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_INTERRUPTED = "INTERRUPTED";
    private static final String STATUS_WAITING_HUMAN = "WAITING_HUMAN";
    /** 子图递归防环/限深（与执行侧 MAX_SUBGRAPH_DEPTH 同值）。 */
    private static final int MAX_BINDING_DEPTH = 3;
    private static final long POLL_INTERVAL_MS = 250L;
    private static final int LINK_QUERY_LIMIT = 20;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private AgentGraphDefMapper graphDefMapper;
    @Autowired
    private AgentGraphExecutionRequestMapper requestMapper;
    @Autowired
    private AgentGraphSnapshotServiceImpl snapshotService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AgentGraphExecutionLifecycleService lifecycleService;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Optional<AgentGraphVersionBinding> describeBinding(Long graphDefId, Integer defVersion) {
        return describeBinding(graphDefId, defVersion, 0, new HashSet<>());
    }

    private Optional<AgentGraphVersionBinding> describeBinding(
            Long graphDefId, Integer defVersion, int depth, Set<Long> visited) {
        if (graphDefId == null || defVersion == null || depth > MAX_BINDING_DEPTH || !visited.add(graphDefId)) {
            return Optional.empty();
        }
        // 租户拦截器按当前上下文过滤：跨租户/不存在 → empty（发布与运行双校验共用，E1-8）
        AgentGraphDef def = graphDefMapper.selectById(graphDefId);
        if (def == null || !"PUBLISHED".equals(def.getStatus())
                || defVersion < 1 || defVersion > (def.getDefVersion() == null ? 0 : def.getDefVersion())) {
            return Optional.empty();
        }
        ProcessGraph graph = snapshotService.loadGraphByVersion(def, defVersion);
        if (graph == null || graph.getElements() == null) {
            return Optional.empty();
        }
        boolean containsHuman = false;
        List<AgentGraphVersionBinding.ParamDecl> startParams = new ArrayList<>();
        List<AgentGraphVersionBinding.OutputDecl> endParams = new ArrayList<>();
        for (var element : graph.getElements()) {
            if (!"node".equals(element.getKind()) || element.getConfig() == null) {
                continue;
            }
            String type = element.getType();
            if (AgentGraphInterpreter.NODE_TYPE_HUMAN.equals(type)) {
                containsHuman = true;
            } else if (AgentGraphInterpreter.NODE_TYPE_SUBGRAPH.equals(type)) {
                // 子图含人工节点同样拒绝（G3-5：子图人工暂停会向上传播）
                Object subId = element.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_SUBGRAPH_ID);
                Object subVersion = element.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_SUBGRAPH_VERSION);
                Long childId = subId == null ? null : Long.valueOf(String.valueOf(subId));
                Integer childVersion = subVersion == null ? null : Integer.valueOf(String.valueOf(subVersion));
                if (describeBinding(childId, childVersion, depth + 1, visited)
                        .map(AgentGraphVersionBinding::containsHumanNode).orElse(false)) {
                    containsHuman = true;
                }
            } else if (AgentGraphInterpreter.NODE_TYPE_START.equals(type)) {
                Object raw = element.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_INPUT_PARAMS);
                if (raw instanceof List<?> params) {
                    for (Object item : params) {
                        if (item instanceof Map<?, ?> param && param.get("name") != null) {
                            startParams.add(new AgentGraphVersionBinding.ParamDecl(
                                    String.valueOf(param.get("name")).trim(),
                                    param.get("type") == null ? "text" : String.valueOf(param.get("type")),
                                    Boolean.TRUE.equals(param.get("required")),
                                    param.get("description") == null ? "" : String.valueOf(param.get("description"))));
                        }
                    }
                }
            } else if (AgentGraphInterpreter.NODE_TYPE_END.equals(type)) {
                Object raw = element.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_OUTPUT_PARAMS);
                if (raw instanceof List<?> params) {
                    for (Object item : params) {
                        if (item instanceof Map<?, ?> param && param.get("name") != null) {
                            endParams.add(new AgentGraphVersionBinding.OutputDecl(
                                    String.valueOf(param.get("name")).trim(),
                                    param.get("description") == null ? "" : String.valueOf(param.get("description"))));
                        }
                    }
                }
            }
        }
        Integer timeout = def.getExecutionTimeoutSeconds() == null ? 300 : def.getExecutionTimeoutSeconds();
        return Optional.of(new AgentGraphVersionBinding(
                def.getId(), def.getGraphKey(), def.getName(), defVersion, timeout,
                containsHuman, List.copyOf(startParams), List.copyOf(endParams)));
    }

    @Override
    public AgentGraphInvocationResult invoke(AgentGraphInvocation invocation) {
        if (invocation == null || invocation.graphDefId() == null || invocation.defVersion() == null) {
            return AgentGraphInvocationResult.failure(null, "REJECTED", "调用缺少图定义或绑定版本");
        }
        // 等待上限：调用方超时与图自身执行超时取较小值（E1-4：不得超过图自身超时）
        AgentGraphDef def = graphDefMapper.selectById(invocation.graphDefId());
        if (def == null) {
            return AgentGraphInvocationResult.failure(null, "REJECTED", "图不存在或跨租户不可达");
        }
        int graphTimeout = def.getExecutionTimeoutSeconds() == null ? 300 : def.getExecutionTimeoutSeconds();
        int waitSeconds = invocation.timeoutSeconds() == null
                ? graphTimeout : Math.min(invocation.timeoutSeconds(), graphTimeout);

        AgentGraphExecutionAcceptedVO accepted;
        try {
            accepted = lifecycleService.submit(invocation.graphDefId(), invocation.defVersion(),
                    null, invocation.inputs(), invocation.idempotencyKey(),
                    new AgentGraphExecutionLifecycleService.BusinessLink(
                            invocation.businessSource() == null ? AgentGraphBusinessSources.DIRECT
                                    : invocation.businessSource(),
                            invocation.processInstanceId(), invocation.nodeId(), invocation.formRecordId()));
        } catch (BaseException rejected) {
            String category = String.valueOf(rejected.getMessage()).contains("容量")
                    ? "CAPACITY" : "REJECTED";
            return AgentGraphInvocationResult.failure(null, category, rejected.getMessage());
        }
        Long executionId = accepted == null ? null : accepted.getExecutionId();
        if (executionId == null) {
            return AgentGraphInvocationResult.failure(null, "REJECTED", "执行受理无返回");
        }

        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        while (true) {
            AgentGraphExecutionRequest row = pollRow(executionId);
            if (row == null) {
                return AgentGraphInvocationResult.failure(executionId, "REJECTED", "执行受理记录不存在");
            }
            String status = row.getStatus();
            if (STATUS_SUCCESS.equals(status)) {
                return successResult(executionId, row, invocation);
            }
            if (STATUS_FAILED.equals(status)) {
                String category = row.getErrorCategory() == null ? "GRAPH_FAILED" : row.getErrorCategory();
                return new AgentGraphInvocationResult(false, executionId, null, category,
                        row.getErrorMessage() == null ? "图执行失败" : row.getErrorMessage(),
                        row.getLatencyMs());
            }
            if (STATUS_CANCELLED.equals(status) || STATUS_INTERRUPTED.equals(status)) {
                return AgentGraphInvocationResult.failure(executionId, status,
                        STATUS_INTERRUPTED.equals(status) ? "服务重启中断执行" : "执行已取消");
            }
            if (STATUS_WAITING_HUMAN.equals(status)) {
                // 运行期兜底（发布期已拒绝人工图）：取消并按失败返回，不悬挂等待
                cancelQuietly(executionId);
                return AgentGraphInvocationResult.failure(executionId, "HUMAN_PAUSE",
                        "图运行进入人工等待，同步调用不支持（发布校验应已拒绝该图）");
            }
            if (System.currentTimeMillis() >= deadline) {
                cancelQuietly(executionId);
                return AgentGraphInvocationResult.failure(executionId, "TIMEOUT",
                        "等待图执行超时（超过 " + waitSeconds + " 秒），已请求取消");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cancelQuietly(executionId);
                return AgentGraphInvocationResult.failure(executionId, "TIMEOUT", "同步等待被中断，已请求取消");
            }
        }
    }

    /**
     * X2-expired 根因修复：轮询读用 JdbcTemplate 直查，绕开 MyBatis 一级缓存。
     * <p>
     * 调用方（GraphExecNodeDelegate）运行在 Flowable 引擎事务内——Spring 集成下同一事务
     * 共享 SqlSession，selectById 的首次结果（QUEUED/RUNNING）会被一级缓存固定，
     * 工作线程在独立事务提交的 SUCCESS/FAILED 永远不可见，轮询必然走到超时取消。
     * </p>
     */
    private AgentGraphExecutionRequest pollRow(Long executionId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, status, error_category, error_message, outputs_json, latency_ms"
                            + " FROM sw_agent_graph_execution_request WHERE id = ? AND deleted = 0",
                    executionId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> r = rows.get(0);
            AgentGraphExecutionRequest row = new AgentGraphExecutionRequest();
            row.setId(((Number) r.get("id")).longValue());
            row.setStatus(String.valueOf(r.get("status")));
            row.setErrorCategory(r.get("error_category") == null ? null : String.valueOf(r.get("error_category")));
            row.setErrorMessage(r.get("error_message") == null ? null : String.valueOf(r.get("error_message")));
            row.setOutputsJson(r.get("outputs_json") == null ? null : String.valueOf(r.get("outputs_json")));
            row.setLatencyMs(r.get("latency_ms") == null ? null : ((Number) r.get("latency_ms")).longValue());
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    /** 成功结果 + E1-4 守卫：声明的结束输出缺失/空白时不得以成功返回（节点成功但出参为空是缺陷）。 */
    private AgentGraphInvocationResult successResult(Long executionId, AgentGraphExecutionRequest row,
                                                     AgentGraphInvocation invocation) {
        Map<String, String> outputs = fromJson(row.getOutputsJson());
        List<String> missing = new ArrayList<>();
        describeBinding(invocation.graphDefId(), invocation.defVersion())
                .ifPresent(binding -> binding.endParams()
                        .forEach(decl -> {
                            String value = outputs.get(decl.name());
                            if (value == null || value.isBlank()) {
                                missing.add(decl.name());
                            }
                        }));
        if (!missing.isEmpty()) {
            return new AgentGraphInvocationResult(false, executionId, null, "OUTPUT_MISSING",
                    "图执行成功但缺少必需输出: " + String.join(", ", missing), row.getLatencyMs());
        }
        return new AgentGraphInvocationResult(true, executionId, outputs, null, null, row.getLatencyMs());
    }

    private void cancelQuietly(Long executionId) {
        try {
            lifecycleService.cancel(executionId);
        } catch (RuntimeException ignored) {
            // 终态竞态（已完成/已取消）无需处理；下一次轮询或调用方重试可见终态
        }
    }

    @Override
    public List<AgentGraphExecutionBrief> listByProcessInstance(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return List.of();
        }
        return requestMapper.selectList(
                        Wrappers.<AgentGraphExecutionRequest>lambdaQuery()
                                .eq(AgentGraphExecutionRequest::getProcessInstanceId, processInstanceId)
                                .orderByDesc(AgentGraphExecutionRequest::getId)
                                .last("LIMIT " + LINK_QUERY_LIMIT))
                .stream().map(this::toBrief).toList();
    }

    @Override
    public List<AgentGraphExecutionBrief> listByFormRecord(Long formRecordId) {
        if (formRecordId == null) {
            return List.of();
        }
        return requestMapper.selectList(
                        Wrappers.<AgentGraphExecutionRequest>lambdaQuery()
                                .eq(AgentGraphExecutionRequest::getFormRecordId, formRecordId)
                                .orderByDesc(AgentGraphExecutionRequest::getId)
                                .last("LIMIT " + LINK_QUERY_LIMIT))
                .stream().map(this::toBrief).toList();
    }

    private AgentGraphExecutionBrief toBrief(AgentGraphExecutionRequest row) {
        AgentGraphDef def = row.getGraphDefId() == null ? null : graphDefMapper.selectById(row.getGraphDefId());
        return new AgentGraphExecutionBrief(
                row.getId(),
                def == null ? null : def.getGraphKey(),
                def == null ? null : def.getName(),
                row.getGraphDefVersion(),
                row.getStatus(),
                row.getBusinessSource() == null ? AgentGraphBusinessSources.DIRECT : row.getBusinessSource(),
                row.getLatencyMs(),
                row.getErrorCategory(),
                row.getErrorMessage(),
                row.getCreateTime() == null ? null : TIME_FORMAT.format(row.getCreateTime()));
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
