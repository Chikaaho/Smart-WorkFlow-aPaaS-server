package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.nodefunc.NodeFunctionContext;
import com.sw.ck.bpm.api.nodefunc.NodeFunctionResult;
import com.sw.ck.bpm.api.nodefunc.ParticipantFunction;
import com.sw.ck.bpm.api.nodefunc.ResultFunction;
import com.sw.ck.bpm.process.entity.BpmNodeFunction;
import com.sw.ck.bpm.process.mapper.BpmNodeFunctionMapper;
import com.sw.ck.common.exception.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受控节点函数注册表与运行器（I3 §4.9）。
 * <p>
 * 稳定 funcKey + 版本进入注册表；发布期校验存在/启用/类型/允许节点/版本冻结；
 * 运行期冻结实际版本并受超时与失败策略约束；生产路径无任意脚本执行
 * —— impl_bean 只能命中本模块内建注册的实现 Bean，不提供任何用户上传入口。
 * </p>
 */
@Service
public class NodeFunctionService {

    private static final Logger log = LoggerFactory.getLogger(NodeFunctionService.class);

    public static final String TYPE_RESOLVE = "RESOLVE_PARTICIPANTS";
    public static final String TYPE_HANDLE = "HANDLE_RESULT";

    private final BpmNodeFunctionMapper mapper;
    private final com.sw.ck.bpm.process.mapper.BpmNodeFunctionAuditMapper fnAuditMapper;
    private final ApplicationContext applicationContext;
    private final org.springframework.beans.factory.ObjectProvider<com.sw.ck.system.api.user.UserQueryFacade> userQueryFacade;
    /** 函数执行超时约束（§4.9）：独立 daemon 池，超时中断并计入失败审计。 */
    private final java.util.concurrent.ExecutorService fnExecutor =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "node-function-runner");
                t.setDaemon(true);
                return t;
            });

    public NodeFunctionService(BpmNodeFunctionMapper mapper,
                               ApplicationContext applicationContext,
                               org.springframework.beans.factory.ObjectProvider<com.sw.ck.system.api.user.UserQueryFacade> userQueryFacade) {
        this.mapper = mapper;
        this.applicationContext = applicationContext;
        this.userQueryFacade = userQueryFacade;
        this.fnAuditMapper = applicationContext.getBean(
                com.sw.ck.bpm.process.mapper.BpmNodeFunctionAuditMapper.class);
    }

    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        fnExecutor.shutdownNow();
    }

    private <T> T runWithTimeout(BpmNodeFunction row, java.util.concurrent.Callable<T> call) {
        long timeout = row.getTimeoutMs() == null || row.getTimeoutMs() <= 0
                ? 5000L : row.getTimeoutMs();
        java.util.concurrent.Future<T> future = fnExecutor.submit(call);
        try {
            return future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new BaseException(BpmErrorCode.NODE_FUNCTION_TIMEOUT);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BaseException be) {
                throw be;
            }
            throw (cause instanceof RuntimeException re) ? re : new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException(BpmErrorCode.NODE_FUNCTION_FAILED);
        }
    }

    /** 发布期校验：图内所有 functions 引用必须存在、启用且版本与 registry 一致。 */
    public List<GraphValidationError> validateForPublish(ProcessGraph graph, Long tenantId) {
        List<GraphValidationError> errors = new ArrayList<>();
        if (graph == null || graph.getElements() == null) {
            return errors;
        }
        for (GraphElement element : graph.getElements()) {
            if (!"node".equals(element.getKind()) || element.getConfig() == null) {
                continue;
            }
            Object functions = element.getConfig().get("functions");
            if (!(functions instanceof List<?> list) || list.isEmpty()) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> fn)) {
                    errors.add(error(element.getId(), BpmErrorCode.NODE_FUNCTION_NOT_FOUND));
                    continue;
                }
                String key = String.valueOf(fn.get("key"));
                Object versionValue = fn.get("version");
                int version;
                try {
                    version = Integer.parseInt(String.valueOf(versionValue));
                } catch (Exception e) {
                    errors.add(error(element.getId(), BpmErrorCode.NODE_FUNCTION_NOT_FOUND));
                    continue;
                }
                BpmNodeFunction registered = find(key, version, tenantId);
                if (registered == null || registered.getEnabled() == null
                        || registered.getEnabled() != 1) {
                    errors.add(error(element.getId(), BpmErrorCode.NODE_FUNCTION_NOT_FOUND));
                    continue;
                }
                if (!allowedForNode(registered, element.getType())) {
                    errors.add(error(element.getId(), BpmErrorCode.AUTO_ACTION_INVALID));
                }
            }
        }
        return errors;
    }

    /** 解析参与人函数输出；未配置返回 null（走默认参与人策略）。 */
    public List<String> resolveParticipants(Long tenantId, String processInstanceId,
                                            String nodeKey, String taskId,
                                            Map<String, Object> functionsConfig,
                                            Map<String, Object> variables) {
        BpmNodeFunction row = locate(functionsConfig, TYPE_RESOLVE, tenantId);
        if (row == null) {
            return null;
        }
        ParticipantFunction impl = bean(row.getImplBean(), ParticipantFunction.class);
        if (impl == null) {
            throw new BaseException(BpmErrorCode.NODE_FUNCTION_NOT_FOUND);
        }
        NodeFunctionContext context = NodeFunctionContext.builder()
                .tenantId(tenantId)
                .processInstanceId(processInstanceId)
                .nodeKey(nodeKey)
                .nodeIdempotentKey(taskId)
                .initiatorUserId(asLong(variables.get("submitter")))
                .actorUserId(asLong(variables.get("submitter")))
                .variables(variables == null ? Map.of() : variables)
                .build();
        long startMs = System.currentTimeMillis();
        try {
            List<String> participants = runWithTimeout(row,
                    () -> impl.resolveParticipants(context));
            if (participants == null || participants.isEmpty()) {
                throw new BaseException(BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
            }
            if (participants.size() > 1000) {
                throw new BaseException(BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
            }
            for (String id : participants) {
                if (id == null || id.isBlank() || !id.matches("\\d+")) {
                    throw new BaseException(BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
                }
            }
            com.sw.ck.system.api.user.UserQueryFacade users = userQueryFacade.getIfAvailable();
            if (users != null) {
                List<Long> requested = participants.stream().map(Long::valueOf).toList();
                List<Long> valid = users.findActiveUserIds(requested, tenantId);
                java.util.Set<Long> validSet = new java.util.HashSet<>(valid == null ? List.of() : valid);
                for (Long id : requested) {
                    if (!validSet.contains(id)) {
                        throw new BaseException(BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT,
                                "参与人不存在或不属于本租户: " + id);
                    }
                }
            }
            log.info("节点函数解析参与人成功: funcKey={}, version={}, nodeKey={}, size={}",
                    row.getFuncKey(), row.getFuncVersion(), nodeKey, participants.size());
            writeAudit(row, context, "SUCCEEDED", null,
                    "participants=" + participants.size(), startMs);
            return participants;
        } catch (BaseException e) {
            writeAudit(row, context, "FAILED", e.getCode(), e.getMessage(), startMs);
            throw e;
        } catch (RuntimeException e) {
            log.warn("节点函数解析失败: funcKey={}, error={}", row.getFuncKey(), e.getMessage());
            writeAudit(row, context, "FAILED", BpmErrorCode.NODE_FUNCTION_FAILED.getCode(),
                    String.valueOf(e.getMessage()), startMs);
            if ("FALLBACK".equals(row.getFailureStrategy())) {
                return null;
            }
            throw new BaseException(BpmErrorCode.NODE_FUNCTION_FAILED);
        }
    }

    /** 结果处理函数：输出审计摘要与白名单变量，不改变流程走向。 */
    public NodeFunctionResult handleResult(NodeFunctionContext context,
                                           Map<String, Object> functionsConfig,
                                           Map<String, Object> nodeResult) {
        BpmNodeFunction row = locate(functionsConfig, TYPE_HANDLE, context.getTenantId());
        if (row == null) {
            return null;
        }
        ResultFunction impl = bean(row.getImplBean(), ResultFunction.class);
        if (impl == null) {
            throw new BaseException(BpmErrorCode.NODE_FUNCTION_NOT_FOUND);
        }
        long startMs = System.currentTimeMillis();
        try {
            NodeFunctionResult result = runWithTimeout(row, () -> impl.handleResult(context, nodeResult));
            if (result != null && result.getResultVariables() != null
                    && result.getResultVariables().size() > 50) {
                throw new BaseException(BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
            }
            writeAudit(row, context, "SUCCEEDED", null,
                    result == null ? null : result.getSummary(), startMs);
            return result;
        } catch (RuntimeException e) {
            log.warn("结果函数失败: funcKey={}, error={}", row.getFuncKey(), e.getMessage());
            writeAudit(row, context, "FAILED", BpmErrorCode.NODE_FUNCTION_FAILED.getCode(),
                    String.valueOf(e.getMessage()), startMs);
            if ("FALLBACK".equals(row.getFailureStrategy())) {
                return NodeFunctionResult.builder()
                        .summary("function-fallback:" + row.getFuncKey())
                        .build();
            }
            throw new BaseException(BpmErrorCode.NODE_FUNCTION_FAILED);
        }
    }


    /** V75：节点函数调用审计（成功/失败各一行），失败不因审计失败而翻转业务结果。 */
    private void writeAudit(Object row, com.sw.ck.bpm.api.nodefunc.NodeFunctionContext context,
                            String outcome, Integer errorCode, String summary, long startMs) {
        try {
            com.sw.ck.bpm.process.entity.BpmNodeFunction fn = (com.sw.ck.bpm.process.entity.BpmNodeFunction) row;
            com.sw.ck.bpm.process.entity.BpmNodeFunctionAudit a = new com.sw.ck.bpm.process.entity.BpmNodeFunctionAudit();
            a.setFuncKey(fn.getFuncKey());
            a.setFuncVersion(fn.getFuncVersion());
            a.setFuncType(fn.getFuncType());
            a.setProcessInstanceId(context.getProcessInstanceId());
            a.setNodeKey(context.getNodeKey());
            a.setIdempotentKey(context.getNodeIdempotentKey());
            a.setActorId(context.getActorUserId());
            a.setOutcome(outcome);
            a.setErrorCode(errorCode);
            a.setSummary(summary == null ? null : summary.substring(0, Math.min(summary.length(), 2000)));
            a.setDurationMs(System.currentTimeMillis() - startMs);
            a.setTenantId(context.getTenantId() == null ? 0L : context.getTenantId());
            fnAuditMapper.insert(a);
        } catch (Exception e) {
            log.warn("节点函数审计写入失败（不影响业务结果）: {}", e.getMessage());
        }
    }

    private BpmNodeFunction locate(Map<String, Object> functionsConfig, String type, Long tenantId) {
        if (functionsConfig == null) {
            return null;
        }
        Object functions = functionsConfig.get("functions");
        if (!(functions instanceof List<?> list)) {
            return null;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> fn)) {
                continue;
            }
            String key = String.valueOf(fn.get("key"));
            int version;
            try {
                version = Integer.parseInt(String.valueOf(fn.get("version")));
            } catch (Exception e) {
                continue;
            }
            BpmNodeFunction registered = find(key, version, tenantId);
            if (registered != null && type.equals(registered.getFuncType())) {
                return registered;
            }
        }
        return null;
    }

    /** 注册表租户隔离：仅全局注册（tenant_id=0）与调用方本租户注册可命中，跨租户引用一律视为不存在。 */
    private BpmNodeFunction find(String key, int version, Long tenantId) {
        if (tenantId == null) {
            // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不按租户 0 解析
            throw new IllegalArgumentException("节点函数解析缺少租户上下文: key=" + key);
        }
        long ownerTenant = tenantId;
        return mapper.selectOne(Wrappers.<BpmNodeFunction>lambdaQuery()
                .eq(BpmNodeFunction::getFuncKey, key)
                .eq(BpmNodeFunction::getFuncVersion, version)
                .eq(BpmNodeFunction::getEnabled, 1)
                .and(w -> w.eq(BpmNodeFunction::getTenantId, 0L)
                        .or().eq(BpmNodeFunction::getTenantId, ownerTenant)));
    }

    private boolean allowedForNode(BpmNodeFunction row, String nodeType) {
        if (row.getAllowedNodes() == null || row.getAllowedNodes().isBlank()
                || "[]".equals(row.getAllowedNodes()) || "[\"\"]".equals(row.getAllowedNodes())) {
            return true;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode nodes = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(row.getAllowedNodes());
            if (nodes.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : nodes) {
                    if (item.asText("").equalsIgnoreCase(nodeType)) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private <T> T bean(String implBean, Class<T> type) {
        if (implBean == null || implBean.isBlank()) {
            return null;
        }
        try {
            return applicationContext.getBean(implBean, type);
        } catch (Exception e) {
            log.warn("节点函数实现不存在: bean={}", implBean);
            return null;
        }
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private GraphValidationError error(String elementId, BpmErrorCode code) {
        return GraphValidationError.builder()
                .elementId(elementId)
                .nodeKey(elementId)
                .errorCode(code.getCode())
                .message(code.getMessage())
                .build();
    }
}
