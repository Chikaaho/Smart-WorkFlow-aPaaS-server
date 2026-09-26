package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 部门负责人策略：只解析正常状态部门的负责人，且负责人用户启用、同租户。 */
@Component
public class DeptLeaderParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;

    public DeptLeaderParticipantResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.DEPT_LEADER);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        List<Long> deptIds = toLongs(context.getStrategyValue());
        if (deptIds.isEmpty()) {
            return Optional.of(List.of());
        }
        Optional<List<Long>> leaderIds = userQueryFacade.findActiveUserIdsByDeptLeaders(
                deptIds, context.getTenantId());
        if (leaderIds.isEmpty()) {
            // 部门/租户上下文缺失：无法解析负责人，交由注册失败策略处置
            return Optional.of(List.of());
        }
        return Optional.of(leaderIds.orElseThrow().stream()
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
