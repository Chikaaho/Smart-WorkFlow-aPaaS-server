package com.sw.ck.agent.orchestration;

import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

/**
 * 三格式模型装配模板（F1 阶段 1，D022-01/02）：按供应商 {@code apiFormat} 分支构造
 * LangChain4j {@link ChatModel}。
 * <ul>
 *   <li>{@code OPENAI_CHAT} → {@link OpenAiChatModel}（Chat Completions，baseUrl 可换任意兼容网关）；</li>
 *   <li>{@code OPENAI_RESPONSES} → {@link OpenAiResponsesChatModel}（Responses，experimental）；</li>
 *   <li>{@code ANTHROPIC} → {@link AnthropicChatModel}（Messages）。</li>
 * </ul>
 * <p>
 * 无状态装配（模板装配，D022-04）：单例复用与失效由 {@code AgentModelRuntimeService} 负责，
 * 本类只做"配置 → 实例"的纯构造。非法 apiFormat 显式抛 {@link IllegalArgumentException}。
 * </p>
 * <p>
 * <b>明文 API Key 生命周期</b>：仅用于本次构造，不落任何字段、不打日志、不进异常消息。
 * timeoutSeconds 经各 builder 的 {@code timeout(Duration)} 生效；retryCount 经
 * {@code maxRetries}（attempts = retryCount + 1）生效（OpenAi Responses 模板暂无
 * 该入口，重试由调用点错误分类兜底）。
 * </p>
 */
public class AgentChatModelAssembler {

    /** apiFormat 白名单（固定三种，D022-02） */
    public static final Set<String> SUPPORTED_FORMATS = Set.of("OPENAI_CHAT", "OPENAI_RESPONSES", "ANTHROPIC");

    /** timeoutSeconds 未配置时的默认值（秒），与 V0.2.2 表结构 DEFAULT 30 一致 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Anthropic Messages API 必填 max_tokens；模型未配置时的兜底值 */
    private static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 1024;

    public ChatModel assemble(AgentProvider provider, AgentModel model, String plainApiKey) {
        String format = provider.getApiFormat();
        if (format == null || !SUPPORTED_FORMATS.contains(format)) {
            throw new IllegalArgumentException("不支持的 API 格式，无法构造模型客户端: " + format);
        }
        return switch (format) {
            case "OPENAI_CHAT" -> assembleOpenAiChat(provider, model, plainApiKey);
            case "OPENAI_RESPONSES" -> assembleOpenAiResponses(provider, model, plainApiKey);
            case "ANTHROPIC" -> assembleAnthropic(provider, model, plainApiKey);
            default -> throw new IllegalStateException("不应到达: " + format);
        };
    }

    private ChatModel assembleOpenAiChat(AgentProvider provider, AgentModel model, String plainApiKey) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(orEmpty(plainApiKey))
                .modelName(model.getModelName())
                .timeout(timeout(model))
                .maxRetries(attempts(model));
        if (model.getTemperature() != null) {
            builder.temperature(toDouble(model.getTemperature()));
        }
        if (model.getTopP() != null) {
            builder.topP(toDouble(model.getTopP()));
        }
        if (model.getMaxTokens() != null) {
            // 用统一 max_tokens 槽位而非 maxCompletion_tokens：与 ChatRequest.maxOutputTokens
            // 同槽互覆，绝不双发（部分兼容网关对双参数返回 conflicting_token_limits 400，
            // D022-01 的目标是 baseUrl 可换任意兼容网关；OpenAI 官方 o 系列如需
            // max_completion_tokens 由后续 format 级配置承接）。
            builder.maxTokens(model.getMaxTokens());
        }
        return builder.build();
    }

    private ChatModel assembleOpenAiResponses(AgentProvider provider, AgentModel model, String plainApiKey) {
        OpenAiResponsesChatModel.Builder builder = OpenAiResponsesChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(orEmpty(plainApiKey))
                .modelName(model.getModelName());
        if (model.getTemperature() != null) {
            builder.temperature(toDouble(model.getTemperature()));
        }
        if (model.getTopP() != null) {
            builder.topP(toDouble(model.getTopP()));
        }
        if (model.getMaxTokens() != null) {
            builder.maxOutputTokens(model.getMaxTokens());
        }
        return builder.build();
    }

    private ChatModel assembleAnthropic(AgentProvider provider, AgentModel model, String plainApiKey) {
        AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(orEmpty(plainApiKey))
                .modelName(model.getModelName())
                // Anthropic Messages 必填 max_tokens；未配置给兜底值
                .maxTokens(model.getMaxTokens() != null ? model.getMaxTokens() : ANTHROPIC_DEFAULT_MAX_TOKENS)
                .timeout(timeout(model))
                .maxRetries(attempts(model));
        if (model.getTemperature() != null) {
            builder.temperature(toDouble(model.getTemperature()));
        }
        if (model.getTopP() != null) {
            builder.topP(toDouble(model.getTopP()));
        }
        return builder.build();
    }

    private Duration timeout(AgentModel model) {
        int seconds = model.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, model.getTimeoutSeconds());
        return Duration.ofSeconds(seconds);
    }

    /** maxRetries = max(0, retryCount)（LC4j 语义为“失败后重试次数”，retryCount=2 → 最多 3 次尝试） */
    private Integer attempts(AgentModel model) {
        return model.getRetryCount() == null ? 0 : Math.max(0, model.getRetryCount());
    }

    private Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private String orEmpty(String plainApiKey) {
        return plainApiKey != null ? plainApiKey : "";
    }
}
