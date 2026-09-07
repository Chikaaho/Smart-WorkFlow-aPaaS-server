package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/** 适配器策略：由稳定 adapterId 选择后端 SPI 实现。 */
@Component
public class AdapterParticipantResolver implements NodeParticipantResolver {

    private final ParticipantResolverRegistry registry;

    public AdapterParticipantResolver(@Lazy ParticipantResolverRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String strategy() {
        return ParticipantStrategy.ADAPTER;
    }

    @Override
    public List<String> resolve(NodeParticipantContext context) {
        return registry.resolveAdapter(context);
    }
}
