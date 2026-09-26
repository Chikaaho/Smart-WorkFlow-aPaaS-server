package com.sw.ck.agent.orchestration;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6A 补证 G2 · Agent 的 Spring AI 调用域兼容行为锚点。
 *
 * <p>
 * 本类不新增生产代码，只沿既有生产路径做三件可断言的事：
 * ① 用真实 Spring AI 类型（{@link SystemMessage}/{@link UserMessage}/{@link Prompt}）经
 * {@link AgentGraphFactory} 编排图实际装配 Prompt，断言指令序列；
 * ② 实际发起一次模型调用（stub {@link ChatModel} 实现 {@code call(Prompt)}），断言生产侧
 * 响应文本提取链 {@code ChatResponse.getResult().getOutput().getText()} 的结果；
 * ③ 用 Spring AI 的 {@link EmptyUsage} 断言「供应商未返回 usage」时生产侧保持
 * <b>未知</b> 语义（null，而非伪零）。
 * </p>
 *
 * <p>
 * 全部离线、内存内执行，不启动 Spring 上下文、不访问网络或真实模型服务。
 * </p>
 */
@DisplayName("Phase 6A G2：Agent Spring AI 装配/调用兼容行为")
class AgentSpringAiCallDomainCompatTest {

    private static final String SYSTEM_TEXT = "你是 Phase 6A G2 兼容性验证助手";
    private static final String USER_TEXT = "请回复哨兵";
    private static final String REPLY_TEXT = "PHASE6A-SPRINGAI-REPLY-OK";

    @Test
    @DisplayName("真实 Spring AI 类型经生产编排图装配 Prompt：系统消息 + 历史 + 本轮 UserMessage 顺序正确")
    void springAiPromptIsAssembledThroughProductionGraph() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        CapturingChatModel model = new CapturingChatModel(REPLY_TEXT);

        AgentGraphFactory.bindChatModel(model);
        AgentGraphFactory.bindHistoryMessages(List.of(new SystemMessage(SYSTEM_TEXT)));
        try {
            Optional<AgentState> result = graph.invoke(Map.of("input", USER_TEXT, "chatModel", model));

            assertThat(result).isPresent();
            Prompt prompt = model.capturedPrompt;
            assertThat(prompt).as("生产路径必须实际构造 Prompt").isNotNull();
            List<Message> instructions = prompt.getInstructions();
            assertThat(instructions).as("系统消息 + 本轮 UserMessage").hasSize(2);
            assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
            assertThat(instructions.get(0).getText()).isEqualTo(SYSTEM_TEXT);
            assertThat(instructions.get(1)).isInstanceOf(UserMessage.class);
            assertThat(instructions.get(1).getText()).isEqualTo(USER_TEXT);

            System.out.println("[P6A-G2] agent springai-prompt instructions=" + instructions.size()
                    + " systemText=" + instructions.get(0).getText()
                    + " userText=" + instructions.get(1).getText());
        } finally {
            AgentGraphFactory.clearHistoryMessages();
            AgentGraphFactory.clearChatModel();
        }
    }

    @Test
    @DisplayName("生产响应文本提取链实际返回模型回复：ChatResponse → Generation → AssistantMessage.getText()")
    void springAiResponseTextIsExtractedThroughProductionPath() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        CapturingChatModel model = new CapturingChatModel(REPLY_TEXT);

        AgentGraphFactory.bindChatModel(model);
        try {
            Optional<AgentState> result = graph.invoke(Map.of("input", USER_TEXT, "chatModel", model));

            assertThat(result).isPresent();
            assertThat(result.get().value("output"))
                    .as("提取链结果必须等于 stub 模型回复")
                    .hasValue(REPLY_TEXT);

            System.out.println("[P6A-G2] agent springai-response output="
                    + result.get().value("output").orElse(""));
        } finally {
            AgentGraphFactory.clearChatModel();
        }
    }

    @Test
    @DisplayName("供应商未返回 usage 时保持未知语义：EmptyUsage → 生产侧 token 快照为 null 而非 0")
    void emptyUsageKeepsUnknownSemantics() throws Exception {
        CompiledGraph<AgentState> graph = new AgentGraphFactory().buildGraph();
        ChatModel model = new EmptyUsageChatModel(REPLY_TEXT);

        AgentGraphFactory.bindChatModel(model);
        try {
            graph.invoke(Map.of("input", USER_TEXT, "chatModel", model));
            AgentGraphFactory.UsageSnapshot usage = AgentGraphFactory.getTokenUsage();

            assertThat(usage).as("调用后必须留下 usage 快照").isNotNull();
            assertThat(usage.inputTokens()).as("未知不等于 0").isNull();
            assertThat(usage.outputTokens()).as("未知不等于 0").isNull();

            System.out.println("[P6A-G2] agent springai-usage input=" + usage.inputTokens()
                    + " output=" + usage.outputTokens());
        } finally {
            AgentGraphFactory.clearTokenUsage();
            AgentGraphFactory.clearChatModel();
        }
    }

    // ==================== 测试用 ChatModel 桩 ====================

    /** 记录实际收到的 Prompt，并按固定文本回复。 */
    static class CapturingChatModel implements ChatModel {

        private final String reply;
        Prompt capturedPrompt;

        CapturingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.capturedPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    /** 回复固定文本，且 metadata 携带 Spring AI 自身的 {@link EmptyUsage}（模拟供应商未返回用量）。 */
    static class EmptyUsageChatModel implements ChatModel {

        private final String reply;

        EmptyUsageChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .usage(new EmptyUsage())
                    .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))), metadata);
        }
    }
}
