package com.sw.ck.agent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentModelMapper;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型运行时服务（F1 阶段 1，D022-04/05）：统一承接编排 / 图执行 / 单步调试三个调用点
 * 的"解析模型 → 单例复用 → 装配"。
 * <ul>
 *   <li><b>单例复用</b>：按模型 id 缓存已装配的 LangChain4j {@code ChatModel}；
 *       缓存键含"模型修订号 + 供应商修订号 + 双方启停状态"（乐观锁 version +
 *       enabled），供应商或模型被修改/停用/删除后下一次调用自动重建，无需事件失效。</li>
 *   <li><b>启停校验</b>：模型或供应商任一停用/不存在即抛业务异常（旧实现图执行不查
 *       enabled 的缺陷在此收口，方向文件阶段 1.4）。</li>
 *   <li><b>多 Key 轮换（D022-05）</b>：{@link #lock} 限流锁定、{@link #nextCandidate}
 *       组内下一候选，供三个调用点共用（旧实现仅编排入口有轮换）。</li>
 * </ul>
 * <p>
 * 明文 API Key 仅在装配分支的局部变量内存在，不进日志/异常/缓存键。缓存实例本身不含
 * 可枚举明文（Key 已烧入 HTTP 客户端请求头工厂）。
 * </p>
 */
@Service
@ConditionalOnProperty(prefix = "sw.agent", name = "enabled", havingValue = "true")
public class AgentModelRuntimeService {

    /** 限流锁定冷却时长默认值（秒）：与 V0.2.2 列默认值 60 一致 */
    private static final int DEFAULT_QUOTA_COOLDOWN_SECONDS = 60;

    /** 解析结果：模型 + 供应商 + 已装配的单例客户端 */
    public record ModelInstance(AgentModel model, AgentProvider provider,
                                dev.langchain4j.model.chat.ChatModel chatModel) {
    }

    /** 流式解析结果：与同步模型共用配置、启停和多 Key 候选语义。 */
    public record StreamingModelInstance(AgentModel model, AgentProvider provider,
                                         dev.langchain4j.model.chat.StreamingChatModel chatModel) {
    }

    /** Embedding 解析结果（F1 阶段 3）：模型 + 供应商 + 单例 EmbeddingModel */
    public record EmbeddingInstance(AgentModel model, AgentProvider provider,
                                    dev.langchain4j.model.embedding.EmbeddingModel embeddingModel) {
    }

    /** 缓存条目：修订键 + 装配产物 */
    private record CachedEntry(String revisionKey, dev.langchain4j.model.chat.ChatModel chatModel) {
    }

    private record CachedStreamingEntry(
            String revisionKey, dev.langchain4j.model.chat.StreamingChatModel chatModel) {
    }

    /** Embedding 缓存条目 */
    private record CachedEmbeddingEntry(String revisionKey,
                                        dev.langchain4j.model.embedding.EmbeddingModel embeddingModel) {
    }

    private final AgentModelMapper modelMapper;
    private final AgentProviderMapper providerMapper;
    private final AesGcmCipher cipher;
    private final AgentChatModelAssembler assembler = new AgentChatModelAssembler();
    private final AgentEmbeddingModelAssembler embeddingAssembler = new AgentEmbeddingModelAssembler();

    /** 模型 id → 装配产物（单例复用，D022-04；修订键不匹配即重建覆盖） */
    private final Map<Long, CachedEntry> cache = new ConcurrentHashMap<>();

    /** 模型 id → 真实流式客户端；修订键规则与同步客户端一致。 */
    private final Map<Long, CachedStreamingEntry> streamingCache = new ConcurrentHashMap<>();

    /** 模型 id → Embedding 装配产物（阶段 3，单例复用同 D022-04） */
    private final Map<Long, CachedEmbeddingEntry> embeddingCache = new ConcurrentHashMap<>();

    public AgentModelRuntimeService(AgentModelMapper modelMapper,
                                    AgentProviderMapper providerMapper,
                                    AesGcmCipher cipher) {
        this.modelMapper = modelMapper;
        this.providerMapper = providerMapper;
        this.cipher = cipher;
    }

    /**
     * 执行前可用性校验（存在 + 模型/供应商均启用）：与 {@link #acquire(Long)} 同语义，
     * 独立入口便于测试对校验路径单独打桩（校验不消耗执行阶段的桩实例）。
     */
    public void assertUsable(Long modelId) {
        acquire(modelId);
    }

    /** 模型/供应商不可用的专用 errorKey：不在消息目录中，全局异常处理器按缺省文案
     * 原样透出可读原因（F1S1-G4：执行/调试拒绝必须可读，不能落通用 param_error 文案） */
    public static final String ERROR_KEY_MODEL_UNAVAILABLE = "agent.model_unavailable";

    /**
     * 解析模型并返回单例客户端（模型/供应商任一停用或不存在 → 业务异常）。
     */
    public ModelInstance acquire(Long modelId) {
        if (modelId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型 id 不能为空");
        }
        AgentModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "模型不存在或已删除: " + modelId);
        }
        if (model.getEnabled() == null || !model.getEnabled()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    ERROR_KEY_MODEL_UNAVAILABLE, "模型已停用: " + model.getName());
        }
        AgentProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "供应商不存在或已删除: " + model.getProviderId());
        }
        if (provider.getEnabled() == null || !provider.getEnabled()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    ERROR_KEY_MODEL_UNAVAILABLE, "供应商已停用: " + provider.getName());
        }
        String revisionKey = revisionKey(model, provider);
        dev.langchain4j.model.chat.ChatModel chatModel = cache.compute(modelId, (id, cached) ->
                cached != null && cached.revisionKey().equals(revisionKey)
                        ? cached
                        : buildAndCache(provider, model, revisionKey)).chatModel();
        return new ModelInstance(model, provider, chatModel);
    }

    /** 解析并复用真实流式客户端。 */
    public StreamingModelInstance acquireStreaming(Long modelId) {
        AgentModel model = requireEnabledModel(modelId);
        AgentProvider provider = requireEnabledProvider(model);
        String revisionKey = revisionKey(model, provider);
        dev.langchain4j.model.chat.StreamingChatModel chatModel = streamingCache.compute(modelId,
                (id, cached) -> cached != null && cached.revisionKey().equals(revisionKey)
                        ? cached : buildStreamingAndCache(provider, model, revisionKey)).chatModel();
        return new StreamingModelInstance(model, provider, chatModel);
    }

    /**
     * 解析 EMBEDDING 模型并返回单例客户端（F1 阶段 3）：除启停校验外，要求模型
     * capabilities 含 EMBEDDING；Anthropic 格式无 Embedding 接口，由装配模板显式拒绝。
     */
    public EmbeddingInstance acquireEmbedding(Long modelId) {
        AgentModel model = requireEnabledModel(modelId);
        AgentProvider provider = requireEnabledProvider(model);
        if (model.getCapabilities() == null
                || !model.getCapabilities().toUpperCase(java.util.Locale.ROOT).contains("EMBEDDING")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    ERROR_KEY_MODEL_UNAVAILABLE, "模型不具备 EMBEDDING 能力: " + model.getName());
        }
        String revisionKey = revisionKey(model, provider);
        dev.langchain4j.model.embedding.EmbeddingModel embeddingModel = embeddingCache.compute(modelId,
                (id, cached) -> cached != null && cached.revisionKey().equals(revisionKey)
                        ? cached
                        : buildEmbeddingAndCache(provider, model, revisionKey)).embeddingModel();
        return new EmbeddingInstance(model, provider, embeddingModel);
    }

    /** 校验 EMBEDDING 模型可用（知识库创建/上传前置检查）。 */
    public void assertEmbeddingUsable(Long modelId) {
        acquireEmbedding(modelId);
    }

    /**
     * 批量向量化（知识库文档/检索查询共用）：返回顺序与入参一致。
     */
    public List<float[]> embedAll(Long modelId, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingInstance instance = acquireEmbedding(modelId);
        List<dev.langchain4j.data.segment.TextSegment> segments = texts.stream()
                .map(dev.langchain4j.data.segment.TextSegment::from)
                .toList();
        return instance.embeddingModel().embedAll(segments).content().stream()
                .map(dev.langchain4j.data.embedding.Embedding::vector)
                .toList();
    }

    private AgentModel requireEnabledModel(Long modelId) {
        if (modelId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型 id 不能为空");
        }
        AgentModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "模型不存在或已删除: " + modelId);
        }
        if (model.getEnabled() == null || !model.getEnabled()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    ERROR_KEY_MODEL_UNAVAILABLE, "模型已停用: " + model.getName());
        }
        return model;
    }

    private AgentProvider requireEnabledProvider(AgentModel model) {
        AgentProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "供应商不存在或已删除: " + model.getProviderId());
        }
        if (provider.getEnabled() == null || !provider.getEnabled()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    ERROR_KEY_MODEL_UNAVAILABLE, "供应商已停用: " + provider.getName());
        }
        return provider;
    }

    /** 仅按需装配（密钥解密只发生在重建分支，明文不离开本方法） */
    private CachedEntry buildAndCache(AgentProvider provider, AgentModel model, String revisionKey) {
        String plainApiKey = null;
        try {
            if (provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isEmpty()) {
                plainApiKey = cipher.decrypt(provider.getApiKeyCipher());
            }
            return new CachedEntry(revisionKey, assembler.assemble(provider, model, plainApiKey));
        } finally {
            plainApiKey = null;
        }
    }

    /** 流式客户端按需装配；明文密钥不进入缓存键、日志或异常消息。 */
    private CachedStreamingEntry buildStreamingAndCache(
            AgentProvider provider, AgentModel model, String revisionKey) {
        String plainApiKey = null;
        try {
            if (provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isEmpty()) {
                plainApiKey = cipher.decrypt(provider.getApiKeyCipher());
            }
            return new CachedStreamingEntry(revisionKey,
                    assembler.assembleStreaming(provider, model, plainApiKey));
        } finally {
            plainApiKey = null;
        }
    }

    /** EMBEDDING 按需装配（密钥生命周期同聊天模型） */
    private CachedEmbeddingEntry buildEmbeddingAndCache(AgentProvider provider, AgentModel model,
                                                        String revisionKey) {
        String plainApiKey = null;
        try {
            if (provider.getApiKeyCipher() != null && !provider.getApiKeyCipher().isEmpty()) {
                plainApiKey = cipher.decrypt(provider.getApiKeyCipher());
            }
            return new CachedEmbeddingEntry(revisionKey,
                    embeddingAssembler.assemble(provider, model, plainApiKey));
        } finally {
            plainApiKey = null;
        }
    }

    /** 修订键：乐观锁 version + 启停状态（任一变化 → 下一次 acquire 重建实例） */
    private String revisionKey(AgentModel model, AgentProvider provider) {
        return model.getVersion() + ":" + model.getEnabled() + ":"
                + provider.getVersion() + ":" + provider.getEnabled();
    }

    /** 限流锁定当前模型（locked_until = now + cooldown，惰性过期，无清理任务） */
    public void lock(Long modelId, int cooldownSeconds) {
        modelMapper.update(null,
                Wrappers.<AgentModel>lambdaUpdate()
                        .eq(AgentModel::getId, modelId)
                        .set(AgentModel::getLockedUntil, LocalDateTime.now().plusSeconds(cooldownSeconds)));
    }

    /**
     * 组内下一候选：同 groupKey、enabled=1、未锁定、未试过，按 sort 升序 + id 升序取第一条；
     * 无候选返回 null。租户隔离由租户拦截器自动完成。
     */
    public AgentModel nextCandidate(String groupKey, Set<Long> excludeIds, LocalDateTime now) {
        List<AgentModel> candidates = modelMapper.selectList(
                Wrappers.<AgentModel>lambdaQuery()
                        .eq(AgentModel::getGroupKey, groupKey)
                        // 列为 BOOLEAN（V0.2.2 DDL），须传布尔字面量（整数在 PG 上类型不匹配）
                        .eq(AgentModel::getEnabled, true)
                        .notIn(!excludeIds.isEmpty(), AgentModel::getId, excludeIds)
                        .and(w -> w.isNull(AgentModel::getLockedUntil)
                                .or().le(AgentModel::getLockedUntil, now))
                        .orderByAsc(AgentModel::getSort)
                        .orderByAsc(AgentModel::getId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** 限流冷却时长（null 防御取默认 60 秒） */
    public int cooldownSeconds(AgentModel model) {
        return model.getQuotaCooldownSeconds() == null
                ? DEFAULT_QUOTA_COOLDOWN_SECONDS
                : model.getQuotaCooldownSeconds();
    }

    /**
     * 限流异常识别：沿 cause 链逐层检查 {@link dev.langchain4j.exception.HttpException}
     * 的 {@code statusCode() == 429}（穿透包装层，与编排入口同语义）。
     */
    public static boolean isRateLimited(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof dev.langchain4j.exception.HttpException http && http.statusCode() == 429) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 带 429 密钥轮换的单跳聊天调用（D022-05，图执行 / 单步调试共用）：先 acquire 当前
     * 模型调用；遇 429 且模型配置了 groupKey 时锁定当前模型（冷却）并切换组内下一候选
     * 重试；无候选或非限流异常原样上抛（由调用方按既有分类包装）。消息组装与 usage
     * 提取仍归调用方，本方法只负责"选模型 → 调用 → 限流换把"。
     */
    public dev.langchain4j.model.chat.response.ChatResponse chatWithRotation(
            Long modelId, List<dev.langchain4j.data.message.ChatMessage> messages) {
        return chatWithRotation(modelId, messages, null);
    }

    /**
     * 带工具规格与 429 轮换的聊天调用（阶段 2：LLM 节点工具循环）。
     * <p>
     * 工具规格由节点配置的启用工具构造；空/null 表示不绑定工具（与单跳语义一致）。
     * 轮换仍只切换模型实例，消息与工具规格由调用方维护并跨轮复用。
     * </p>
     */
    public dev.langchain4j.model.chat.response.ChatResponse chatWithRotation(
            Long modelId, List<dev.langchain4j.data.message.ChatMessage> messages,
            List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
        java.util.Set<Long> tried = new java.util.HashSet<>();
        Long current = modelId;
        while (true) {
            ModelInstance instance = acquire(current);
            tried.add(current);
            try {
                dev.langchain4j.model.chat.request.ChatRequest.Builder builder =
                        dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages);
                if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                    builder.toolSpecifications(toolSpecifications);
                }
                return instance.chatModel().chat(builder.build());
            } catch (Exception e) {
                if (!isRateLimited(e) || instance.model().getGroupKey() == null) {
                    throw e;
                }
                lock(current, cooldownSeconds(instance.model()));
                AgentModel next = nextCandidate(instance.model().getGroupKey(), tried, LocalDateTime.now());
                if (next == null) {
                    throw e;
                }
                current = next.getId();
            }
        }
    }

    /**
     * 真实增量调用。首个可见 delta 到达后不再轮换，避免重放已展示文本或工具副作用；
     * 仅在尚未产生任何可见输出且收到 429 时切换同组候选。
     */
    public dev.langchain4j.model.chat.response.ChatResponse chatStreamingWithRotation(
            Long modelId, List<dev.langchain4j.data.message.ChatMessage> messages,
            List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications,
            java.util.function.Consumer<String> onDelta) {
        java.util.Set<Long> tried = new java.util.HashSet<>();
        Long current = modelId;
        while (true) {
            StreamingModelInstance instance = acquireStreaming(current);
            tried.add(current);
            java.util.concurrent.CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse> result =
                    new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean();
            dev.langchain4j.model.chat.request.ChatRequest.Builder builder =
                    dev.langchain4j.model.chat.request.ChatRequest.builder().messages(messages);
            if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
                builder.toolSpecifications(toolSpecifications);
            }
            instance.chatModel().chat(builder.build(),
                    new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partialResponse) {
                            if (partialResponse != null && !partialResponse.isEmpty()) {
                                visible.set(true);
                                onDelta.accept(partialResponse);
                            }
                        }

                        @Override
                        public void onCompleteResponse(
                                dev.langchain4j.model.chat.response.ChatResponse response) {
                            result.complete(response);
                        }

                        @Override
                        public void onError(Throwable error) {
                            result.completeExceptionally(error);
                        }
                    });
            try {
                return result.join();
            } catch (java.util.concurrent.CompletionException wrapped) {
                Throwable error = wrapped.getCause() == null ? wrapped : wrapped.getCause();
                if (visible.get() || !isRateLimited(error) || instance.model().getGroupKey() == null) {
                    if (error instanceof RuntimeException runtimeException) throw runtimeException;
                    throw new RuntimeException(error);
                }
                lock(current, cooldownSeconds(instance.model()));
                AgentModel next = nextCandidate(instance.model().getGroupKey(), tried, LocalDateTime.now());
                if (next == null) {
                    if (error instanceof RuntimeException runtimeException) throw runtimeException;
                    throw new RuntimeException(error);
                }
                current = next.getId();
            }
        }
    }
}
