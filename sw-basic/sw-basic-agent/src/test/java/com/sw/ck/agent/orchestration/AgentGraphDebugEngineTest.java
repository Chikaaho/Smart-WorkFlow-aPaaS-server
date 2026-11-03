package com.sw.ck.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentGraphDebugEngine} 测试（M07-F02-04 图单步调试，纯 Java 单测；F1 阶段 1
 * LangChain4j 化翻新：模型经 mock {@link AgentModelRuntimeService} 解析，LC4j ChatModel 桩）。
 * <p>
 * 覆盖：构造初始化、单步推进、CONDITION 分支、LOOP 迭代/LIMIT、FORK/JOIN、序列化往返、终态/peek。
 * </p>
 */
@DisplayName("图单步调试引擎测试（纯 Java）")
class AgentGraphDebugEngineTest {

    private final AgentModelRuntimeService modelRuntimeService = mock(AgentModelRuntimeService.class);
    private final AgentToolCallbackFactory toolCallbackFactory = mock(AgentToolCallbackFactory.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== Helpers: graph builders ====================

    private ProcessGraph linearGraph(Long modelId) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
    }

    private ProcessGraph conditionGraph() {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_cond", "CONDITION", Map.of()),
                node("node_a", "LLM", Map.of("agentModelConfigId", 1L)),
                node("node_b", "LLM", Map.of("agentModelConfigId", 2L)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_cond", Map.of()),
                edge("e_key", "node_cond", "node_a", Map.of("keyword", "urgent")),
                edge("e_default", "node_cond", "node_b", Map.of()),
                edge("e3", "node_a", "node_end", Map.of()),
                edge("e4", "node_b", "node_end", Map.of()));
    }

    private ProcessGraph loopGraph(Long modelId) {
        // START -> LOOP(maxIterations 3) -> LLM -> CONDITION with back-edge to LOOP and exit to END
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 3)),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_cond", "CONDITION", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "exit")),
                edge("e_back", "node_cond", "node_loop", Map.of()));
    }

    private ProcessGraph forkGraph(Long m1, Long m2) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_fork", "FORK", Map.of()),
                node("node_llm_a", "LLM", Map.of("agentModelConfigId", m1)),
                node("node_llm_b", "LLM", Map.of("agentModelConfigId", m2)),
                node("node_join", "JOIN", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_fork", Map.of()),
                edge("e2", "node_fork", "node_llm_a", Map.of()),
                edge("e3", "node_fork", "node_llm_b", Map.of()),
                edge("e4", "node_llm_a", "node_join", Map.of()),
                edge("e5", "node_llm_b", "node_join", Map.of()),
                edge("e6", "node_join", "node_end", Map.of()));
    }

    // ==================== Test 1: construction initializes correctly ====================

    @Test
    @DisplayName("用例1: 构造时初始化 variables 含默认变量 input，activePoints 指向 START，isTerminal=false")
    void construction_shouldInitVariablesAndActivePoints() {
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                linearGraph(1L), "hello", 100, modelRuntimeService, toolCallbackFactory, 100L);
        assertThat(engine.getVariables()).containsEntry("input", "hello");
        assertThat(engine.getActivePoints()).hasSize(1);
        assertThat(engine.getActivePoints().get(0).getNodeId()).isEqualTo("node_start");
        assertThat(engine.getActivePoints().get(0).getBranchPath()).isEqualTo("0");
        assertThat(engine.isTerminal()).isFalse();
        assertThat(engine.peekNextNodeId()).isEqualTo("node_start");
        assertThat(engine.peekNextBranchId()).isEqualTo("0");
        assertThat(engine.getTraceSeq()).isZero();
        assertThat(engine.getSteps()).isZero();
    }

    // ==================== Test 2: single step on START ====================

    @Test
    @DisplayName("用例2: START 单步 → 产生 trace，activePoints 移至下一节点，peek 更新")
    void step_onStart_shouldMoveToNextNode() {
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                linearGraph(1L), "hello", 100, modelRuntimeService, toolCallbackFactory, 100L);
        AgentGraphDebugEngine.StepResult result = engine.step();
        assertThat(result.isTerminal()).isFalse();
        assertThat(result.isPendingJoin()).isFalse();
        assertThat(result.getTrace().getNodeId()).isEqualTo("node_start");
        assertThat(result.getTrace().getNodeType()).isEqualTo("START");
        assertThat(engine.peekNextNodeId()).isEqualTo("node_llm");
        assertThat(engine.getTraceSeq()).isEqualTo(1);
    }

    // ==================== Test 3: step on LLM updates variable ====================

    @Test
    @DisplayName("用例3: LLM 单步 → 写入默认变量 input，variables 更新，步骤计数递增")
    void step_onLlm_shouldUpdateVariableAndProgress() {
        stubModel(1L, new StubChatModel("llm-output"));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                linearGraph(1L), "hello", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START
        AgentGraphDebugEngine.StepResult result = engine.step(); // LLM
        assertThat(result.isTerminal()).isFalse();
        assertThat(result.getTrace().getNodeId()).isEqualTo("node_llm");
        assertThat(engine.getVariables()).containsEntry("input", "llm-output");
        assertThat(engine.peekNextNodeId()).isEqualTo("node_end");
        assertThat(result.getTrace().getVariableSnapshot()).containsEntry("input", "llm-output");
    }

    // ==================== Test 4: step on END returns terminal ====================

    @Test
    @DisplayName("用例4: END 单步 → terminal=true，resultText 为当前变量值，isTerminal 后续状态保持")
    void step_onEnd_shouldReturnTerminal() {
        stubModel(1L, new StubChatModel("final-output"));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                linearGraph(1L), "start", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START
        engine.step(); // LLM
        AgentGraphDebugEngine.StepResult result = engine.step(); // END
        assertThat(result.isTerminal()).isTrue();
        assertThat(result.getResultText()).isEqualTo("final-output");
        // END 不入队后继，activePoints 已空，peek 为 null
        assertThat(engine.peekNextNodeId()).isNull();
        assertThat(engine.peekNextBranchId()).isNull();
    }

    // ==================== Test 5: CONDITION branches by keyword ====================

    @Test
    @DisplayName("用例5: CONDITION 按关键词分支 — 输入含 urgent 走 keyword 边，否则走默认边")
    void step_onCondition_shouldBranchByKeyword() {
        // 准备两个 LLM 模型桩，分别对应两条分支
        stubModel(1L, new StubChatModel("A路"));
        stubModel(2L, new StubChatModel("B路"));
        // urgent 分支
        AgentGraphDebugEngine engineUrgent = new AgentGraphDebugEngine(
                conditionGraph(), "this is urgent", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engineUrgent.step(); // START
        engineUrgent.step(); // CONDITION -> should route to node_a
        assertThat(engineUrgent.peekNextNodeId()).isEqualTo("node_a");

        // 默认分支
        AgentGraphDebugEngine engineDefault = new AgentGraphDebugEngine(
                conditionGraph(), "normal text", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engineDefault.step(); // START
        engineDefault.step(); // CONDITION -> default
        assertThat(engineDefault.peekNextNodeId()).isEqualTo("node_b");
    }

    // ==================== Test 6: loop iteration and LIMIT error ====================

    @Test
    @DisplayName("用例6: LOOP 迭代计数 — 3 次循环正常退出，第 4 次超 maxIterations=3 抛 LOOP_LIMIT")
    void loop_shouldCountIterationsAndThrowOnLimit() {
        stubModel(1L, new SequencedChatModel("continue", "continue", "exit"));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                loopGraph(1L), "start", 100, modelRuntimeService, toolCallbackFactory, 100L);
        // 完整步进直到退出：START -> LOOP(1) -> LLM -> CONDITION -> LOOP(2) -> LLM -> CONDITION -> LOOP(3) -> LLM -> CONDITION -> END
        int steps = 0;
        AgentGraphDebugEngine.StepResult last = null;
        boolean completed = false;
        for (int i = 0; i < 20; i++) {
            last = engine.step();
            steps++;
            if (last.isTerminal()) {
                completed = true;
                break;
            }
        }
        assertThat(completed).isTrue();
        assertThat(last.getResultText()).isEqualTo("exit");

        // 超限场景：maxIterations=1，第二次到达 LOOP 抛错
        ProcessGraph smallLoop = graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 1)),
                node("node_llm", "LLM", Map.of("agentModelConfigId", 1L)),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_loop", Map.of()));
        AgentGraphDebugEngine engineLimit = new AgentGraphDebugEngine(
                smallLoop, "start", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engineLimit.step(); // START
        engineLimit.step(); // LOOP iter 1
        engineLimit.step(); // LLM
        assertThatThrownBy(engineLimit::step) // LOOP iter 2 exceeds 1
                .isInstanceOf(AgentGraphInterpreter.GraphExecutionException.class)
                .hasMessageContaining("循环迭代次数超限");
    }

    // ==================== Test 7: fork fans out with distinct branchIds ====================

    @Test
    @DisplayName("用例7: FORK 扇出 — 产生 2 个分支，branchId 分别为 0-0 / 0-1")
    void fork_shouldFanOutWithDistinctBranchIds() {
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                forkGraph(1L, 2L), "in", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START -> FORK
        AgentGraphDebugEngine.StepResult forkResult = engine.step(); // FORK fan-out
        assertThat(forkResult.isTerminal()).isFalse();
        List<AgentGraphDebugEngine.ActivePoint> points = engine.getActivePoints();
        assertThat(points).hasSize(2);
        assertThat(points.get(0).getBranchPath()).isEqualTo("0-0");
        assertThat(points.get(1).getBranchPath()).isEqualTo("0-1");
        assertThat(points.get(0).getNodeId()).isEqualTo("node_llm_a");
        assertThat(points.get(1).getNodeId()).isEqualTo("node_llm_b");
    }

    // ==================== Test 8: join waits until all branches arrive ====================

    @Test
    @DisplayName("用例8: JOIN 汇合 — 第一次到达 pendingJoin=true，第二次才放行")
    void join_shouldWaitUntilAllBranchesArrive() {
        stubModel(1L, new StubChatModel("out"));
        stubModel(2L, new StubChatModel("out"));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                forkGraph(1L, 2L), "in", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START
        engine.step(); // FORK -> 2 branches
        engine.step(); // branch 0-0: LLM_a -> JOIN
        engine.step(); // branch 0-1: LLM_b -> JOIN
        AgentGraphDebugEngine.StepResult joinFirst = engine.step(); // JOIN first arrival: pendingJoin
        assertThat(joinFirst.isPendingJoin()).isTrue();
        assertThat(joinFirst.isTerminal()).isFalse();
        // 仍有一条分支未汇合，下一 peek 应为 JOIN 第二次到达
        assertThat(engine.peekNextNodeId()).isEqualTo("node_join");
        AgentGraphDebugEngine.StepResult joinSecond = engine.step(); // JOIN second arrival: non-terminal, routes to END
        assertThat(joinSecond.isPendingJoin()).isFalse();
        assertThat(joinSecond.isTerminal()).isFalse();
        assertThat(engine.peekNextNodeId()).isEqualTo("node_end");
        AgentGraphDebugEngine.StepResult end = engine.step(); // END -> terminal
        assertThat(end.isTerminal()).isTrue();
    }

    // ==================== Test 9: serialize/deserialize restores state ====================

    @Test
    @DisplayName("用例9: 序列化往返 — 序列化 stateJson 后反序列化，variables/activePoints/steps 完全一致，继续推进结果一致")
    void serializeDeserialize_shouldRestoreState() throws Exception {
        stubModel(1L, new StubChatModel("mid"));
        ProcessGraph graph = linearGraph(1L);
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                graph, "hello", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START
        engine.step(); // LLM -> input = mid
        String json = engine.serializeState(objectMapper);
        assertThat(json).isNotBlank();
        AgentGraphDebugEngine restored = AgentGraphDebugEngine.deserializeState(
                json, objectMapper, graph, 100, modelRuntimeService, toolCallbackFactory, 100L);
        assertThat(restored.getVariables()).isEqualTo(engine.getVariables());
        assertThat(restored.getActivePoints()).hasSize(engine.getActivePoints().size());
        assertThat(restored.peekNextNodeId()).isEqualTo(engine.peekNextNodeId());
        assertThat(restored.getTraceSeq()).isEqualTo(engine.getTraceSeq());
        assertThat(restored.getSteps()).isEqualTo(engine.getSteps());
        // 继续推进应得到相同终态
        AgentGraphDebugEngine.StepResult r1 = engine.step();
        AgentGraphDebugEngine.StepResult r2 = restored.step();
        assertThat(r1.isTerminal()).isEqualTo(r2.isTerminal());
        assertThat(r1.getResultText()).isEqualTo(r2.getResultText());
    }

    // ==================== Test 10: isTerminal / peekNextNodeId after each step ====================

    @Test
    @DisplayName("用例10: isTerminal / peekNextNodeId 随单步推进精确变化（START→LLM→END）")
    void terminalAndPeek_shouldTrackProgressively() {
        stubModel(1L, new StubChatModel("out"));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                linearGraph(1L), "hi", 100, modelRuntimeService, toolCallbackFactory, 100L);
        assertThat(engine.isTerminal()).isFalse();
        assertThat(engine.peekNextNodeId()).isEqualTo("node_start");
        engine.step(); // START
        assertThat(engine.isTerminal()).isFalse();
        assertThat(engine.peekNextNodeId()).isEqualTo("node_llm");
        engine.step(); // LLM
        assertThat(engine.isTerminal()).isFalse();
        assertThat(engine.peekNextNodeId()).isEqualTo("node_end");
        AgentGraphDebugEngine.StepResult end = engine.step(); // END
        assertThat(end.isTerminal()).isTrue();
        assertThat(engine.peekNextNodeId()).isNull();
        assertThat(engine.isTerminal()).isTrue();
        assertThat(engine.peekNextBranchId()).isNull();
    }

    // ==================== Test 11: TOOL node branch coverage ====================

    @Test
    @DisplayName("用例11: TOOL 节点单步 — 按 toolName 精确匹配句柄，输出写入默认变量")
    void step_onTool_shouldCallCallbackAndWriteVariable() {
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(echoTool("echo_tool", "echo:")));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_tool", "TOOL", Map.of("toolName", "echo_tool")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_tool", Map.of()),
                edge("e2", "node_tool", "node_end", Map.of()));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                graph, "hello", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START
        AgentGraphDebugEngine.StepResult toolResult = engine.step(); // TOOL
        assertThat(toolResult.isTerminal()).isFalse();
        assertThat(engine.getVariables()).containsEntry("input", "echo:hello");
        assertThat(engine.peekNextNodeId()).isEqualTo("node_end");
    }

    // ==================== Test 12: variable named read/write ====================

    @Test
    @DisplayName("用例12: 命名变量 outputVar/inputVar — LLM 写 v1，END 读 v1")
    void step_namedVariable_shouldChainThroughVariables() {
        stubModel(1L, new StubChatModel("named-out"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", 1L, "outputVar", "v1")),
                node("node_end", "END", Map.of("inputVar", "v1")),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                graph, "hello", 100, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START
        engine.step(); // LLM writes v1
        assertThat(engine.getVariables()).containsEntry("v1", "named-out");
        assertThat(engine.getVariables()).containsEntry("input", "hello"); // 默认变量保持
        AgentGraphDebugEngine.StepResult end = engine.step();
        assertThat(end.isTerminal()).isTrue();
        assertThat(end.getResultText()).isEqualTo("named-out");
    }

    // ==================== Test 13: step limit error ====================

    @Test
    @DisplayName("用例13: 步数超限 — maxSteps=2 时第 3 步抛 STEP_LIMIT")
    void step_shouldThrowStepLimitWhenExceeded() {
        stubModel(1L, new StubChatModel("loop"));
        ProcessGraph loopForever = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", 1L)),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_llm", Map.of()));
        AgentGraphDebugEngine engine = new AgentGraphDebugEngine(
                loopForever, "hi", 2, modelRuntimeService, toolCallbackFactory, 100L);
        engine.step(); // START (steps=1)
        engine.step(); // LLM 1 (steps=2)
        assertThatThrownBy(engine::step) // LLM 2 self-loop → steps=3 > maxSteps=2 → STEP_LIMIT
                .isInstanceOf(AgentGraphInterpreter.GraphExecutionException.class)
                .satisfies(ex -> assertThat(((AgentGraphInterpreter.GraphExecutionException) ex).getCategory())
                        .isEqualTo("STEP_LIMIT"));
    }

    // ==================== Helpers: ProcessGraph builders ====================

    private ProcessGraph graphOf(GraphElement... elements) {
        ProcessGraph graph = new ProcessGraph();
        graph.setGraphKey("debug_test_key");
        graph.setName("调试测试图");
        graph.setVersion(1);
        graph.setElements(Arrays.asList(elements));
        graph.setCanvas(Map.of());
        return graph;
    }

    private GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("node").type(type)
                .config(config).style(Map.of())
                .build();
    }

    private GraphElement edge(String id, String source, String target, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("edge").source(source).target(target)
                .config(config).style(Map.of())
                .build();
    }

    /** 打桩运行时服务：acquire(id) 返回携带指定 ChatModel 的 ModelInstance */
    private void stubModel(Long id, ChatModel chatModel) {
        AgentModel model = new AgentModel();
        model.setId(id);
        model.setName("stub-model-" + id);
        model.setEnabled(true);
        AgentProvider provider = new AgentProvider();
        provider.setId(100L);
        provider.setName("stub-provider");
        provider.setApiFormat("OPENAI_CHAT");
        provider.setEnabled(true);
        when(modelRuntimeService.acquire(id))
                .thenReturn(new AgentModelRuntimeService.ModelInstance(model, provider, chatModel));
    }

    /** 回声工具句柄：解码 JSON 入参 → 加前缀 → JSON 编码返回（与工厂执行器契约一致） */
    private static AgentToolCallbackFactory.ToolHandle echoTool(String name, String prefix) {
        return new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name(name).description("回声工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> {
                    try {
                        String arg = new ObjectMapper().readValue(request.arguments(), String.class);
                        return new ObjectMapper().writeValueAsString(prefix + arg);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    // ==================== ChatModel stubs ====================

    static class StubChatModel implements ChatModel {
        private final String reply;
        StubChatModel(String reply) { this.reply = reply; }
        @Override public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    static class SequencedChatModel implements ChatModel {
        private final String[] replies;
        private int index = 0;
        SequencedChatModel(String... replies) { this.replies = replies; }
        @Override public ChatResponse chat(ChatRequest request) {
            String reply = replies[Math.min(index, replies.length - 1)];
            index++;
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }
}
