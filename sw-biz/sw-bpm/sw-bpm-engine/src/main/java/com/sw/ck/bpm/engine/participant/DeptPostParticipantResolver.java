package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.DEPT_POST);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        Object value = context.getStrategyValue();
        if (!(value instanceof Map<?, ?> mapping)) {
            return Optional.of(List.of());
        }
        Long deptId = toLong(mapping.get("deptId"));
        Object postCode = mapping.get("postCode");
        String code = postCode == null || String.valueOf(postCode).isBlank()
                ? null : String.valueOf(postCode);
        if (deptId == null || code == null) {
            return Optional.of(List.of());
        }
        Optional<List<Long>> memberIds = userQueryFacade.findActiveUserIdsByDeptAndPost(
                deptId, code, context.getTenantId());
        if (memberIds.isEmpty()) {
            // 部门/岗位/租户上下文缺失：无法解析任职用户，交由注册失败策略处置
            return Optional.of(List.of());
        }
        return Optional.of(memberIds.orElseThrow().stream()
                .map(String::valueOf).distinct().toList());
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
