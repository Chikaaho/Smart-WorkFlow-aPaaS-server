package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 部门+岗位组合策略：value 形如 {@code {deptId: 123, postCode: "manager"}}，
 * 解析该部门内担任该岗位的有效用户（部门正常、岗位启用、用户启用、同租户）。
 */
@Component
public class DeptPostParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;

    public DeptPostParticipantResolver(UserQueryFacade userQueryFacade) {
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public String strategy() {
        return ParticipantStrategy.DEPT_POST;
    }

    @Override
    public List<String> resolve(NodeParticipantContext context) {
        Object value = context.getStrategyValue();
        if (!(value instanceof Map<?, ?> mapping)) {
            return List.of();
        }
        Long deptId = toLong(mapping.get("deptId"));
        Object postCode = mapping.get("postCode");
        String code = postCode == null || String.valueOf(postCode).isBlank()
                ? null : String.valueOf(postCode);
        if (deptId == null || code == null) {
            return List.of();
        }
        return userQueryFacade.findActiveUserIdsByDeptAndPost(deptId, code, context.getTenantId()).stream()
                .map(String::valueOf).distinct().toList();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
