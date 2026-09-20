package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDetailDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecution;
import com.sw.ck.agent.entity.AgentGraphExecutionNode;
import com.sw.ck.agent.entity.AgentModelConfig;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper;
import com.sw.ck.agent.mapper.AgentModelConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 图执行 Service 实现（M07-F02 Step8 图解释执行引擎第一版 + Step12 执行历史
 * 持久化）。
 * <p>
 * 流程（方案 §5）：加载图定义（requireEntity，NOT_FOUND 语义同 Step7）→ 校验
 * PUBLISHED → 反序列化 graph_json → 执行前校验（方案 §2-D 五项，任一失败即
 * PARAM_ERROR + 具体原因，不做部分执行）→ 调 {@link AgentGraphInterpreter} 解释执行。
 * </p>
 * <p>
 * <b>错误语义</b>：校验失败 → {@link BaseException}（全局惯例 HTTP 200 + body.code）；
 * 运行时错误（条件无匹配且无默认边 / 步数超限 / 模型或工具调用异常）→
 * {@code success=false} + errorMessage 返回（不上抛，与 F01 run() 语义一致）；
 * 不存在的 graphDefId / 跨租户 → NOT_FOUND。校验失败发生在执行阶段之前，不产生
 * 执行历史记录（对齐 F04"配置非法不落脏数据"先例）；只有进入执行阶段（解释器实际
 * 运行）的调用才落库。
 * </p>
 * <p>
 * <b>执行历史持久化（Step12）</b>：执行前建 {@code RUNNING} 执行记录 → 解释器运行
 * （成功/失败）→ 终态回写（{@code SUCCESS}/{@code FAILED} + 错误分类 + 耗时）+ 节点
 * 级轨迹明细批量落库——成功与失败两类路径都完整记录（区别于 F04 只写成功分支）。
 * 节点轨迹由解释器采集（{@code getTraces()}，纯 Java 无 Mapper 依赖），本类负责
 * 序列化（变量快照 JSON）与落库。
 * </p>
 * <p>
 * <b>LLM 节点模型配置</b>：执行前校验一次性加载图内全部 LLM 节点引用的
 * {@link AgentModelConfig}（租户拦截器自动隔离）并传给解释器——单一查询、无
 * 校验与执行之间的 TOCTOU 窗口，解释器保持纯 Java 无 DB 访问。
 * </p>
 */
@Service
public class AgentGraphExecutionServiceImpl
        extends BaseServiceImpl<AgentGraphDefMapper, AgentGraphDef>
        implements AgentGraphExecutionService {

    /** 状态常量（varchar + String，不建 enum 类，D52 决策） */
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    /** 执行记录状态：执行前建行（Step12） */
    private static final String STATUS_RUNNING = "RUNNING";

    /** 执行记录状态：执行成功 */
    private static final String STATUS_SUCCESS = "SUCCESS";

    /** 执行记录状态：执行失败（运行时错误，含第三方异常） */
    private static final String STATUS_FAILED = "FAILED";

    private final ObjectMapper objectMapper;
    private final AgentModelConfigMapper modelConfigMapper;
    private final AgentToolInternalConfigMapper internalToolMapper;
    private final AgentToolExternalConfigMapper externalToolMapper;
    private final AgentGraphExecutionMapper executionMapper;
    private final AgentGraphExecutionNodeMapper executionNodeMapper;
    private final ChatModelFactory chatModelFactory;
    private final AesGcmCipher cipher;
    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    /**
     * 工具回调工厂（可选注入，与 F01 同款模式）：{@code sw.agent.enabled} 未开启时
     * 为 null，TOOL 节点执行时由解释器抛运行时错误转 success=false。
     */
    @Autowired(required = false)
    private AgentToolCallbackFactory agentToolCallbackFactory;

    public AgentGraphExecutionServiceImpl(ObjectMapper objectMapper,
                                          AgentModelConfigMapper modelConfigMapper,
                                          AgentToolInternalConfigMapper internalToolMapper,
                                          AgentToolExternalConfigMapper externalToolMapper,
                                          AgentGraphExecutionMapper executionMapper,
                                          AgentGraphExecutionNodeMapper executionNodeMapper,
                                          ChatModelFactory chatModelFactory,
                                          AesGcmCipher cipher,
                                          LoginContextProvider loginContextProvider,
                                          DeptScopeProvider deptScopeProvider) {
        this.objectMapper = objectMapper;
        this.modelConfigMapper = modelConfigMapper;
        this.internalToolMapper = internalToolMapper;
        this.externalToolMapper = externalToolMapper;
        this.executionMapper = executionMapper;
        this.executionNodeMapper = executionNodeMapper;
        this.chatModelFactory = chatModelFactory;
        this.cipher = cipher;
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphExecuteRespDTO execute(Long graphDefId, String input) {
        // 参数校验（对齐 F01 run() 校验惯例：Service 层手动校验）
        if (input == null || input.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "input 不能为空");
        }
        // NOT_FOUND（selectById 经租户拦截器自动过滤 tenant_id，同 Step7 requireEntity）
        AgentGraphDef entity = requireEntity(graphDefId);
        // 执行只认发布版本（草稿不可执行，对齐"发布版本是执行引用的稳定锚点"）
        if (!STATUS_PUBLISHED.equals(entity.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图未发布，无法执行");
        }
        ProcessGraph graph = parseGraph(entity.getGraphJson());
        if (graph == null || graph.getElements() == null || graph.getElements().isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图数据为空，无法执行");
        }
        // 执行前校验（§2-D）：任一失败即 PARAM_ERROR，不做部分执行；返回校验通过的
        // 模型配置映射（LLM 节点执行数据，解释器直接消费）。校验失败发生在执行阶段
        // 之前 → 不产生执行历史记录（对齐 F04"配置非法不落脏数据"先例）
        Map<Long, AgentModelConfig> modelConfigs = validateForExecution(graph);

        // —— Step12 执行历史：进入执行阶段即建 RUNNING 记录（成功/失败两路径均落库） ——
        AgentGraphExecution exec = new AgentGraphExecution();
        exec.setGraphDefId(entity.getId());
        exec.setGraphDefVersion(entity.getDefVersion());
        exec.setStatus(STATUS_RUNNING);
        exec.setInput(input);
        executionMapper.insert(exec);

        // Step11 死循环防护预算（方案 §2.3）：maxSteps = 2 × 节点数 +
        // Σ(maxIterations of 所有 LOOP 节点) × 节点数。LOOP config 缺省 maxIterations
        // 用 DEFAULT_MAX_ITERATIONS 参与预算；无 LOOP 时退化为现状 2 × 节点数（回归
        // 安全）。给显式循环留足预算（近似最坏情况：每个循环跑满配置次数 × 全图节点
        // 数），避免"循环刚跑 1-2 次被误判死循环"；意外死循环 / JOIN 挂起死锁仍由
        // 全局兜底统一拦截（执行步数超限）。
        List<GraphElement> elements = graph.getElements();
        int nodeCount = (int) elements.stream()
                .filter(e -> "node".equals(e.getKind()))
                .count();
        int loopBudget = 0;
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind())
                    && AgentGraphInterpreter.NODE_TYPE_LOOP.equals(element.getType())) {
                loopBudget += maxIterationsOf(element);
            }
        }
        int maxSteps = nodeCount * 2 + loopBudget * nodeCount;
        AgentGraphInterpreter interpreter = new AgentGraphInterpreter(chatModelFactory,
                agentToolCallbackFactory, modelConfigs, cipher,
                loginContextProvider.getTenantId(), maxSteps);

        long start = System.currentTimeMillis();
        AgentGraphExecuteRespDTO resp = new AgentGraphExecuteRespDTO();
        List<AgentGraphInterpreter.NodeExecutionTrace> traces;
        Throwable failure = null;
        try {
            String output = interpreter.run(graph, input);
            resp.setSuccess(true);
            resp.setOutput(output);
            traces = interpreter.getTraces();
        } catch (Exception e) {
            // 运行时错误（GraphExecutionException / 模型或工具调用异常）：不上抛，
            // success=false + 异常摘要（与 F01 run() success=false 语义一致）；
            // 节点轨迹在解释器 catch 中已完整留痕，此处读取
            failure = e;
            resp.setSuccess(false);
            resp.setErrorMessage(summarizeError(e));
            traces = interpreter.getTraces();
        }
        long latency = System.currentTimeMillis() - start;
        resp.setLatencyMs(latency);
        resp.setExecutionId(exec.getId());

        // —— 终态回写（成功/失败统一路径；DB 异常位于运行时 catch 之外，上抛不吞） ——
        exec.setStatus(resp.isSuccess() ? STATUS_SUCCESS : STATUS_FAILED);
        exec.setResultText(resp.getOutput());
        exec.setErrorCategory(failure == null ? null : classifyError(failure));
        exec.setErrorMessage(resp.getErrorMessage());
        exec.setLatencyMs(latency);
        executionMapper.updateById(exec);

        // —— 节点级轨迹明细批量落库 ——
        persistNodeTraces(exec.getId(), traces);
        return resp;
    }

    // ==================== 执行历史查询（Step12，只读端点） ====================

    @Override
    public PageResult<AgentGraphExecutionDTO> pageExecutions(PageParam pageParam, Long graphDefId) {
        // 租户隔离经租户拦截器自动生效；数据范围：sw_agent_graph_execution 无 dept_id 列，
        // 等效条件在 selectExecutionPage 内实现（create_by VARCHAR 兼容比较）
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);
        IPage<AgentGraphExecution> page = executionMapper.selectExecutionPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()), graphDefId, scope);
        return PageResult.of(page.convert(this::toSummaryDTO));
    }

    @Override
    public AgentGraphExecutionDetailDTO getExecution(Long executionId) {
        return toDetailDTO(requireExecution(executionId));
    }

    @Override
    public List<AgentGraphExecutionNodeDTO> listExecutionNodes(Long executionId) {
        // 执行记录存在性校验（selectById 经租户拦截器自动过滤：跨租户/不存在 → 404 语义）
        requireExecution(executionId);
        return executionNodeMapper.selectList(
                        Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                                .eq(AgentGraphExecutionNode::getExecutionId, executionId)
                                .orderByAsc(AgentGraphExecutionNode::getNodeSeq))
                .stream().map(this::toNodeDTO).toList();
    }

    /** 执行记录加载（不存在/跨租户 → NOT_FOUND，同会话查询先例） */
    private AgentGraphExecution requireExecution(Long executionId) {
        if (executionId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "executionId 不能为空");
        }
        AgentGraphExecution exec = executionMapper.selectById(executionId);
        if (exec == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "执行记录不存在");
        }
        return exec;
    }

    // ==================== 执行历史持久化辅助（Step12） ====================

    /**
     * 节点轨迹批量落库：解释器采集的轨迹（纯 Java 数据载体，方向文档 §5.4）→ 实体
     * 逐条 insert（与 F04 persistToolCallLogs 同款逐条模式；变量快照序列化为 JSON）。
     * 空轨迹（理论不可达：进入执行阶段必有节点出队）为空操作。
     */
    private void persistNodeTraces(Long executionId,
                                   List<AgentGraphInterpreter.NodeExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        // M07-F04-02: 汇总 token 数据（每侧独立：无数据侧保持 null，不得写成 0——
        // 部分 usage 场景"输出未知"必须与"输出 0"区分，贯穿 DB→DTO）
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        boolean hasInputData = false;
        boolean hasOutputData = false;

        for (AgentGraphInterpreter.NodeExecutionTrace trace : traces) {
            AgentGraphExecutionNode node = new AgentGraphExecutionNode();
            node.setExecutionId(executionId);
            node.setNodeSeq((int) trace.getNodeSeq());
            node.setBranchId(trace.getBranchId());
            node.setNodeId(trace.getNodeId());
            node.setNodeType(trace.getNodeType());
            node.setNodeLatencyMs(trace.getNodeLatencyMs());
            node.setVariableSnapshot(toJson(trace.getVariableSnapshot()));
            // M07-F04-02: 设置 token 字段
            node.setInputTokens(trace.getInputTokens());
            node.setOutputTokens(trace.getOutputTokens());
            // 汇总 token（只累加有数据的节点；每侧独立标记）
            if (trace.getInputTokens() != null) {
                totalInputTokens += trace.getInputTokens();
                hasInputData = true;
            }
            if (trace.getOutputTokens() != null) {
                totalOutputTokens += trace.getOutputTokens();
                hasOutputData = true;
            }
            executionNodeMapper.insert(node);
        }

        // M07-F04-02: 更新执行记录的 token 汇总（每侧独立：有数据才写，无数据保持 null）
        if (hasInputData || hasOutputData) {
            AgentGraphExecution execUpdate = new AgentGraphExecution();
            execUpdate.setId(executionId);
            if (hasInputData) {
                execUpdate.setInputTokens(totalInputTokens);
            }
            if (hasOutputData) {
                execUpdate.setOutputTokens(totalOutputTokens);
            }
            executionMapper.updateById(execUpdate);
        }
    }

    /**
     * 错误分类（Step12）：沿 cause 链找携带分类的 GraphExecutionException——解释器
     * 全部运行时抛出点（含第三方异常包装）均已带分类，未找到为理论不可达兜底（UNKNOWN）。
     */
    private String classifyError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof AgentGraphInterpreter.GraphExecutionException gex
                    && gex.getCategory() != null) {
                return gex.getCategory();
            }
            cur = cur.getCause();
        }
        return AgentGraphInterpreter.ERROR_CATEGORY_UNKNOWN;
    }

    /** 变量表快照序列化（Map<String,String> → JSON；失败理论不可达，兜底 null） */
    private String toJson(Map<String, String> variables) {
        if (variables == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== DTO 转换（Step12） ====================

    private AgentGraphExecutionDTO toSummaryDTO(AgentGraphExecution exec) {
        AgentGraphExecutionDTO dto = new AgentGraphExecutionDTO();
        dto.setId(exec.getId());
        dto.setGraphDefId(exec.getGraphDefId());
        dto.setGraphDefVersion(exec.getGraphDefVersion());
        dto.setStatus(exec.getStatus());
        dto.setErrorCategory(exec.getErrorCategory());
        dto.setErrorMessage(exec.getErrorMessage());
        dto.setLatencyMs(exec.getLatencyMs());
        // M07-F04-02: 添加 token 字段
        dto.setInputTokens(exec.getInputTokens());
        dto.setOutputTokens(exec.getOutputTokens());
        dto.setCreateTime(exec.getCreateTime());
        return dto;
    }

    private AgentGraphExecutionDetailDTO toDetailDTO(AgentGraphExecution exec) {
        AgentGraphExecutionDetailDTO dto = new AgentGraphExecutionDetailDTO();
        dto.setId(exec.getId());
        dto.setGraphDefId(exec.getGraphDefId());
        dto.setGraphDefVersion(exec.getGraphDefVersion());
        dto.setStatus(exec.getStatus());
        dto.setInput(exec.getInput());
        dto.setOutput(exec.getResultText());
        dto.setErrorCategory(exec.getErrorCategory());
        dto.setErrorMessage(exec.getErrorMessage());
        dto.setLatencyMs(exec.getLatencyMs());
        // M07-F04-02: 添加 token 字段
        dto.setInputTokens(exec.getInputTokens());
        dto.setOutputTokens(exec.getOutputTokens());
        dto.setCreateTime(exec.getCreateTime());
        dto.setUpdateTime(exec.getUpdateTime());
        return dto;
    }

    private AgentGraphExecutionNodeDTO toNodeDTO(AgentGraphExecutionNode node) {
        AgentGraphExecutionNodeDTO dto = new AgentGraphExecutionNodeDTO();
        dto.setNodeSeq(node.getNodeSeq());
        dto.setBranchId(node.getBranchId());
        dto.setNodeId(node.getNodeId());
        dto.setNodeType(node.getNodeType());
        dto.setNodeLatencyMs(node.getNodeLatencyMs());
        dto.setVariableSnapshot(node.getVariableSnapshot());
        // M07-F04-02: 添加 token 字段
        dto.setInputTokens(node.getInputTokens());
        dto.setOutputTokens(node.getOutputTokens());
        return dto;
    }

    // ==================== 执行前校验（方案 §2-D） ====================

    /**
     * 执行前最小校验（非完整拓扑校验器，方案 §3 已裁定）：
     * ①PUBLISHED（调用方已校验）②唯一 START + 至少一个 END 可达 ③LLM 节点
     * agentModelConfigId 可解析到租户内 AgentModelConfig ④TOOL 节点 toolName 精确
     * 匹配 enabled=1 白名单 ⑤CONDITION 出边默认边唯一 ⑥FORK 出边数 ≥ 2 ⑦JOIN 入边数
     * ≥ 2 ⑧LOOP maxIterations（存在时）≥ 1。
     * <p>
     * 不做"循环体可达退出路径"静态分析（方案 §2.4 判断非必要：运行时已有 maxIterations
     * + 全局步数兜底双层防护，且循环体路径依赖动态变量匹配易误报）。既有 BFS visited
     * 去重天然容忍环。
     * </p>
     *
     * @return 图内全部 LLM 节点引用的模型配置（id → 配置）
     */
    private Map<Long, AgentModelConfig> validateForExecution(ProcessGraph graph) {
        List<GraphElement> elements = graph.getElements();
        List<GraphElement> nodes = elements.stream()
                .filter(e -> "node".equals(e.getKind()))
                .toList();

        // —— ② START 唯一 ——
        List<GraphElement> starts = nodes.stream()
                .filter(n -> AgentGraphInterpreter.NODE_TYPE_START.equals(n.getType()))
                .toList();
        if (starts.size() != 1) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "图中 START 节点必须唯一（当前 " + starts.size() + " 个）");
        }
        // —— ② 至少一个 END 可达（从唯一 START 沿边 BFS） ——
        if (!hasReachableEnd(starts.get(0), elements, nodes)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图中不存在可达的 END 节点");
        }

        // —— ③④⑤ 按节点类型逐项校验 ——
        Map<Long, AgentModelConfig> modelConfigs = new HashMap<>();
        for (GraphElement node : nodes) {
            switch (node.getType()) {
                case AgentGraphInterpreter.NODE_TYPE_LLM -> {
                    Object idObj = configValue(node, AgentGraphInterpreter.CONFIG_KEY_AGENT_MODEL_CONFIG_ID);
                    if (!(idObj instanceof Number n)) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LLM 节点缺少模型配置引用: " + node.getId());
                    }
                    Long modelConfigId = n.longValue();
                    AgentModelConfig mc = modelConfigMapper.selectById(modelConfigId);
                    if (mc == null) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LLM 节点引用的模型配置不存在: " + modelConfigId);
                    }
                    modelConfigs.put(modelConfigId, mc);
                }
                case AgentGraphInterpreter.NODE_TYPE_TOOL -> {
                    Object nameObj = configValue(node, AgentGraphInterpreter.CONFIG_KEY_TOOL_NAME);
                    if (!(nameObj instanceof String toolName) || toolName.isBlank()) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "TOOL 节点缺少工具名: " + node.getId());
                    }
                    if (!toolExists(toolName)) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "工具节点引用的工具不存在或未启用: " + toolName);
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_CONDITION -> {
                    // ⑤ 默认边唯一（≥2 条无 keyword 边 → 图非法）；0 条默认边允许通过
                    // 校验，运行时未命中关键词时由解释器抛 GraphExecutionException
                    long defaultEdgeCount = outgoingEdges(node, elements).stream()
                            .filter(e -> AgentGraphInterpreter.keywordOf(e) == null)
                            .count();
                    if (defaultEdgeCount > 1) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "条件分支默认边不唯一: " + node.getId());
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_FORK -> {
                    // ⑥ 扇出分支数 ≥ 2（每出边一个分支；少于 2 无并行语义）
                    if (outgoingEdges(node, elements).size() < 2) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "FORK 节点扇出分支数必须 ≥ 2: " + node.getId());
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_JOIN -> {
                    // ⑦ 汇合入边数 ≥ 2（无汇合语义的单入边 JOIN 无意义）
                    if (incomingEdges(node, elements).size() < 2) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "JOIN 节点汇合入边数必须 ≥ 2: " + node.getId());
                    }
                }
                case AgentGraphInterpreter.NODE_TYPE_LOOP -> {
                    // ⑧ maxIterations 存在且 <1 → 非法（缺失用默认 10，不报错）
                    Object maxIterations = configValue(node,
                            AgentGraphInterpreter.CONFIG_KEY_MAX_ITERATIONS);
                    if (maxIterations instanceof Number n && n.intValue() < 1) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LOOP 节点 maxIterations 必须 ≥ 1: " + node.getId());
                    }
                }
                default -> { /* START/END 及其他：无执行前校验 */ }
            }
        }
        return modelConfigs;
    }

    /** 节点 config 读取（config 为不透明 Map，本校验只按执行契约读已定义键，null 安全） */
    private Object configValue(GraphElement node, String key) {
        return node.getConfig() == null ? null : node.getConfig().get(key);
    }

    /** 当前节点的出边列表（kind=edge 且 source == 节点 id），按 elements 出现顺序 */
    private List<GraphElement> outgoingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getSource())) {
                edges.add(element);
            }
        }
        return edges;
    }

    /** 当前节点的入边列表（kind=edge 且 target == 节点 id），按 elements 出现顺序 */
    private List<GraphElement> incomingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getTarget())) {
                edges.add(element);
            }
        }
        return edges;
    }

    /**
     * LOOP 节点迭代上限（预算公式用）：config.maxIterations（Number → int；缺失/非数值 =
     * 默认 {@link AgentGraphInterpreter#DEFAULT_MAX_ITERATIONS}；<1 已被执行前校验拦截）。
     */
    private int maxIterationsOf(GraphElement node) {
        Object value = configValue(node, AgentGraphInterpreter.CONFIG_KEY_MAX_ITERATIONS);
        if (!(value instanceof Number n)) {
            return AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;
        }
        return n.intValue();
    }

    /** 从唯一 START 沿边 BFS，判断是否存在可达的 END 节点 */
    private boolean hasReachableEnd(GraphElement start, List<GraphElement> elements, List<GraphElement> nodes) {
        Map<String, String> typeById = new HashMap<>();
        for (GraphElement node : nodes) {
            typeById.put(node.getId(), node.getType());
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GraphElement edge : elements) {
            if ("edge".equals(edge.getKind()) && edge.getSource() != null && edge.getTarget() != null) {
                adjacency.computeIfAbsent(edge.getSource(), k -> new ArrayList<>()).add(edge.getTarget());
            }
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start.getId());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            if (AgentGraphInterpreter.NODE_TYPE_END.equals(typeById.get(id))) {
                return true;
            }
            for (String next : adjacency.getOrDefault(id, List.of())) {
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * 工具白名单精确匹配：internal/external 两表任一存在 name 精确相等且 enabled=1 的记录。
     * enabled 用数字字面量 1（非 Boolean 参数）：H2/PG 下 SMALLINT 列比对惯例（Step4 现场实证）。
     */
    private boolean toolExists(String toolName) {
        Long internal = internalToolMapper.selectCount(
                Wrappers.<AgentToolInternalConfig>lambdaQuery()
                        .eq(AgentToolInternalConfig::getName, toolName)
                        .eq(AgentToolInternalConfig::getEnabled, 1));
        if (internal != null && internal > 0) {
            return true;
        }
        Long external = externalToolMapper.selectCount(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getName, toolName)
                        .eq(AgentToolExternalConfig::getEnabled, 1));
        return external != null && external > 0;
    }

    // ==================== 内部辅助 ====================

    /** 按 id + 租户加载（selectById 经租户拦截器自动过滤），不存在抛 NOT_FOUND（同 Step7） */
    private AgentGraphDef requireEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentGraphDef entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private ProcessGraph parseGraph(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(graphJson, ProcessGraph.class);
        } catch (Exception e) {
            // 注：ServiceImpl 基类的 log 为 MyBatis Log 接口（org.apache.ibatis.logging.Log），
            // 不支持 {} 占位符，拼接消息（Step7 回执偏差 C 同款）
            log.warn("Failed to parse graph_json: " + e.getMessage());
            return null;
        }
    }

    /**
     * 异常摘要（对齐 F01 summarizeError）：沿 cause 链取最深层非空 message。
     * 只取 message 不含堆栈，杜绝明文 API Key 通过异常信息泄漏。
     */
    private String summarizeError(Throwable t) {
        // P61：按异常类型分层——平台业务异常与编排自身状态失败保留可定位文案，
        // 传输/服务商类异常替换为安全结论并写日志（见 AgentFailureSummarizer）。
        return com.sw.ck.agent.orchestration.AgentFailureSummarizer.summarize(t);
    }
}
