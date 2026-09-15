package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** 部门负责人策略：只解析正常状态部门的负责人，且负责人用户启用、同租户。 */
@Component
public class DeptLeaderParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;

    public DeptLeaderParticipantResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public String strategy() {
        return ParticipantStrategy.DEPT_LEADER;
    }

    @Override
    public List<String> resolve(NodeParticipantContext context) {
        List<Long> deptIds = toLongs(context.getStrategyValue());
        if (deptIds.isEmpty()) {
            return List.of();
        }
        return userQueryFacade.findActiveUserIdsByDeptLeaders(deptIds, context.getTenantId()).stream()
                .map(String::valueOf).distinct().toList();
    }

    private List<Long> toLongs(Object value) {
        Collection<?> values = value instanceof Collection<?> collection ? collection : List.of(value);
        return values.stream().map(item -> {
            try { return Long.valueOf(String.valueOf(item)); }
            catch (NumberFormatException e) { return null; }
        }).filter(item -> item != null).distinct().toList();
    }
}
