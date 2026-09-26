package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 固定用户策略：保存稳定 user id，运行时重新执行租户/启用状态校验。 */
@Component
public class FixedUserParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;

    public FixedUserParticipantResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.FIXED_USER);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        List<Long> configured = toLongs(context.getStrategyValue());
        if (configured.isEmpty()) {
            return Optional.of(List.of());
        }
        Optional<List<Long>> validUsers = userQueryFacade.findActiveUserIds(configured, context.getTenantId());
        if (validUsers.isEmpty()) {
            // 租户/查询上下文缺失：无法确认有效用户，交由注册失败策略处置
            return Optional.of(List.of());
        }
        List<Long> valid = validUsers.orElseThrow();
        return Optional.of(configured.stream().filter(valid::contains)
                .map(String::valueOf).distinct().toList());
    }

    private List<Long> toLongs(Object value) {
        Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
        return values.stream().map(item -> {
            try { return Long.valueOf(String.valueOf(item)); }
            catch (NumberFormatException e) { return null; }
        }).filter(item -> item != null).distinct().toList();
    }
}
