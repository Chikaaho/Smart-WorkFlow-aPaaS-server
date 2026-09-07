package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** 角色策略：只解析当前租户、启用角色和启用成员。 */
@Component
public class RoleParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;

    public RoleParticipantResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public String strategy() {
        return ParticipantStrategy.ROLE;
    }

    @Override
    public List<String> resolve(NodeParticipantContext context) {
        Collection<?> values = context.getStrategyValue() instanceof Collection<?> collection
                ? collection : List.of(context.getStrategyValue());
        List<String> codes = values.stream().map(String::valueOf).filter(item -> !item.isBlank()).distinct().toList();
        return userQueryFacade.findActiveUserIdsByRoleCodes(codes, context.getTenantId()).stream()
                .map(String::valueOf).distinct().toList();
    }
}
