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

    /** 缓存条目：修订键 + 装配产物 */
    private record CachedEntry(String revisionKey, dev.langchain4j.model.chat.ChatModel chatModel) {
    }

    private final AgentModelMapper modelMapper;
    private final AgentProviderMapper providerMapper;
    private final AesGcmCipher cipher;
    private final AgentChatModelAssembler assembler = new AgentChatModelAssembler();

    /** 模型 id → 装配产物（单例复用，D022-04；修订键不匹配即重建覆盖） */
    private final Map<Long, CachedEntry> cache = new ConcurrentHashMap<>();

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
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型已停用: " + model.getName());
        }
        AgentProvider provider = providerMapper.selectById(model.getProviderId());
        if (provider == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "供应商不存在或已删除: " + model.getProviderId());
        }
        if (provider.getEnabled() == null || !provider.getEnabled()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "供应商已停用: " + provider.getName());
        }
        String revisionKey = revisionKey(model, provider);
        dev.langchain4j.model.chat.ChatModel chatModel = cache.compute(modelId, (id, cached) ->
                cached != null && cached.revisionKey().equals(revisionKey)
                        ? cached
                        : buildAndCache(provider, model, revisionKey)).chatModel();
        return new ModelInstance(model, provider, chatModel);
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
                        .eq(AgentModel::getEnabled, 1)
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
}
