package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantAdapter;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.common.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 统一参与人解析注册结果；启动时拒绝重复 strategy/adapter。 */
@Component
public class ParticipantResolverRegistry {

    private final Map<String, NodeParticipantResolver> resolvers;
    private final Map<String, NodeParticipantAdapter> adapters;

    public ParticipantResolverRegistry(List<NodeParticipantResolver> resolvers,
                                       List<NodeParticipantAdapter> adapters) {
        this.resolvers = uniqueResolvers(resolvers);
        this.adapters = uniqueAdapters(adapters);
    }

    public List<String> resolve(NodeParticipantContext context) {
        if (context == null || context.getStrategy() == null || context.getStrategy().isBlank()
                || (context.getStrategyValue() == null
                && !"ADAPTER".equalsIgnoreCase(context.getStrategy()))) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID);
        }
        if ("ADAPTER".equalsIgnoreCase(context.getStrategy())
                && (context.getAdapterId() == null || context.getAdapterId().isBlank())) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID);
        }
        NodeParticipantResolver resolver = this.resolvers.get(context.getStrategy());
        if (resolver == null && context.getStrategy() != null) {
            resolver = this.resolvers.get(context.getStrategy().toUpperCase());
        }
        if (resolver == null) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_TYPE_NOT_IMPLEMENTED.getCode(),
                    "未实现的参与人策略: " + context.getStrategy());
        }
        Optional<List<String>> resolved = resolver.resolve(context);
        if (resolved.isEmpty()) {
            // 解析器契约恒 present：以上空表达属契约违约，按解析不到参与人拒绝
            throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY);
        }
        List<String> result = resolved.orElseThrow();
        if (result.isEmpty()) {
            // 解析成功但零参与人：交由注册失败策略处置
            throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY);
        }
        return result.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    public List<String> resolveAdapter(NodeParticipantContext context) {
        NodeParticipantAdapter adapter = adapters.get(context.getAdapterId());
        if (adapter == null) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_ADAPTER_NOT_FOUND.getCode(),
                    "参与人适配器不存在: " + context.getAdapterId());
        }
        Optional<List<String>> resolved = adapter.resolve(context);
        if (resolved.isEmpty()) {
            // 适配器契约恒 present：以上空表达属契约违约，按解析不到参与人拒绝
            throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY);
        }
        List<String> result = resolved.orElseThrow();
        if (result.isEmpty()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY);
        }
        return result.stream().filter(item -> item != null && !item.isBlank()).distinct().toList();
    }

    public Map<String, NodeParticipantAdapter> adapters() {
        return Map.copyOf(adapters);
    }

    private static Map<String, NodeParticipantResolver> uniqueResolvers(
            Collection<NodeParticipantResolver> values) {
        Map<String, NodeParticipantResolver> result = new LinkedHashMap<>();
        for (NodeParticipantResolver resolver : values == null ? List.<NodeParticipantResolver>of() : values) {
            if (resolver == null) {
                throw new IllegalStateException("参与人策略重复或标识为空");
            }
            // 策略标识为注册期契约（恒 present）：缺失即拒绝装配
            String strategy = resolver.strategy().orElseThrow(
                    () -> new IllegalStateException("参与人策略重复或标识为空"));
            if (strategy.isBlank() || result.putIfAbsent(strategy, resolver) != null) {
                throw new IllegalStateException("参与人策略重复或标识为空");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, NodeParticipantAdapter> uniqueAdapters(
            Collection<NodeParticipantAdapter> values) {
        Map<String, NodeParticipantAdapter> result = new LinkedHashMap<>();
        for (NodeParticipantAdapter adapter : values == null ? List.<NodeParticipantAdapter>of() : values) {
            if (adapter == null) {
                throw new IllegalStateException("参与人适配器重复或标识为空");
            }
            // 适配器标识为注册期契约（恒 present）：缺失即拒绝装配
            String id = adapter.id().orElseThrow(
                    () -> new IllegalStateException("参与人适配器重复或标识为空"));
            if (id.isBlank() || result.putIfAbsent(id, adapter) != null) {
                throw new IllegalStateException("参与人适配器重复或标识为空");
            }
        }
        return Map.copyOf(result);
    }
}
