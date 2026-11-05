package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentChatModelAssemblerStreamingTest {
    private final AgentChatModelAssembler assembler = new AgentChatModelAssembler();

    @Test
    void assemblesAllSupportedStreamingFormats() {
        AgentModel model = model();
        assertThat(assembler.assembleStreaming(provider("OPENAI_CHAT"), model, "test-key"))
                .isInstanceOf(OpenAiStreamingChatModel.class);
        assertThat(assembler.assembleStreaming(provider("OPENAI_RESPONSES"), model, "test-key"))
                .isInstanceOf(OpenAiResponsesStreamingChatModel.class);
        assertThat(assembler.assembleStreaming(provider("ANTHROPIC"), model, "test-key"))
                .isInstanceOf(AnthropicStreamingChatModel.class);
    }

    @Test
    void rejectsUnknownStreamingFormatWithoutLeakingKey() {
        assertThatThrownBy(() -> assembler.assembleStreaming(provider("OTHER"), model(), "secret-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-value");
    }

    private AgentProvider provider(String format) {
        AgentProvider provider = new AgentProvider();
        provider.setApiFormat(format);
        provider.setBaseUrl("https://example.invalid");
        return provider;
    }

    private AgentModel model() {
        AgentModel model = new AgentModel();
        model.setModelName("test-model");
        model.setTimeoutSeconds(3);
        model.setRetryCount(0);
        model.setMaxTokens(64);
        return model;
    }
}
