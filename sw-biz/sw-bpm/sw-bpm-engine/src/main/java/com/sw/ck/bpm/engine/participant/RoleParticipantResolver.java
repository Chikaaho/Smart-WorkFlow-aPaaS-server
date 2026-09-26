package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 角色策略：只解析当前租户、启用角色和启用成员。 */
@Component
public class RoleParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;

    public RoleParticipantResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.ROLE);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        Collection<?> values = context.getStrategyValue() instanceof Collection<?> collection
                ? collection : List.of(context.getStrategyValue());
        List<String> codes = values.stream().map(String::valueOf).filter(item -> !item.isBlank()).distinct().toList();
        if (codes.isEmpty()) {
            return Optional.of(List.of());
        }
        Optional<List<Long>> memberIds = userQueryFacade.findActiveUserIdsByRoleCodes(codes, context.getTenantId());
        if (memberIds.isEmpty()) {
            // 角色集合/租户上下文缺失：无法解析成员，交由注册失败策略处置
            return Optional.of(List.of());
        }
        return Optional.of(memberIds.orElseThrow().stream()
                .map(String::valueOf).distinct().toList());
    }
}
