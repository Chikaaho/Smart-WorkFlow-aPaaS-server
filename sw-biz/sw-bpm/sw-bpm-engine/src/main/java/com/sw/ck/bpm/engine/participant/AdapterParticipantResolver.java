package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.List;
import java.util.Optional;

/** 适配器策略：由稳定 adapterId 选择后端 SPI 实现。 */
@Component
public class AdapterParticipantResolver implements NodeParticipantResolver {

    private final ParticipantResolverRegistry registry;

    public AdapterParticipantResolver(@Lazy ParticipantResolverRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.ADAPTER);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        // 适配器解析成功但零参与人时由注册结果按失败策略拒绝，契约恒 present
        return Optional.of(registry.resolveAdapter(context));
    }
}
