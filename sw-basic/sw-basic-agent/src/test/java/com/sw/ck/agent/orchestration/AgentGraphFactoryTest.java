package com.sw.ck.agent.orchestration;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link AgentGraphFactory} 测试（M07 Step2 §13.2，纯 JUnit，不启动 Spring 上下文；
 * F1 阶段 1 LangChain4j 化翻新：ChatModel/历史消息/工具均为 LC4j 类型）。
 * <p>
 * 用例 3 断言依据：langgraph4j 1.5.14 实测，节点动作抛异常时 {@code invoke()} 以
 * {@code java.util.concurrent.CompletionException}（CompletableFuture.join 包装链：
 * CompletionException → ExecutionException → 原始异常）原样抛出，不会返回空
 * {@code Optional}——断言只锁定根因类型，对包装层宽松（兼容未来版本包装变化）。
 * </p>
 */
@DisplayName("最小编排图构造/执行测试")
class AgentGraphFactoryTest {

    @Test
    @DisplayName("用例1: buildGraph() 不抛异常，返回非 null CompiledGraph")
    void buildGraph_shouldCompile() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();

        assertThat(graph).isNotNull();
    }

    @Test
    @DisplayName("用例2: 绑定 stub ChatModel 后 invoke，output 与 stub 回复一致（START→callModel→END 全链路）")
    void invoke_withStubChatModel_shouldReturnOutput() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        ChatModel stub = new StubChatModel("你好，stub 回复");

        AgentGraphFactory.bindChatModel(stub);
        try {
            Optional<AgentState> result = graph.invoke(Map.of("input", "hello", "chatModel", stub));

            assertThat(result).isPresent();
            assertThat(result.get().value("output")).hasValue("你好，stub 回复");
        } finally {
            AgentGraphFactory.clearChatModel();
        }
    }

    @Test
    @DisplayName("用例3: stub ChatModel 抛异常时 invoke() 实际抛出异常，根因为节点异常")
    void invoke_withThrowingChatModel_shouldThrow() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        ChatModel throwing = new ThrowingChatModel();

        AgentGraphFactory.bindChatModel(throwing);
        try {
            Throwable thrown = catchThrowable(() ->
                    graph.invoke(Map.of("input", "hi", "chatModel", throwing)));

            // 实测行为：invoke() 抛异常（CompletionException 包装链），不会返回空 Optional
            assertThat(thrown).isNotNull();
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertThat(root)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("node exploded");
        } finally {
            AgentGraphFactory.clearChatModel();
        }
    }

    // ==================== 用例 4-6：M07 Step4 F04 历史消息注入 ====================

    @Test
    @DisplayName("用例4: 绑定历史消息后 invoke，callModel 收到的 ChatRequest 含历史 + 新 UserMessage（messages size = 历史数 + 1）")
    void invoke_withHistoryBound_shouldIncludeHistoryInPrompt() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        CapturingChatModel stub = new CapturingChatModel("多轮回复");
        List<ChatMessage> history = List.of(
                UserMessage.from("第一轮问题"),
                AiMessage.from("第一轮回答"));

        AgentGraphFactory.bindChatModel(stub);
        AgentGraphFactory.bindHistoryMessages(history);
        try {
            Optional<AgentState> result = graph.invoke(Map.of("input", "第二轮问题", "chatModel", stub));

            assertThat(result).isPresent();
            assertThat(stub.capturedRequest).isNotNull();
            List<ChatMessage> messages = stub.capturedRequest.messages();
            assertThat(messages).hasSize(3);
            // 历史在前、新 UserMessage 在尾部（顺序 = DB msg_order 升序加载顺序）
            assertThat(textOf(messages.get(0))).isEqualTo("第一轮问题");
            assertThat(textOf(messages.get(1))).isEqualTo("第一轮回答");
            assertThat(messages.get(2)).isInstanceOf(UserMessage.class);
            assertThat(textOf(messages.get(2))).isEqualTo("第二轮问题");
            // 无工具 → 请求不携带 toolSpecifications
            assertThat(stub.capturedRequest.toolSpecifications()).isNullOrEmpty();
        } finally {
            AgentGraphFactory.clearChatModel();
            AgentGraphFactory.clearHistoryMessages();
        }
    }

    @Test
    @DisplayName("用例5: 未绑定历史（首轮）时行为与 Step2/3 一致——messages 仅含本轮 UserMessage，不报 NPE")
    void invoke_withoutHistoryBound_shouldFallBackToSingleMessage() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        CapturingChatModel stub = new CapturingChatModel("首轮回复");

        AgentGraphFactory.bindChatModel(stub);
        try {
            Optional<AgentState> result = graph.invoke(Map.of("input", "hello", "chatModel", stub));

            assertThat(result).isPresent();
            List<ChatMessage> messages = stub.capturedRequest.messages();
            assertThat(messages).hasSize(1);
            assertThat(textOf(messages.get(0))).isEqualTo("hello");
            assertThat(stub.capturedRequest.toolSpecifications()).isNullOrEmpty();
        } finally {
            AgentGraphFactory.clearChatModel();
        }
    }

    @Test
    @DisplayName("用例6: 历史 + 工具同时绑定时，ChatRequest 携带 toolSpecifications 且 messages 含历史")
    void invoke_withHistoryAndTools_shouldCarryOptionsAndHistory() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        CapturingChatModel stub = new CapturingChatModel("工具回复");
        List<ChatMessage> history = List.of(UserMessage.from("旧问题"), AiMessage.from("旧回答"));
        AgentToolCallbackFactory.ToolHandle tool = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("echo_tool").description("回声工具").build(),
                (request, memoryId) -> "\"echo\"");

        AgentGraphFactory.bindChatModel(stub);
        AgentGraphFactory.bindHistoryMessages(history);
        AgentGraphFactory.bindTools(List.of(tool));
        try {
            Optional<AgentState> result = graph.invoke(Map.of("input", "新问题", "chatModel", stub));

            assertThat(result).isPresent();
            assertThat(stub.capturedRequest.messages()).hasSize(3);
            assertThat(stub.capturedRequest.toolSpecifications())
                    .extracting(ToolSpecification::name)
                    .contains("echo_tool");
        } finally {
            AgentGraphFactory.clearChatModel();
            AgentGraphFactory.clearHistoryMessages();
            AgentGraphFactory.clearTools();
        }
    }

    // ==================== 消息文本辅助 ====================

    private static String textOf(ChatMessage message) {
        if (message instanceof SystemMessage sys) {
            return sys.text();
        }
        if (message instanceof UserMessage user) {
            return user.singleText();
        }
        if (message instanceof AiMessage ai) {
            return ai.text();
        }
        throw new IllegalArgumentException("非文本消息: " + message.getClass());
    }

    // ==================== 测试 ChatModel 桩 ====================

    /** 记录收到的 ChatRequest 的 ChatModel 桩 */
    static class CapturingChatModel implements ChatModel {
        private final String reply;
        ChatRequest capturedRequest;

        CapturingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            this.capturedRequest = request;
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    /** 固定回复的 ChatModel 桩 */
    static class StubChatModel implements ChatModel {

        private final String reply;

        StubChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    /** 调用即抛异常的 ChatModel 桩 */
    static class ThrowingChatModel implements ChatModel {

        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new IllegalStateException("node exploded");
        }
    }
}
