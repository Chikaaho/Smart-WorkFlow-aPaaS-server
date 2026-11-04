package com.sw.ck.agent.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.common.exception.BaseException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可单步调试的图执行引擎（M07-F02-04）。
 * <p>
 * 语义与 {@link AgentGraphInterpreter} 完全一致，但将 {@code run()} 的 while 循环拆为
 * 可跨 HTTP 请求恢复的单步 {@link #step()}。持有与解释器 run() 循环前初始化完全一致的
 * 状态：variables / activePoints / loopCounts / joinCounts / traceSeq / steps。
 * 每次 step() 仅执行一个活跃点的单节点访问（FIFO 取队首），含完整的节点执行、路由与轨迹采集。
 * </p>
 * <p>
 * 状态可经 {@link #serializeState(ObjectMapper)} 序列化为 JSON，随调试会话落库；
 * 下次请求经 {@link #deserializeState(String, ObjectMapper, ProcessGraph, int, ChatModelFactory, AgentToolCallbackFactory, Map, AesGcmCipher, Long)}
 * 恢复后继续推进。
 * </p>
 */
public class AgentGraphDebugEngine {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]*)}}");

    private static final String ROOT_BRANCH_ID = "0";

    // 复用解释器常量，避免语义漂移
    private static final String NODE_TYPE_START = AgentGraphInterpreter.NODE_TYPE_START;
    private static final String NODE_TYPE_END = AgentGraphInterpreter.NODE_TYPE_END;
    private static final String NODE_TYPE_LLM = AgentGraphInterpreter.NODE_TYPE_LLM;
    private static final String NODE_TYPE_TOOL = AgentGraphInterpreter.NODE_TYPE_TOOL;
    private static final String NODE_TYPE_CONDITION = AgentGraphInterpreter.NODE_TYPE_CONDITION;
    private static final String NODE_TYPE_LOOP = AgentGraphInterpreter.NODE_TYPE_LOOP;
    private static final String NODE_TYPE_FORK = AgentGraphInterpreter.NODE_TYPE_FORK;
    private static final String NODE_TYPE_JOIN = AgentGraphInterpreter.NODE_TYPE_JOIN;

    private static final String CONFIG_KEY_AGENT_MODEL_CONFIG_ID = AgentGraphInterpreter.CONFIG_KEY_AGENT_MODEL_CONFIG_ID;
    private static final String CONFIG_KEY_TOOL_NAME = AgentGraphInterpreter.CONFIG_KEY_TOOL_NAME;
    private static final String CONFIG_KEY_INPUT_VAR = AgentGraphInterpreter.CONFIG_KEY_INPUT_VAR;
    private static final String CONFIG_KEY_OUTPUT_VAR = AgentGraphInterpreter.CONFIG_KEY_OUTPUT_VAR;
    private static final String CONFIG_KEY_MAX_ITERATIONS = AgentGraphInterpreter.CONFIG_KEY_MAX_ITERATIONS;
    private static final String CONFIG_KEY_SYSTEM_PROMPT = AgentGraphInterpreter.CONFIG_KEY_SYSTEM_PROMPT;
    private static final String CONFIG_KEY_USER_PROMPT_TEMPLATE = AgentGraphInterpreter.CONFIG_KEY_USER_PROMPT_TEMPLATE;

    private static final String DEFAULT_VARIABLE_NAME = AgentGraphInterpreter.DEFAULT_VARIABLE_NAME;
    private static final int DEFAULT_MAX_ITERATIONS = AgentGraphInterpreter.DEFAULT_MAX_ITERATIONS;

    private static final String ERROR_CATEGORY_STEP_LIMIT = AgentGraphInterpreter.ERROR_CATEGORY_STEP_LIMIT;
    private static final String ERROR_CATEGORY_LOOP_LIMIT = AgentGraphInterpreter.ERROR_CATEGORY_LOOP_LIMIT;
    private static final String ERROR_CATEGORY_UNDEFINED_VARIABLE = AgentGraphInterpreter.ERROR_CATEGORY_UNDEFINED_VARIABLE;
    private static final String ERROR_CATEGORY_CONDITION_NO_MATCH = AgentGraphInterpreter.ERROR_CATEGORY_CONDITION_NO_MATCH;
    private static final String ERROR_CATEGORY_TOPOLOGY_INVALID = AgentGraphInterpreter.ERROR_CATEGORY_TOPOLOGY_INVALID;
    private static final String ERROR_CATEGORY_MODEL_CALL_FAILED = AgentGraphInterpreter.ERROR_CATEGORY_MODEL_CALL_FAILED;
    private static final String ERROR_CATEGORY_TOOL_CALL_FAILED = AgentGraphInterpreter.ERROR_CATEGORY_TOOL_CALL_FAILED;

    /** 工具入参/返回 JSON 编码（Jackson，替代旧 spring-ai JsonParser） */
    private static final ObjectMapper JSON = new ObjectMapper();

    // ==================== 状态 ====================

    private final ProcessGraph graph;
    private final int maxSteps;

    private final AgentModelRuntimeService modelRuntimeService;
    private final AgentToolCallbackFactory toolCallbackFactory;
    private final Long tenantId;

    private Map<String, String> variables;
    private List<ActivePoint> activePoints;
    private Map<String, Integer> loopCounts;
    private Map<String, Integer> joinCounts;
    private long traceSeq;
    private int steps;

    // 最近一次 step 产生的 trace（供调用方读取落库），失败路径同样留痕
    private AgentGraphInterpreter.NodeExecutionTrace lastTrace;

    /**
     * 初始化引擎：语义与 {@link AgentGraphInterpreter#run(ProcessGraph, String)} 循环前一致。
     */
    public AgentGraphDebugEngine(ProcessGraph graph,
                                 String input,
                                 int maxSteps,
                                 AgentModelRuntimeService modelRuntimeService,
                                 AgentToolCallbackFactory toolCallbackFactory,
                                 Long tenantId) {
        this.graph = graph;
        this.maxSteps = maxSteps;
        this.modelRuntimeService = modelRuntimeService;
        this.toolCallbackFactory = toolCallbackFactory;
        this.tenantId = tenantId;

        this.variables = new HashMap<>();
        this.variables.put(DEFAULT_VARIABLE_NAME, input);
        this.activePoints = new ArrayList<>();
        this.activePoints.add(new ActivePoint(findStart(graph.getElements()).getId(), ROOT_BRANCH_ID));
        this.loopCounts = new HashMap<>();
        this.joinCounts = new HashMap<>();
        this.traceSeq = 0;
        this.steps = 0;
    }

    /**
     * 私有构造：用于反序列化恢复。
     */
    private AgentGraphDebugEngine(ProcessGraph graph,
                                  int maxSteps,
                                  AgentModelRuntimeService modelRuntimeService,
                                  AgentToolCallbackFactory toolCallbackFactory,
                                  Long tenantId,
                                  Map<String, String> variables,
                                  List<ActivePoint> activePoints,
                                  Map<String, Integer> loopCounts,
                                  Map<String, Integer> joinCounts,
                                  long traceSeq,
                                  int steps) {
        this.graph = graph;
        this.maxSteps = maxSteps;
        this.modelRuntimeService = modelRuntimeService;
        this.toolCallbackFactory = toolCallbackFactory;
        this.tenantId = tenantId;
        this.variables = variables;
        this.activePoints = activePoints;
        this.loopCounts = loopCounts;
        this.joinCounts = joinCounts;
        this.traceSeq = traceSeq;
        this.steps = steps;
    }

    // ==================== 单步执行 ====================

    /**
     * 执行单步：FIFO 取队首活跃点，执行一个节点访问。
     *
     * @return 单步结果（含 trace、是否终态、END 输出）
     * @throws AgentGraphInterpreter.GraphExecutionException 运行时错误（已带分类）
     */
    public StepResult step() {
        if (activePoints.isEmpty()) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "所有执行点已终止但未到达 END 节点（JOIN 汇合入边数无法满足）");
        }
        List<GraphElement> elements = graph.getElements();
        ActivePoint point = activePoints.remove(0);
        GraphElement current = findNode(point.getNodeId(), elements);
        long stepStartNanos = System.nanoTime();
        AgentGraphInterpreter.NodeExecutionTrace trace =
                new AgentGraphInterpreter.NodeExecutionTrace(++traceSeq, point.getBranchPath(),
                        current.getId(), current.getType());
        lastTrace = trace;
        try {
            if (NODE_TYPE_END.equals(current.getType())) {
                finishTrace(trace, stepStartNanos, variables);
                String result = readVariable(current, variables, CONFIG_KEY_INPUT_VAR);
                return StepResult.terminal(trace, result);
            }
            if (++steps > maxSteps) {
                throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_STEP_LIMIT,
                        "执行步数超限，图可能存在环路");
            }
            switch (current.getType()) {
                case NODE_TYPE_LLM -> writeOutput(current, variables,
                        callLlmNode(current, readInput(current, variables), variables, trace));
                case NODE_TYPE_TOOL -> writeOutput(current, variables,
                        callToolNode(current, readInput(current, variables)));
                case NODE_TYPE_START, NODE_TYPE_CONDITION -> { }
                case NODE_TYPE_LOOP -> {
                    int iteration = loopCounts.merge(current.getId(), 1, Integer::sum);
                    if (iteration > maxIterationsOf(current)) {
                        throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_LOOP_LIMIT,
                                "循环迭代次数超限: " + current.getId());
                    }
                }
                case NODE_TYPE_FORK -> { /* 扇出：无动作，路由段按全部出边产出多活跃点 */ }
                case NODE_TYPE_JOIN -> {
                    int arrived = joinCounts.merge(current.getId(), 1, Integer::sum);
                    if (arrived < incomingEdgeCount(current, elements)) {
                        finishTrace(trace, stepStartNanos, variables);
                        return StepResult.pendingJoin(trace);
                    }
                }
                default -> throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                        "不支持的节点类型: " + current.getType() + "（节点 " + current.getId() + "）");
            }
            // 后继路由
            if (NODE_TYPE_FORK.equals(current.getType())) {
                int branchIndex = 0;
                for (GraphElement edge : outgoingEdges(current, elements)) {
                    activePoints.add(new ActivePoint(edge.getTarget(),
                            point.getBranchPath() + "-" + branchIndex++));
                }
            } else {
                activePoints.add(new ActivePoint(
                        nextNodeId(current, elements, variables), point.getBranchPath()));
            }
            finishTrace(trace, stepStartNanos, variables);
            return StepResult.nonTerminal(trace);
        } catch (RuntimeException e) {
            finishTrace(trace, stepStartNanos, variables);
            throw e;
        }
    }

    private void finishTrace(AgentGraphInterpreter.NodeExecutionTrace trace, long stepStartNanos,
                             Map<String, String> variables) {
        trace.setNodeLatencyMs((System.nanoTime() - stepStartNanos) / 1_000_000);
        trace.setVariableSnapshot(new HashMap<>(variables));
    }

    // ==================== 序列化 ====================

    /**
     * 序列化当前状态为 JSON。
     */
    public String serializeState(ObjectMapper objectMapper) throws JsonProcessingException {
        DebugState state = new DebugState();
        state.variables = this.variables;
        state.activePoints = this.activePoints;
        state.loopCounts = this.loopCounts;
        state.joinCounts = this.joinCounts;
        state.traceSeq = this.traceSeq;
        state.steps = this.steps;
        return objectMapper.writeValueAsString(state);
    }

    /**
     * 从 JSON 恢复引擎状态。
     */
    public static AgentGraphDebugEngine deserializeState(String json,
                                                        ObjectMapper objectMapper,
                                                        ProcessGraph graph,
                                                        int maxSteps,
                                                        AgentModelRuntimeService modelRuntimeService,
                                                        AgentToolCallbackFactory toolCallbackFactory,
                                                        Long tenantId) throws JsonProcessingException {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("stateJson 不能为空");
        }
        DebugState state = objectMapper.readValue(json, DebugState.class);
        // 防御：null 集合归一为空
        Map<String, String> variables = state.variables != null ? state.variables : new HashMap<>();
        List<ActivePoint> activePoints = state.activePoints != null ? state.activePoints : new ArrayList<>();
        Map<String, Integer> loopCounts = state.loopCounts != null ? state.loopCounts : new HashMap<>();
        Map<String, Integer> joinCounts = state.joinCounts != null ? state.joinCounts : new HashMap<>();
        return new AgentGraphDebugEngine(graph, maxSteps, modelRuntimeService, toolCallbackFactory,
                tenantId,
                variables, activePoints, loopCounts, joinCounts,
                state.traceSeq, state.steps);
    }

    // ==================== 访问器 ====================

    public ProcessGraph getGraph() {
        return graph;
    }

    public Map<String, String> getVariables() {
        return new HashMap<>(variables);
    }

    public List<ActivePoint> getActivePoints() {
        return new ArrayList<>(activePoints);
    }

    public long getTraceSeq() {
        return traceSeq;
    }

    public int getSteps() {
        return steps;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public AgentGraphInterpreter.NodeExecutionTrace getLastTrace() {
        return lastTrace;
    }

    /** 下一个待执行节点 id（队首），无活跃点时为 null */
    public String peekNextNodeId() {
        if (activePoints.isEmpty()) {
            return null;
        }
        return activePoints.get(0).getNodeId();
    }

    /** 下一个待执行分支标识（队首），无活跃点时为 null */
    public String peekNextBranchId() {
        if (activePoints.isEmpty()) {
            return null;
        }
        return activePoints.get(0).getBranchPath();
    }

    public boolean isTerminal() {
        return activePoints.isEmpty();
    }

    // ==================== LLM / TOOL 节点（与解释器同语义） ====================

    private String callLlmNode(GraphElement node, String text, Map<String, String> variables,
                               AgentGraphInterpreter.NodeExecutionTrace currentTrace) {
        Long modelConfigId = requireConfigId(node, CONFIG_KEY_AGENT_MODEL_CONFIG_ID);
        try {
            // F1 阶段 1：经运行时服务解析（启停校验 + LangChain4j 单例复用；
            // D022-05：429 时组内密钥轮换，与编排入口同语义）
            String systemPrompt = configString(node, CONFIG_KEY_SYSTEM_PROMPT);
            String userPromptTemplate = configString(node, CONFIG_KEY_USER_PROMPT_TEMPLATE);
            List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(SystemMessage.from(systemPrompt));
            }
            String userText;
            if (userPromptTemplate == null || userPromptTemplate.isBlank()) {
                userText = text;
            } else {
                userText = interpolateTemplate(userPromptTemplate, node, variables);
            }
            messages.add(UserMessage.from(userText));
            // 阶段 2：调试与图执行共用节点内工具调用循环
            return AgentGraphInterpreter.executeChatWithTools(modelRuntimeService,
                    toolCallbackFactory, tenantId, node, modelConfigId, messages, currentTrace);
        } catch (AgentGraphInterpreter.GraphExecutionException e) {
            throw e;
        } catch (BaseException e) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_MODEL_CALL_FAILED,
                    e.getMessage(), e);
        } catch (Exception e) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_MODEL_CALL_FAILED,
                    e.getMessage(), e);
        }
    }

    private String callToolNode(GraphElement node, String text) {
        String toolName = requireConfigString(node, CONFIG_KEY_TOOL_NAME);
        if (toolCallbackFactory == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                    "工具工厂未装配，无法执行 TOOL 节点: " + toolName);
        }
        AgentToolCallbackFactory.ToolHandle target = toolCallbackFactory.buildToolCallbacks(tenantId).stream()
                .filter(handle -> toolName.equals(handle.specification().name()))
                .findFirst()
                .orElseThrow(() -> new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "TOOL 节点引用的工具不存在或未启用: " + toolName));
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("debug-tool-" + node.getId())
                    .name(toolName)
                    .arguments(JSON.writeValueAsString(text))
                    .build();
            String result = target.executor().execute(request, null);
            String decoded = decodeIfJsonString(result);
            if (decoded == null) {
                throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                        "TOOL 节点未返回文本: " + node.getId());
            }
            return decoded;
        } catch (AgentGraphInterpreter.GraphExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOOL_CALL_FAILED,
                    e.getMessage(), e);
        }
    }

    // ==================== 多变量上下文 ====================

    private String readInput(GraphElement node, Map<String, String> variables) {
        return readVariable(node, variables, CONFIG_KEY_INPUT_VAR);
    }

    private String readVariable(GraphElement node, Map<String, String> variables, String varKey) {
        String varName = resolveVarName(node, varKey);
        String value = variables.get(varName);
        if (value == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_UNDEFINED_VARIABLE,
                    "引用了未定义的变量: " + varName + "（节点 " + node.getId() + "）");
        }
        return value;
    }

    private void writeOutput(GraphElement node, Map<String, String> variables, String output) {
        variables.put(resolveVarName(node, CONFIG_KEY_OUTPUT_VAR), output);
    }

    private String resolveVarName(GraphElement node, String varKey) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return DEFAULT_VARIABLE_NAME;
        }
        Object value = config.get(varKey);
        if (!(value instanceof String s) || s.isBlank()) {
            return DEFAULT_VARIABLE_NAME;
        }
        return s;
    }

    private String configString(GraphElement node, String key) {
        Map<String, Object> cfg = node.getConfig();
        if (cfg == null) {
            return null;
        }
        Object v = cfg.get(key);
        if (!(v instanceof String s)) {
            return null;
        }
        return s;
    }

    private String interpolateTemplate(String template, GraphElement node,
                                       Map<String, String> variables) {
        Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length());
        while (m.find()) {
            String varName = m.group(1);
            String value = variables.get(varName);
            if (value == null) {
                throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_UNDEFINED_VARIABLE,
                        "引用了未定义的变量: " + varName + "（节点 " + node.getId() + "）");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ==================== 条件分支与选路 ====================

    private String nextNodeId(GraphElement current, List<GraphElement> elements,
                              Map<String, String> variables) {
        List<GraphElement> edges = outgoingEdges(current, elements);
        if (NODE_TYPE_CONDITION.equals(current.getType())) {
            String matchText = readInput(current, variables);
            for (GraphElement edge : edges) {
                String keyword = keywordOf(edge);
                if (keyword != null && matchText.contains(keyword)) {
                    return edge.getTarget();
                }
            }
            List<GraphElement> defaultEdges = edges.stream()
                    .filter(e -> keywordOf(e) == null)
                    .toList();
            if (defaultEdges.size() == 1) {
                return defaultEdges.get(0).getTarget();
            }
            if (defaultEdges.isEmpty()) {
                throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_CONDITION_NO_MATCH,
                        "条件分支无匹配且无默认边: " + current.getId());
            }
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "条件分支默认边不唯一: " + current.getId());
        }
        if (edges.isEmpty()) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点没有出边，无法继续执行: " + current.getId());
        }
        if (edges.size() > 1) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "非条件节点的出边不唯一: " + current.getId());
        }
        return edges.get(0).getTarget();
    }

    public static String keywordOf(GraphElement edge) {
        Map<String, Object> config = edge.getConfig();
        if (config == null) {
            return null;
        }
        Object keyword = config.get(AgentGraphInterpreter.CONFIG_KEY_KEYWORD);
        if (!(keyword instanceof String s) || s.isBlank()) {
            return null;
        }
        return s;
    }

    private List<GraphElement> outgoingEdges(GraphElement node, List<GraphElement> elements) {
        List<GraphElement> edges = new ArrayList<>();
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getSource())) {
                edges.add(element);
            }
        }
        return edges;
    }

    private int incomingEdgeCount(GraphElement node, List<GraphElement> elements) {
        int count = 0;
        for (GraphElement element : elements) {
            if ("edge".equals(element.getKind()) && node.getId().equals(element.getTarget())) {
                count++;
            }
        }
        return count;
    }

    private int maxIterationsOf(GraphElement node) {
        Map<String, Object> config = node.getConfig();
        if (config == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        // 接受 Number/数字字符串（graph_json 落库后 Long 为字符串形态，全局 Long→String 约定）
        Long parsed = AgentGraphInterpreter.parseConfigLong(config.get(CONFIG_KEY_MAX_ITERATIONS));
        if (parsed == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        return parsed.intValue();
    }

    private GraphElement findStart(List<GraphElement> elements) {
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind()) && NODE_TYPE_START.equals(element.getType())) {
                return element;
            }
        }
        throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID, "图中不存在 START 节点");
    }

    private String decodeIfJsonString(String result) {
        if (result == null) {
            return null;
        }
        try {
            return JSON.readValue(result, String.class);
        } catch (Exception e) {
            return result;
        }
    }

    private GraphElement findNode(String id, List<GraphElement> elements) {
        for (GraphElement element : elements) {
            if ("node".equals(element.getKind()) && id.equals(element.getId())) {
                return element;
            }
        }
        throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID, "边引用了不存在的节点: " + id);
    }

    private Long requireConfigId(GraphElement node, String key) {
        Map<String, Object> config = node.getConfig();
        // 接受 Number/数字字符串（graph_json 落库后 Long 为字符串形态，全局 Long→String 约定）
        Long parsed = config == null ? null : AgentGraphInterpreter.parseConfigLong(config.get(key));
        if (parsed == null) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点缺少 " + key + ": " + node.getId());
        }
        return parsed;
    }

    private String requireConfigString(GraphElement node, String key) {
        Map<String, Object> config = node.getConfig();
        if (config == null || !(config.get(key) instanceof String s) || s.isBlank()) {
            throw new AgentGraphInterpreter.GraphExecutionException(ERROR_CATEGORY_TOPOLOGY_INVALID,
                    "节点缺少 " + key + ": " + node.getId());
        }
        return s;
    }

    // ==================== 内部辅助类型 ====================

    /**
     * 活跃执行点（与解释器 ActiveExecutionPoint 同构，public 以便序列化）。
     */
    public static class ActivePoint {
        private String nodeId;
        private String branchPath;

        public ActivePoint() {
        }

        public ActivePoint(String nodeId, String branchPath) {
            this.nodeId = nodeId;
            this.branchPath = branchPath;
        }

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getBranchPath() {
            return branchPath;
        }

        public void setBranchPath(String branchPath) {
            this.branchPath = branchPath;
        }
    }

    /**
     * 可序列化状态。
     */
    public static class DebugState {
        @JsonProperty("variables")
        public Map<String, String> variables;
        @JsonProperty("activePoints")
        public List<ActivePoint> activePoints;
        @JsonProperty("loopCounts")
        public Map<String, Integer> loopCounts;
        @JsonProperty("joinCounts")
        public Map<String, Integer> joinCounts;
        @JsonProperty("traceSeq")
        public long traceSeq;
        @JsonProperty("steps")
        public int steps;
    }

    /**
     * 单步结果。
     */
    public static class StepResult {
        private final AgentGraphInterpreter.NodeExecutionTrace trace;
        private final boolean terminal;
        private final boolean pendingJoin;
        private final String resultText;

        private StepResult(AgentGraphInterpreter.NodeExecutionTrace trace, boolean terminal,
                           boolean pendingJoin, String resultText) {
            this.trace = trace;
            this.terminal = terminal;
            this.pendingJoin = pendingJoin;
            this.resultText = resultText;
        }

        static StepResult terminal(AgentGraphInterpreter.NodeExecutionTrace trace, String resultText) {
            return new StepResult(trace, true, false, resultText);
        }

        static StepResult nonTerminal(AgentGraphInterpreter.NodeExecutionTrace trace) {
            return new StepResult(trace, false, false, null);
        }

        static StepResult pendingJoin(AgentGraphInterpreter.NodeExecutionTrace trace) {
            return new StepResult(trace, false, true, null);
        }

        public AgentGraphInterpreter.NodeExecutionTrace getTrace() {
            return trace;
        }

        public boolean isTerminal() {
            return terminal;
        }

        public boolean isPendingJoin() {
            return pendingJoin;
        }

        public String getResultText() {
            return resultText;
        }
    }
}
