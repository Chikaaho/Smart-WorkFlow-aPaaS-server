package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.time.Duration;

/**
 * Embedding 模型装配（F1 阶段 3）：OpenAI Chat / OpenAI Responses 两种供应商格式
 * 共用 OpenAI 兼容的 {@code /embeddings} 接口；Anthropic Messages 格式没有
 * Embeddings 接口，显式拒绝并给出可读原因。
 * <p>
 * 明文 Key 仅用于构造，不落字段/日志/异常文本；单例复用与失效由
 * {@link AgentModelRuntimeService} 负责。
 * </p>
 */
public class AgentEmbeddingModelAssembler {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public EmbeddingModel assemble(AgentProvider provider, AgentModel model, String plainApiKey) {
        String format = provider.getApiFormat();
        if (AgentProtocolFormatEnum.ANTHROPIC.name().equals(format)) {
            throw new IllegalArgumentException("Anthropic Messages 格式不提供 Embeddings 接口，无法用于知识库向量化");
        }
        if (!AgentProtocolFormatEnum.OPENAI_CHAT.name().equals(format) && !AgentProtocolFormatEnum.OPENAI_RESPONSES.name().equals(format)) {
            throw new IllegalArgumentException("不支持的 Embedding 供应商格式: " + format);
        }
        return OpenAiEmbeddingModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(plainApiKey == null ? "" : plainApiKey)
                .modelName(model.getModelName())
                .timeout(Duration.ofSeconds(model.getTimeoutSeconds() == null
                        ? DEFAULT_TIMEOUT_SECONDS
                        : Math.max(1, model.getTimeoutSeconds())))
                .maxRetries(model.getRetryCount() == null ? 0 : Math.max(0, model.getRetryCount()))
                .build();
    }
}
