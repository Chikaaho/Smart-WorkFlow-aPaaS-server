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
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphInterpreter;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.service.AgentGraphExecutionService;
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
 * 模型经运行时服务校验（启停含供应商）并预热单例——单一查询、无
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

    /** 人工节点等待中的执行记录状态（G3） */
    private static final String STATUS_WAITING_HUMAN = "WAITING_HUMAN";

    /** 人工等待被取消/子图取消时的执行记录状态 */
    private static final String STATUS_CANCELLED = "CANCELLED";

    /** 子图嵌套深度上限（G3-5） */
    private static final int MAX_SUBGRAPH_DEPTH = 3;

    /** 图级执行超时默认值（秒，与 V0.2.3 列默认一致） */
    private static final int DEFAULT_EXECUTION_TIMEOUT_SECONDS = 300;

    /** 执行线程池（虚拟线程：超时取消后不占用平台线程；执行记录仍由主线程收尾） */
    private final java.util.concurrent.ExecutorService timeoutExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final ObjectMapper objectMapper;
    private final AgentModelRuntimeService modelRuntimeService;
    private final AgentGraphSnapshotServiceImpl snapshotService;
    private final AgentToolInternalConfigMapper internalToolMapper;
    private final AgentToolExternalConfigMapper externalToolMapper;
    private final AgentGraphExecutionMapper executionMapper;
    private final AgentGraphExecutionNodeMapper executionNodeMapper;
    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    /**
     * 工具回调工厂（可选注入，与 F01 同款模式）：{@code sw.agent.enabled} 未开启时
     * 为 null，TOOL 节点执行时由解释器抛运行时错误转 success=false。
     */
    @Autowired(required = false)
    private AgentToolCallbackFactory agentToolCallbackFactory;

    /** 知识检索服务（阶段 3；knowledge 模块 bean，缺失时 KNOWLEDGE 节点运行时报错） */
    @Autowired(required = false)
    private com.sw.ck.knowledge.service.KnowledgeRetrievalService knowledgeRetrievalService;

    public AgentGraphExecutionServiceImpl(ObjectMapper objectMapper,
                                          AgentModelRuntimeService modelRuntimeService,
                                          AgentGraphSnapshotServiceImpl snapshotService,
                                          AgentToolInternalConfigMapper internalToolMapper,
                                          AgentToolExternalConfigMapper externalToolMapper,
                                          AgentGraphExecutionMapper executionMapper,
                                          AgentGraphExecutionNodeMapper executionNodeMapper,
                                          LoginContextProvider loginContextProvider,
                                          DeptScopeProvider deptScopeProvider) {
        this.objectMapper = objectMapper;
        this.modelRuntimeService = modelRuntimeService;
        this.snapshotService = snapshotService;
        this.internalToolMapper = internalToolMapper;
        this.externalToolMapper = externalToolMapper;
        this.executionMapper = executionMapper;
        this.executionNodeMapper = executionNodeMapper;
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    @Override
    public AgentGraphExecuteRespDTO execute(Long graphDefId, String input) {
        return execute(graphDefId, input, null);
    }

    /** G1-4 END outputs 解析：source=nodeId.var 取节点作用域输出，否则取全局变量。 */
    private java.util.Map<String, String> resolveOutputs(ProcessGraph graph,
                                                         AgentGraphInterpreter interpreter) {
        java.util.Map<String, String> outputs = new java.util.LinkedHashMap<>();
        for (GraphElement element : graph.getElements()) {
            if (!"node".equals(element.getKind())
                    || !AgentGraphInterpreter.NODE_TYPE_END.equals(element.getType())) {
                continue;
            }
            Object raw = element.getConfig() == null ? null
                    : element.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_OUTPUT_PARAMS);
            if (!(raw instanceof List<?> params)) continue;
            for (Object item : params) {
                if (!(item instanceof java.util.Map<?, ?> param)) continue;
                String name = param.get("name") == null ? ""
                        : String.valueOf(param.get("name")).trim();
                String source = param.get("source") == null ? ""
                        : String.valueOf(param.get("source")).trim();
                if (name.isEmpty() || source.isEmpty()) continue;
                String value;
                int dot = source.indexOf('.');
                if (dot > 0) {
                    value = interpreter.getNodeOutputVariables(source.substring(0, dot))
                            .get(source.substring(dot + 1));
                } else {
                    value = interpreter.getFinalVariables().get(source);
                }
                if (value != null) {
                    outputs.put(name, value);
                }
            }
        }
        return outputs;
    }

    @Override
    public com.sw.ck.agent.dto.AgentNodeRunRespDTO runNode(Long graphDefId, String nodeId,
                                                           java.util.Map<String, String> inputs) {
        AgentGraphDef entity = requireEntity(graphDefId);
        if (!STATUS_PUBLISHED.equals(entity.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图未发布，无法运行节点");
        }
        ProcessGraph graph = snapshotService.loadPublishedGraph(entity);
        GraphElement target = graph == null || graph.getElements() == null ? null
                : graph.getElements().stream()
                        .filter(el -> "node".equals(el.getKind()) && nodeId != null
                                && nodeId.equals(el.getId()))
                        .findFirst().orElse(null);
        if (target == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "节点不存在: " + nodeId);
        }
        String type = target.getType();
        boolean runnable = AgentGraphInterpreter.NODE_TYPE_LLM.equals(type)
                || AgentGraphInterpreter.NODE_TYPE_TOOL.equals(type)
                || AgentGraphInterpreter.NODE_TYPE_KNOWLEDGE.equals(type);
        if (!runnable) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该节点类型不支持单独运行");
        }
        validateForExecution(graph);
        String outputVar = resolveOutputVar(target);
        ProcessGraph debugGraph = ProcessGraph.builder()
                .graphKey("debug-single-node")
                .name("debug-single-node")
                .version(entity.getDefVersion())
                .elements(java.util.List.of(
                        GraphElement.builder().id("debug-start").kind("node")
                                .type(AgentGraphInterpreter.NODE_TYPE_START)
                                .config(java.util.Map.of()).build(),
                        target,
                        GraphElement.builder().id("debug-end").kind("node")
                                .type(AgentGraphInterpreter.NODE_TYPE_END)
                                .config(java.util.Map.of(
                                        AgentGraphInterpreter.CONFIG_KEY_INPUT_VAR, outputVar))
                                .build(),
                        GraphElement.builder().id("debug-e1").kind("edge")
                                .source("debug-start").target(nodeId).build(),
                        GraphElement.builder().id("debug-e2").kind("edge")
                                .source(nodeId).target("debug-end").build()))
                .canvas(java.util.Map.of())
                .build();
        AgentGraphInterpreter interpreter = new AgentGraphInterpreter(modelRuntimeService,
                agentToolCallbackFactory, knowledgeRetrievalService,
                loginContextProvider.getTenantId(),
                Math.max(8, graph.getElements().size() * 2));
        long start = System.currentTimeMillis();
        com.sw.ck.agent.dto.AgentNodeRunRespDTO resp = new com.sw.ck.agent.dto.AgentNodeRunRespDTO();
        try {
            String output = interpreter.run(debugGraph,
                    inputs == null ? java.util.Map.of() : inputs);
            resp.setSuccess(true);
            resp.setStatus("SUCCESS");
            resp.setOutput(output);
        } catch (RuntimeException failure) {
            resp.setSuccess(false);
            resp.setStatus("FAILED");
            resp.setErrorMessage(summarizeError(failure));
        }
        resp.setLatencyMs(System.currentTimeMillis() - start);
        return resp;
    }

    /** 节点输出变量名（config.outputVar，缺省默认变量）。 */
    private String resolveOutputVar(GraphElement node) {
        Object configured = node.getConfig() == null ? null
                : node.getConfig().get(AgentGraphInterpreter.CONFIG_KEY_OUTPUT_VAR);
        return configured instanceof String s && !s.isBlank()
                ? s : AgentGraphInterpreter.DEFAULT_VARIABLE_NAME;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentGraphExecuteRespDTO execute(Long graphDefId, String input,
                                            java.util.Map<String, String> inputs) {
        AgentGraphDef entity = requireEntity(graphDefId);
        ProcessGraph graph = requireExecutableGraph(entity);
        validateForExecution(graph);
        ExecutionOutcome outcome = runInternal(entity, entity.getDefVersion(), input, inputs,
                null, null, 0, new java.util.HashSet<>(), null);
        if (outcome.pauseState != null) {
            // 同步接口不支持人工等待：把本次执行标为失败并给出明确原因
            markExecutionFailed(outcome.executionId, AgentGraphInterpreter.ERROR_CATEGORY_HUMAN_PAUSE,
                    "运行到人工节点，需通过异步执行与运行页处理");
            AgentGraphExecuteRespDTO resp = outcome.response;
            resp.setSuccess(false);
            resp.setErrorMessage("运行到人工节点，需通过异步执行与运行页处理");
            return resp;
        }
        return outcome.response;
    }

    @Override
    public ExecutionOutcome runLifecycle(Long graphDefId, Integer graphDefVersion, String input,
                                         java.util.Map<String, String> inputs,
                                         AgentGraphInterpreter.PauseState resumeState,
                                         java.util.List<AgentGraphInterpreter.HumanDecision> decisions,
                                         Long resumeExecutionId) {
        AgentGraphDef entity = requireEntity(graphDefId);
        Integer version = graphDefVersion == null ? entity.getDefVersion() : graphDefVersion;
        ProcessGraph graph = graphByVersion(entity, version);
        validateForExecution(graph);
        return runInternal(entity, version, input, inputs, resumeState, decisions, 0,
                new java.util.HashSet<>(), resumeExecutionId);
    }

    /** 校验可执行：已发布 + 图数据非空（快照按当前版本）。 */
    private ProcessGraph requireExecutableGraph(AgentGraphDef entity) {
        if (!STATUS_PUBLISHED.equals(entity.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图未发布，无法执行");
        }
        // D022-06：执行读取发布快照（按 defVersion 绑定），不读可被草稿编辑覆盖的 graph_json
        ProcessGraph graph = snapshotService.loadPublishedGraph(entity);
        if (graph == null || graph.getElements() == null || graph.getElements().isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图数据为空，无法执行");
        }
        return graph;
    }

    /** 按固定版本读取图快照（G3：恢复执行与子图绑定固定 defVersion）。 */
    private ProcessGraph graphByVersion(AgentGraphDef entity, Integer defVersion) {
        if (!STATUS_PUBLISHED.equals(entity.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图未发布，无法执行");
        }
        ProcessGraph graph = snapshotService.loadGraphByVersion(entity, defVersion);
        if (graph == null || graph.getElements() == null || graph.getElements().isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "图数据为空，无法执行");
        }
        return graph;
    }

    /** 同步路径人工暂停：已创建的等待执行记录按失败收尾，避免悬挂等待。 */
    private void markExecutionFailed(Long executionId, String category, String message) {
        if (executionId == null) return;
        AgentGraphExecution exec = executionMapper.selectById(executionId);
        if (exec == null) return;
        exec.setStatus(STATUS_FAILED);
        exec.setErrorCategory(category);
        exec.setErrorMessage(message);
        executionMapper.updateById(exec);
    }

    /**
     * 执行核心（G3）：建执行记录 → 解释执行（支持恢复状态/人工决策/子图运行器）→
     * 终态回写与节点轨迹落库。人工节点暂停时返回携带暂停状态与任务定义的结果，
     * 不写终态（执行记录保持 WAITING_HUMAN，由生命周期服务接续）。
     */
    private ExecutionOutcome runInternal(AgentGraphDef entity, Integer defVersion, String input,
                                         java.util.Map<String, String> inputs,
                                         AgentGraphInterpreter.PauseState resumeState,
                                         java.util.List<AgentGraphInterpreter.HumanDecision> decisions,
                                         int depth, java.util.Set<Long> graphChain,
                                         Long resumeExecutionId) {
        boolean structured = inputs != null && !inputs.isEmpty();
        if (resumeState == null && (input == null || input.isBlank()) && !structured) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "input 不能为空");
        }
        ProcessGraph graph = depth == 0 ? requireExecutableGraph(entity)
                : graphByVersion(entity, defVersion);
        if (depth == 0) {
            validateForExecution(graph);
        }
        // V3（planning-review-110-02）：人工恢复复用暂停前的执行记录，暂停前后同一条执行
        AgentGraphExecution exec = null;
        boolean resumedExecution = false;
        if (resumeExecutionId != null && depth == 0 && resumeState != null) {
            exec = executionMapper.selectById(resumeExecutionId);
            if (exec != null) {
                resumedExecution = true;
                exec.setStatus(STATUS_RUNNING);
            }
        }
        if (exec == null) {
            exec = new AgentGraphExecution();
            exec.setGraphDefId(entity.getId());
            exec.setGraphDefVersion(defVersion == null ? entity.getDefVersion() : defVersion);
            exec.setStatus(STATUS_RUNNING);
            exec.setInput(input == null ? "" : input);
            executionMapper.insert(exec);
        }
        final AgentGraphExecution currentExec = exec;

        List<GraphElement> elements = graph.getElements();
        int nodeCount = (int) elements.stream().filter(e -> "node".equals(e.getKind())).count();
        int loopBudget = 0;
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind())
                    && AgentGraphInterpreter.NODE_TYPE_LOOP.equals(element.getType())) {
                loopBudget += maxIterationsOf(element);
            }
        }
        int maxSteps = nodeCount * 2 + loopBudget * nodeCount;
        AgentGraphInterpreter interpreter = new AgentGraphInterpreter(modelRuntimeService,
                agentToolCallbackFactory, knowledgeRetrievalService,
                loginContextProvider.getTenantId(), maxSteps);

        // V3 返修（planning-review-110-02 G3-4）：顶层新执行同样需要 subgraphRunner，
        // 否则含子图节点的图在生命周期首跑必然报「子图运行器未配置」。恢复/决策/子调用
        // 场景仅在字段取值上有差异，统一在此构建 RunOptions。
        AgentGraphInterpreter.RunOptions options = new AgentGraphInterpreter.RunOptions();
        options.resumeState = resumeState;
        options.decisions = decisions == null ? java.util.List.of() : decisions;
        options.depth = depth;
        options.subgraphRunner = call -> runSubgraph(call, currentExec.getId(), depth, graphChain);

        long start = System.currentTimeMillis();
        AgentGraphExecuteRespDTO resp = new AgentGraphExecuteRespDTO();
        List<AgentGraphInterpreter.NodeExecutionTrace> traces;
        Throwable failure = null;
        AgentGraphInterpreter.HumanPauseException pause = null;
        int timeoutSeconds = entity.getExecutionTimeoutSeconds() == null
                ? DEFAULT_EXECUTION_TIMEOUT_SECONDS : entity.getExecutionTimeoutSeconds();
        com.sw.ck.security.holder.LoginUser loginUser =
                com.sw.ck.security.holder.LoginUserHolder.get();
        final AgentGraphInterpreter.RunOptions runOptions = options;
        java.util.concurrent.Future<String> future =
                timeoutExecutor.submit(() -> {
                    if (loginUser != null) {
                        com.sw.ck.security.holder.LoginUserHolder.set(loginUser);
                    }
                    try {
                        // G1-4：结构化入参优先；无结构化入参走旧单文本契约（写入默认变量）
                        java.util.Map<String, String> effectiveInputs = structured
                                ? inputs
                                : java.util.Map.of(AgentGraphInterpreter.DEFAULT_VARIABLE_NAME, input == null ? "" : input);
                        return interpreter.run(graph, effectiveInputs, runOptions);
                    } finally {
                        com.sw.ck.security.holder.LoginUserHolder.clear();
                    }
                });
        try {
            String output = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            resp.setSuccess(true);
            resp.setOutput(output);
            traces = interpreter.getTraces();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            traces = interpreter.getTraces();
            if (cause instanceof AgentGraphInterpreter.HumanPauseException humanPause) {
                pause = humanPause;
                resp.setSuccess(false);
            } else {
                failure = cause;
                resp.setSuccess(false);
                resp.setErrorMessage(summarizeError(failure));
            }
        } catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true);
            failure = new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_GRAPH_TIMEOUT,
                    "图执行超时（超过 " + timeoutSeconds + " 秒）");
            resp.setSuccess(false);
            resp.setErrorMessage(failure.getMessage());
            traces = interpreter.getTraces();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            failure = e;
            resp.setSuccess(false);
            resp.setErrorMessage("执行被中断");
            traces = interpreter.getTraces();
        }
        long latency = System.currentTimeMillis() - start;
        resp.setLatencyMs(latency);
        resp.setExecutionId(exec.getId());

        ExecutionOutcome outcome = new ExecutionOutcome();
        outcome.response = resp;
        outcome.executionId = exec.getId();
        outcome.pauseState = pause == null ? null : pause.getPauseState();
        outcome.humanTask = pause == null ? null : pause.getTaskSpec();
        if (pause != null) {
            // 暂停：执行记录保持等待态，轨迹落库；不写终态
            exec.setStatus(STATUS_WAITING_HUMAN);
            exec.setLatencyMs(latency);
            executionMapper.updateById(exec);
            persistNodeTraces(exec.getId(), traces, resumedExecution);
            return outcome;
        }

        // G1-4：按 END 节点 outputs 声明解析结构化出参（成功路径；无声明为空 map）
        if (resp.isSuccess()) {
            resp.setOutputs(resolveOutputs(graph, interpreter));
        }
        exec.setStatus(resp.isSuccess() ? STATUS_SUCCESS : STATUS_FAILED);
        exec.setResultText(resp.getOutput());
        String errorCategory = failure == null ? null : classifyError(failure);
        exec.setErrorCategory(errorCategory);
        // X3：分类随响应返回，生命周期受理行与执行行保持同一分类（如 HUMAN_TIMEOUT）
        resp.setErrorCategory(errorCategory);
        exec.setErrorMessage(resp.getErrorMessage());
        exec.setLatencyMs(latency);
        executionMapper.updateById(exec);
        persistNodeTraces(exec.getId(), traces, resumedExecution);
        return outcome;
    }

    /** G3-4/5：子图执行器（固定版本、父子执行记录关联、嵌套深度与引用链防环、取消透传）。 */
    private AgentGraphInterpreter.SubgraphResult runSubgraph(AgentGraphInterpreter.SubgraphCall call,
                                                             Long parentExecutionId, int depth,
                                                             java.util.Set<Long> graphChain) {
        if (depth + 1 > MAX_SUBGRAPH_DEPTH) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "子图嵌套深度超过上限 " + MAX_SUBGRAPH_DEPTH);
        }
        if (graphChain.contains(call.graphDefId)) {
            throw new AgentGraphInterpreter.GraphExecutionException(
                    AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "检测到子图引用环: graphDefId=" + call.graphDefId);
        }
        AgentGraphDef child = requireEntity(call.graphDefId);
        if (!STATUS_PUBLISHED.equals(child.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "子图未发布: " + call.graphDefId);
        }
        Integer version = call.graphDefVersion == null ? child.getDefVersion() : call.graphDefVersion;
        ProcessGraph childGraph = snapshotService.loadGraphByVersion(child, version);
        if (childGraph == null || childGraph.getElements() == null
                || childGraph.getElements().isEmpty()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "子图数据为空: " + call.graphDefId);
        }
        validateForExecution(childGraph);

        AgentGraphExecution childExec = new AgentGraphExecution();
        childExec.setGraphDefId(child.getId());
        childExec.setGraphDefVersion(version);
        childExec.setParentExecutionId(parentExecutionId);
        childExec.setStatus(STATUS_RUNNING);
        try {
            childExec.setInput(objectMapper.writeValueAsString(call.inputs));
        } catch (Exception ignored) {
            childExec.setInput("{}");
        }
        executionMapper.insert(childExec);

        int childNodeCount = (int) childGraph.getElements().stream()
                .filter(e -> "node".equals(e.getKind())).count();
        AgentGraphInterpreter childInterpreter = new AgentGraphInterpreter(modelRuntimeService,
                agentToolCallbackFactory, knowledgeRetrievalService,
                loginContextProvider.getTenantId(), Math.max(8, childNodeCount * 2));
        AgentGraphInterpreter.RunOptions childOptions = new AgentGraphInterpreter.RunOptions();
        childOptions.depth = depth + 1;
        childOptions.resumeState = call.resumeState;
        childOptions.decisions = call.decisions == null ? java.util.List.of() : call.decisions;
        java.util.Set<Long> childChain = new java.util.HashSet<>(graphChain);
        childChain.add(call.graphDefId);
        childOptions.subgraphRunner = nested -> runSubgraph(nested, childExec.getId(), depth + 1,
                childChain);

        long start = System.currentTimeMillis();
        try {
            String output = childInterpreter.run(childGraph, call.inputs, childOptions);
            long latency = System.currentTimeMillis() - start;
            childExec.setStatus(STATUS_SUCCESS);
            childExec.setResultText(output);
            childExec.setLatencyMs(latency);
            executionMapper.updateById(childExec);
            persistNodeTraces(childExec.getId(), childInterpreter.getTraces());
            AgentGraphInterpreter.SubgraphResult result = new AgentGraphInterpreter.SubgraphResult();
            result.outputs = resolveOutputs(childGraph, childInterpreter);
            result.childExecutionId = childExec.getId();
            return result;
        } catch (AgentGraphInterpreter.HumanPauseException pause) {
            long latency = System.currentTimeMillis() - start;
            childExec.setStatus(STATUS_WAITING_HUMAN);
            childExec.setLatencyMs(latency);
            executionMapper.updateById(childExec);
            persistNodeTraces(childExec.getId(), childInterpreter.getTraces());
            // 父执行通过异常链感知子图人工暂停（父级会合并自身暂停状态）
            throw pause;
        } catch (RuntimeException failure) {
            long latency = System.currentTimeMillis() - start;
            boolean cancelled = Thread.currentThread().isInterrupted();
            childExec.setStatus(cancelled ? STATUS_CANCELLED : STATUS_FAILED);
            childExec.setErrorCategory(cancelled ? "CANCELLED" : classifyError(failure));
            childExec.setErrorMessage(cancelled ? "父执行取消，子执行一并取消" : summarizeError(failure));
            childExec.setLatencyMs(latency);
            executionMapper.updateById(childExec);
            persistNodeTraces(childExec.getId(), childInterpreter.getTraces());
            throw failure;
        }
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
        persistNodeTraces(executionId, traces, false);
    }

    private void persistNodeTraces(Long executionId,
                                   List<AgentGraphInterpreter.NodeExecutionTrace> traces,
                                   boolean resumed) {
        if (traces == null || traces.isEmpty()) {
            return;
        }
        // V3（planning-review-110-02）：恢复执行在同一执行记录上续写轨迹——
        // 清掉暂停期遗留的 WAITING 行（人工节点在恢复轮会重新产生完成态记录），
        // 新轨迹步序接在已有记录之后，保证轨迹从开始到结束完整连续。
        int seqOffset = 0;
        if (resumed) {
            executionNodeMapper.delete(Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                    .eq(AgentGraphExecutionNode::getExecutionId, executionId)
                    .eq(AgentGraphExecutionNode::getStatus, "WAITING"));
            List<AgentGraphExecutionNode> lastSeq = executionNodeMapper.selectList(
                    Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                            .eq(AgentGraphExecutionNode::getExecutionId, executionId)
                            .orderByDesc(AgentGraphExecutionNode::getNodeSeq)
                            .last("LIMIT 1"));
            if (!lastSeq.isEmpty() && lastSeq.get(0).getNodeSeq() != null) {
                seqOffset = lastSeq.get(0).getNodeSeq();
            }
        }
        // M07-F04-02: 汇总 token 数据（每侧独立：无数据侧保持 null，不得写成 0——
        // 部分 usage 场景"输出未知"必须与"输出 0"区分，贯穿 DB→DTO）
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        boolean hasInputData = false;
        boolean hasOutputData = false;
        // V3：恢复执行时在既有汇总上累加，不覆盖暂停前的 token 统计
        if (resumed) {
            AgentGraphExecution existing = executionMapper.selectById(executionId);
            if (existing != null) {
                if (existing.getInputTokens() != null) {
                    totalInputTokens += existing.getInputTokens();
                    hasInputData = true;
                }
                if (existing.getOutputTokens() != null) {
                    totalOutputTokens += existing.getOutputTokens();
                    hasOutputData = true;
                }
            }
        }

        for (AgentGraphInterpreter.NodeExecutionTrace trace : traces) {
            AgentGraphExecutionNode node = new AgentGraphExecutionNode();
            node.setExecutionId(executionId);
            node.setNodeSeq(seqOffset + (int) trace.getNodeSeq());
            node.setBranchId(trace.getBranchId());
            node.setNodeId(trace.getNodeId());
            node.setNodeType(trace.getNodeType());
            // G3-4：SUBGRAPH 节点持久化子执行 id，执行详情可跳转父子执行
            node.setChildExecutionId(trace.getChildExecutionId());
            node.setNodeLatencyMs(trace.getNodeLatencyMs());
            node.setVariableSnapshot(toJson(trace.getVariableSnapshot()));
            // M07-F04-02: 设置 token 字段
            node.setInputTokens(trace.getInputTokens());
            node.setOutputTokens(trace.getOutputTokens());
            // 阶段 2：工具调用轨迹（空列表不落库）
            if (trace.getToolCalls() != null && !trace.getToolCalls().isEmpty()) {
                node.setToolCalls(toJson(trace.getToolCalls()));
            }
            // F1S2-G1：节点真实输入/输出、时间与状态（执行详情节点卡片展示）
            node.setInputText(trace.getInputText());
            node.setOutputText(trace.getOutputText());
            node.setStartTime(trace.getStartTime());
            node.setEndTime(trace.getEndTime());
            node.setStatus(trace.getStatus());
            node.setErrorMessage(trace.getErrorMessage());
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
    private String toJson(Object variables) {
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
        // G3-4：子图节点子执行 id（父子执行详情跳转）
        dto.setChildExecutionId(node.getChildExecutionId());
        dto.setNodeLatencyMs(node.getNodeLatencyMs());
        dto.setVariableSnapshot(node.getVariableSnapshot());
        // M07-F04-02: 添加 token 字段
        dto.setInputTokens(node.getInputTokens());
        dto.setOutputTokens(node.getOutputTokens());
        dto.setToolCalls(node.getToolCalls());
        // F1S2-G1：节点真实输入/输出与时间（历史行缺省值兜底为成功）
        dto.setInput(node.getInputText());
        dto.setOutput(node.getOutputText());
        dto.setStartTime(node.getStartTime());
        dto.setEndTime(node.getEndTime());
        dto.setStatus(node.getStatus() != null ? node.getStatus() : "SUCCESS");
        dto.setErrorMessage(node.getErrorMessage());
        return dto;
    }

    // ==================== 执行前校验（方案 §2-D） ====================

    /**
     * 执行前最小校验（非完整拓扑校验器，方案 §3 已裁定）：
     * ①PUBLISHED（调用方已校验）②唯一 START + 至少一个 END 可达 ③LLM 节点
     * agentModelConfigId 可解析到租户内启用模型（含供应商启停）④TOOL 节点 toolName 精确
     * 匹配 enabled=1 白名单 ⑤CONDITION 出边默认边唯一 ⑥FORK 出边数 ≥ 2 ⑦JOIN 入边数
     * ≥ 2 ⑧LOOP maxIterations（存在时）≥ 1。
     * <p>
     * 不做"循环体可达退出路径"静态分析（方案 §2.4 判断非必要：运行时已有 maxIterations
     * + 全局步数兜底双层防护，且循环体路径依赖动态变量匹配易误报）。既有 BFS visited
     * 去重天然容忍环。
     * </p>
     *
     */
    private void validateForExecution(ProcessGraph graph) {
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
        for (GraphElement node : nodes) {
            switch (node.getType()) {
                case AgentGraphInterpreter.NODE_TYPE_LLM -> {
                    Object idObj = configValue(node, AgentGraphInterpreter.CONFIG_KEY_AGENT_MODEL_CONFIG_ID);
                    // 接受 Number/数字字符串：graph_json 落库后 Long 一律是字符串形态
                    // （全局 Jackson Long→String），仅收 Number 会让已发布图无法执行
                    Long modelConfigId = AgentGraphInterpreter.parseConfigLong(idObj);
                    if (modelConfigId == null) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LLM 节点缺少模型配置引用: " + node.getId());
                    }
                    // F1 阶段 1：经运行时服务校验（存在性 + 模型/供应商启停），并预热单例
                    modelRuntimeService.assertUsable(modelConfigId);
                    // 阶段 2：节点两级选择（供应商 + 模型）时校验归属一致
                    Long providerId = AgentGraphInterpreter.parseConfigLong(
                            configValue(node, AgentGraphInterpreter.CONFIG_KEY_PROVIDER_ID));
                    if (providerId != null) {
                        AgentModelRuntimeService.ModelInstance instance =
                                modelRuntimeService.acquire(modelConfigId);
                        if (instance != null && !providerId.equals(instance.provider().getId())) {
                            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                    "LLM 节点所选供应商与模型不匹配: " + node.getId());
                        }
                    }
                    // 阶段 2：节点挂载的工具必须存在且启用
                    List<String> toolNames = AgentGraphInterpreter.configStringList(
                            node, AgentGraphInterpreter.CONFIG_KEY_TOOL_NAMES);
                    if (!toolNames.isEmpty()) {
                        if (agentToolCallbackFactory == null) {
                            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                    "工具工厂未装配，无法执行 LLM 节点工具: " + node.getId());
                        }
                        for (String toolName : toolNames) {
                            if (!toolExists(toolName)) {
                                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                        "LLM 节点引用的工具不存在或未启用: " + toolName);
                            }
                        }
                    }
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
                case AgentGraphInterpreter.NODE_TYPE_KNOWLEDGE -> {
                    Long kbId = AgentGraphInterpreter.parseConfigLong(
                            configValue(node, AgentGraphInterpreter.CONFIG_KEY_KNOWLEDGE_BASE_ID));
                    if (kbId == null) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "KNOWLEDGE 节点缺少知识库引用: " + node.getId());
                    }
                    int topK = AgentGraphInterpreter.configInt(node,
                            AgentGraphInterpreter.CONFIG_KEY_TOP_K,
                            AgentGraphInterpreter.DEFAULT_KNOWLEDGE_TOP_K);
                    if (topK < 1 || topK > 20) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "KNOWLEDGE 节点 topK 必须在 1~20 之间: " + node.getId());
                    }
                    double threshold = AgentGraphInterpreter.configDouble(node,
                            AgentGraphInterpreter.CONFIG_KEY_SIMILARITY_THRESHOLD, 0.0);
                    if (threshold < 0.0 || threshold > 1.0) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "KNOWLEDGE 节点相似度阈值必须在 0~1 之间: " + node.getId());
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
                    Long parsed = AgentGraphInterpreter.parseConfigLong(maxIterations);
                    if (parsed != null && parsed < 1) {
                        throw new BaseException(CommonErrorCode.PARAM_ERROR,
                                "LOOP 节点 maxIterations 必须 ≥ 1: " + node.getId());
                    }
                }
                default -> { /* START/END 及其他：无执行前校验 */ }
            }
        }
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
        Long parsed = AgentGraphInterpreter.parseConfigLong(value);
        if (parsed == null) {
            return AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;
        }
        return parsed.intValue();
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
     * 工具白名单精确匹配：internal/external 两表任一存在 name 精确相等且 enabled=true 的记录。
     * enabled 用 Boolean 绑定，由驱动映射；PG 的 V0.2.3 已把该列转为 BOOLEAN。
     */
    private boolean toolExists(String toolName) {
        Long internal = internalToolMapper.selectCount(
                Wrappers.<AgentToolInternalConfig>lambdaQuery()
                        .eq(AgentToolInternalConfig::getName, toolName)
                        .eq(AgentToolInternalConfig::getEnabled, true));
        if (internal != null && internal > 0) {
            return true;
        }
        Long external = externalToolMapper.selectCount(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getName, toolName)
                        .eq(AgentToolExternalConfig::getEnabled, true));
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
