package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F02 图执行路径 Token 聚合行为测试（M07-F04-02 验收标准 1/2；F1 阶段 1 LangChain4j 化翻新）。
 * <p>
 * 本测试类专注于验证 AgentGraphInterpreter 在多节点、LOOP、FORK/JOIN 场景下的 Token 累积行为。
 * 使用 TokenChatModel 桩注入 LC4j {@link TokenUsage} metadata，验证：
 * <ul>
 *   <li>标准1：F02 图执行路径 Token 读取与持久化</li>
 *   <li>标准2：多节点/LOOP/FORK/JOIN Token 聚合</li>
 * </ul>
 */
@DisplayName("F02 图执行路径 Token 聚合行为测试（M07-F04-02）")
class AgentGraphInterpreterTokenTest {

    private AgentModelRuntimeService modelRuntimeService;
    private AgentToolCallbackFactory toolCallbackFactory;

    @BeforeEach
    void setUp() throws Exception {
        modelRuntimeService = mock(AgentModelRuntimeService.class);
        toolCallbackFactory = mock(AgentToolCallbackFactory.class);
    }

    // ==================== TokenChatModel 桩 ====================

    /**
     * 注入 TokenUsage metadata 的 ChatModel 桩。
     * 每次 chat() 返回固定的 reply，并在 ChatResponseMetadata 中注入 usage。
     */
    static class TokenChatModel implements ChatModel {
        private final String reply;
        private final long promptTokens;
        private final long completionTokens;
        private int callCount = 0;

        TokenChatModel(String reply, long promptTokens, long completionTokens) {
            this.reply = reply;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            callCount++;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(reply))
                    .metadata(ChatResponseMetadata.builder()
                            // LC4j TokenUsage（Integer 计数，可能为 null；此处固定注入）
                            .tokenUsage(new TokenUsage((int) promptTokens, (int) completionTokens))
                            .build())
                    .build();
        }

        public int getCallCount() {
            return callCount;
        }
    }

    /**
     * 多次调用返回不同回复和 token 的 ChatModel 桩。
     */
    static class SequencedTokenChatModel implements ChatModel {
        private final List<String> replies;
        private final List<long[]> tokenSequences;
        private int callIndex = 0;

        SequencedTokenChatModel(List<String> replies, List<long[]> tokenSequences) {
            this.replies = replies;
            this.tokenSequences = tokenSequences;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            String reply = replies.get(Math.min(callIndex, replies.size() - 1));
            long[] tokens = tokenSequences.get(Math.min(callIndex, tokenSequences.size() - 1));
            callIndex++;

            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(reply))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage((int) tokens[0], (int) tokens[1]))
                            .build())
                    .build();
        }

        public int getCallCount() {
            return callIndex;
        }
    }

    // ==================== 辅助方法 ====================

    private AgentGraphInterpreter interpreter(int maxSteps) {
        return new AgentGraphInterpreter(modelRuntimeService, toolCallbackFactory, null, maxSteps);
    }

    private void stubChatModel(Long id, ChatModel chatModel) {
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
        // D022-05 轮换入口：委托回 acquire 打桩（每次调用仍经 acquire 解析，计数语义不变）
        when(modelRuntimeService.chatWithRotation(eq(id), anyList(), any())).thenAnswer(inv ->
                modelRuntimeService.acquire(id).chatModel().chat(
                        dev.langchain4j.model.chat.request.ChatRequest.builder()
                                .messages((java.util.List<dev.langchain4j.data.message.ChatMessage>) inv.getArgument(1))
                                .build()));
    }

    // ==================== 标准1：F02 Token 读取 ====================

    @Test
    @DisplayName("标准1-F02：单 LLM 节点调用后 nodeUsage.totalTokens 正确记录")
    void singleLlmNode_shouldRecordTokenUsage() throws Exception {
        // 输入：单 LLM 节点，promptTokens=10, completionTokens=20
        // 预期：nodeUsage.totalTokens = 30
        // 实际：通过 TokenChatModel 桩注入 usage，验证 AgentGraphInterpreter 返回的 usage

        TokenChatModel chatModel = new TokenChatModel("reply", 10, 20);
        stubChatModel(1L, chatModel);

        AgentGraphInterpreter interp = interpreter(100);

        // 构建单 LLM 节点图
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        elements.add(nodeWithConfig("llm", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(node("end", "END"));
        elements.add(edge("e1", "start", "llm"));
        elements.add(edge("e2", "llm", "end"));

        ProcessGraph graph = new ProcessGraph("single-llm", "test", 1, elements, Map.of());
        interp.run(graph, "input");

        // 验证 token 聚合：通过 traces 获取节点级 token
        List<AgentGraphInterpreter.NodeExecutionTrace> traces = interp.getTraces();
        assertThat(traces).hasSize(3); // START + LLM + END
        // LLM 节点（index=1）应该有 token 数据
        AgentGraphInterpreter.NodeExecutionTrace llmTrace = traces.get(1);
        assertThat(llmTrace.getInputTokens()).isEqualTo(10L);
        assertThat(llmTrace.getOutputTokens()).isEqualTo(20L);
        assertThat(chatModel.getCallCount()).isEqualTo(1);
    }

    // ==================== 标准2：多节点 Token 聚合 ====================

    @Test
    @DisplayName("标准2：顺序 LLM 链 Token 累积 - 两跳 LLM token 求和")
    void twoLlmChain_shouldAccumulateTokens() throws Exception {
        // 输入：顺序 LLM 链，LLM1(prompt=10, completion=20) + LLM2(prompt=30, completion=40)
        // 预期：totalTokens = 10+20+30+40 = 100

        SequencedTokenChatModel chatModel = new SequencedTokenChatModel(
                List.of("reply1", "reply2"),
                List.of(new long[]{10, 20}, new long[]{30, 40}));
        stubChatModel(1L, chatModel);

        AgentGraphInterpreter interp = interpreter(100);

        // 构建顺序两 LLM 节点图
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        elements.add(nodeWithConfig("llm1", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(nodeWithConfig("llm2", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(node("end", "END"));
        elements.add(edge("e1", "start", "llm1"));
        elements.add(edge("e2", "llm1", "llm2"));
        elements.add(edge("e3", "llm2", "end"));

        ProcessGraph graph = new ProcessGraph("two-llm", "test", 1, elements, Map.of());
        interp.run(graph, "input");

        // 验证两个 LLM 节点的 token 数据
        List<AgentGraphInterpreter.NodeExecutionTrace> traces = interp.getTraces();
        assertThat(traces).hasSize(4); // START + LLM1 + LLM2 + END
        // LLM1 节点（index=1）
        AgentGraphInterpreter.NodeExecutionTrace llm1Trace = traces.get(1);
        assertThat(llm1Trace.getInputTokens()).isEqualTo(10L);
        assertThat(llm1Trace.getOutputTokens()).isEqualTo(20L);
        // LLM2 节点（index=2）
        AgentGraphInterpreter.NodeExecutionTrace llm2Trace = traces.get(2);
        assertThat(llm2Trace.getInputTokens()).isEqualTo(30L);
        assertThat(llm2Trace.getOutputTokens()).isEqualTo(40L);
        assertThat(chatModel.getCallCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("标准2：三节点 LLM 链 Token 累积")
    void threeLlmChain_shouldAccumulateTokens() throws Exception {
        // 输入：三跳 LLM，每跳 prompt=5, completion=5
        // 预期：totalTokens = 3 × 10 = 30

        SequencedTokenChatModel chatModel = new SequencedTokenChatModel(
                List.of("r1", "r2", "r3"),
                List.of(new long[]{5, 5}, new long[]{5, 5}, new long[]{5, 5}));
        stubChatModel(1L, chatModel);

        AgentGraphInterpreter interp = interpreter(100);

        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        elements.add(nodeWithConfig("llm1", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(nodeWithConfig("llm2", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(nodeWithConfig("llm3", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(node("end", "END"));
        elements.add(edge("e1", "start", "llm1"));
        elements.add(edge("e2", "llm1", "llm2"));
        elements.add(edge("e3", "llm2", "llm3"));
        elements.add(edge("e4", "llm3", "end"));

        ProcessGraph graph = new ProcessGraph("three-llm", "test", 1, elements, Map.of());
        interp.run(graph, "input");

        // 验证三个 LLM 节点的 token 数据
        List<AgentGraphInterpreter.NodeExecutionTrace> traces = interp.getTraces();
        assertThat(traces).hasSize(5); // START + LLM1 + LLM2 + LLM3 + END
        // 每个 LLM 节点都应该有 token 数据
        for (int i = 1; i <= 3; i++) {
            AgentGraphInterpreter.NodeExecutionTrace llmTrace = traces.get(i);
            assertThat(llmTrace.getInputTokens()).isEqualTo(5L);
            assertThat(llmTrace.getOutputTokens()).isEqualTo(5L);
        }
    }

    // ==================== 标准2：LOOP 迭代 Token 累积 ====================

    @Test
    @DisplayName("标准2：LOOP 3 轮迭代 Token 累积 - 每轮 token 求和")
    void loopThreeIterations_shouldAccumulateTokens() throws Exception {
        // 输入：LOOP(maxIterations=3) + LLM(prompt=5, completion=5)
        // 条件分支：每次返回"continue"回边，第3轮后返回空触发退出
        // 预期：3 轮 × 10 = 30

        // 前2轮返回"continue"，第3轮返回"done"（触发条件分支退出）
        SequencedTokenChatModel chatModel = new SequencedTokenChatModel(
                List.of("continue", "continue", "done"),
                List.of(new long[]{5, 5}, new long[]{5, 5}, new long[]{5, 5}));
        stubChatModel(1L, chatModel);

        AgentGraphInterpreter interp = interpreter(100);

        // 构建 LOOP 图：start → loop → llm → cond → end(默认) / cond → loop(keyword="continue")
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        elements.add(nodeWithConfig("loop", "LOOP", Map.of("maxIterations", 3)));
        elements.add(nodeWithConfig("llm", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(node("cond", "CONDITION"));
        elements.add(node("end", "END"));
        elements.add(edge("e1", "start", "loop"));
        elements.add(edge("e2", "loop", "llm"));
        elements.add(edge("e3", "llm", "cond"));
        elements.add(edge("e4", "cond", "end"));
        elements.add(edgeWithKeyword("e5", "cond", "loop", "continue"));

        ProcessGraph graph = new ProcessGraph("loop-graph", "test", 1, elements, Map.of());
        interp.run(graph, "input");

        // 验证 LOOP 迭代的 token 数据
        List<AgentGraphInterpreter.NodeExecutionTrace> traces = interp.getTraces();
        // 应该有 3 次 LLM 调用（每轮一次）
        assertThat(chatModel.getCallCount()).isEqualTo(3);
        // 统计所有 LLM 节点的 token 总和
        long totalInput = 0;
        long totalOutput = 0;
        for (AgentGraphInterpreter.NodeExecutionTrace trace : traces) {
            if ("LLM".equals(trace.getNodeType()) && trace.getInputTokens() != null) {
                totalInput += trace.getInputTokens();
                totalOutput += trace.getOutputTokens();
            }
        }
        assertThat(totalInput).isEqualTo(15L); // 3 × 5
        assertThat(totalOutput).isEqualTo(15L); // 3 × 5
    }

    // ==================== 标准2：FORK/JOIN Token 聚合 ====================

    @Test
    @DisplayName("标准2-FORK/JOIN：两分支各含 LLM 节点 - 各分支 Token 独立记录 + 汇合后累加")
    void forkJoin_twoBranchesWithLlm_shouldRecordTokensPerBranch() throws Exception {
        // 输入：FORK → [LLM1(prompt=10,completion=20)] / [LLM2(prompt=30,completion=40)] → JOIN → END
        // 预期：LLM1 input=10,output=20; LLM2 input=30,output=40
        // 执行汇总：总 input=40, 总 output=60

        // FORK 两个分支各自执行一个 LLM 节点
        SequencedTokenChatModel chatModel = new SequencedTokenChatModel(
                List.of("branch1-reply", "branch2-reply"),
                List.of(new long[]{10, 20}, new long[]{30, 40}));
        stubChatModel(1L, chatModel);

        AgentGraphInterpreter interp = interpreter(100);

        // 构建 FORK/JOIN 图：START → FORK → [LLM1, LLM2] → JOIN → END
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        elements.add(node("fork", "FORK"));
        elements.add(nodeWithConfig("llm1", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(nodeWithConfig("llm2", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(node("join", "JOIN"));
        elements.add(node("end", "END"));
        elements.add(edge("e1", "start", "fork"));
        elements.add(edge("e2", "fork", "llm1"));
        elements.add(edge("e3", "fork", "llm2"));
        elements.add(edge("e4", "llm1", "join"));
        elements.add(edge("e5", "llm2", "join"));
        elements.add(edge("e6", "join", "end"));

        ProcessGraph graph = new ProcessGraph("fork-join", "test", 1, elements, Map.of());
        interp.run(graph, "input");

        // 验证两个 LLM 节点的 token 数据
        List<AgentGraphInterpreter.NodeExecutionTrace> traces = interp.getTraces();
        // 统计所有 LLM 节点的 token
        long totalInput = 0;
        long totalOutput = 0;
        int llmCount = 0;
        for (AgentGraphInterpreter.NodeExecutionTrace trace : traces) {
            if ("LLM".equals(trace.getNodeType()) && trace.getInputTokens() != null) {
                totalInput += trace.getInputTokens();
                totalOutput += trace.getOutputTokens();
                llmCount++;
            }
        }
        assertThat(llmCount).isEqualTo(2); // 两个 LLM 节点
        assertThat(totalInput).isEqualTo(40L); // 10 + 30
        assertThat(totalOutput).isEqualTo(60L); // 20 + 40
    }

    // ==================== 标准2：同节点重复执行 Token ====================

    @Test
    @DisplayName("标准2-同节点重复：LOOP 中同一 LLM 节点多次执行 Token 独立记录")
    void sameNodeRepeatedExecution_shouldRecordTokensPerExecution() throws Exception {
        // 输入：LOOP(maxIterations=2) + LLM(prompt=10,completion=20)
        // 预期：两次执行各记录 input=10,output=20（不去重）
        // 执行汇总：总 input=20, 总 output=40

        SequencedTokenChatModel chatModel = new SequencedTokenChatModel(
                List.of("continue", "done"),
                List.of(new long[]{10, 20}, new long[]{10, 20}));
        stubChatModel(1L, chatModel);

        AgentGraphInterpreter interp = interpreter(100);

        // 构建 LOOP 图
        List<GraphElement> elements = new ArrayList<>();
        elements.add(node("start", "START"));
        elements.add(nodeWithConfig("loop", "LOOP", Map.of("maxIterations", 2)));
        elements.add(nodeWithConfig("llm", "LLM", Map.of("agentModelConfigId", 1L)));
        elements.add(node("cond", "CONDITION"));
        elements.add(node("end", "END"));
        elements.add(edge("e1", "start", "loop"));
        elements.add(edge("e2", "loop", "llm"));
        elements.add(edge("e3", "llm", "cond"));
        elements.add(edge("e4", "cond", "end"));
        elements.add(edgeWithKeyword("e5", "cond", "loop", "continue"));

        ProcessGraph graph = new ProcessGraph("same-node-loop", "test", 1, elements, Map.of());
        interp.run(graph, "input");

        // 验证同一 LLM 节点两次执行的 token 数据（不去重）
        List<AgentGraphInterpreter.NodeExecutionTrace> traces = interp.getTraces();
        long totalInput = 0;
        long totalOutput = 0;
        int llmCount = 0;
        for (AgentGraphInterpreter.NodeExecutionTrace trace : traces) {
            if ("LLM".equals(trace.getNodeType()) && trace.getInputTokens() != null) {
                totalInput += trace.getInputTokens();
                totalOutput += trace.getOutputTokens();
                llmCount++;
            }
        }
        assertThat(llmCount).isEqualTo(2); // 同一节点执行两次
        assertThat(totalInput).isEqualTo(20L); // 2 × 10
        assertThat(totalOutput).isEqualTo(40L); // 2 × 20
    }

    // ==================== 图元素构建辅助 ====================

    private GraphElement node(String id, String type) {
        return GraphElement.builder()
                .id(id).kind("node").type(type)
                .config(Map.of()).style(Map.of())
                .build();
    }

    private GraphElement nodeWithConfig(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("node").type(type)
                .config(config).style(Map.of())
                .build();
    }

    private GraphElement edge(String id, String source, String target) {
        return GraphElement.builder()
                .id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of())
                .build();
    }

    private GraphElement edgeWithKeyword(String id, String source, String target, String keyword) {
        return GraphElement.builder()
                .id(id).kind("edge").source(source).target(target)
                .config(Map.of("keyword", keyword)).style(Map.of())
                .build();
    }

    private ProcessGraph graphOf(GraphElement... elements) {
        ProcessGraph graph = new ProcessGraph();
        graph.setGraphKey("test_key");
        graph.setName("测试图");
        graph.setVersion(1);
        graph.setElements(Arrays.asList(elements));
        graph.setCanvas(Map.of());
        return graph;
    }
}
