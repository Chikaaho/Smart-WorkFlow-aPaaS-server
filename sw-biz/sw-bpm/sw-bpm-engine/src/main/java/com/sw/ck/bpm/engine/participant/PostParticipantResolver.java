package com.sw.ck.bpm.engine.participant;

import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.api.delegate.PositionDelegateFacade;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 岗位策略：解析启用岗位的有效任职用户（用户启用、同租户）。
 * <p>
 * P64 阶段Ⅱ（A07）起委托感知：解析顺序为源岗位 → 有效委托关系 → 受托岗位 → 实际办理人
 * （{@link PositionDelegateFacade}；未配置有效关系时与 P63 原义一致，直接解析源岗位有效人员）。
 * value 形状：岗位编码（或编码集合），或 {@code {postCodes[, deptId]}} 显式业务部门上下文；
 * 委托链超过 4 跳/循环/受托岗位空缺抛 {@code DELEGATE_RESOLVE_FAILED} 可诊断异常，
 * 不自动回退。同轮名单冻结沿既有参与人快照，后续委托变更不改已生成任务。
 * </p>
 */
@Component
public class PostParticipantResolver implements NodeParticipantResolver {

    private final UserQueryFacade userQueryFacade;
    /** 可选委托解析门面（engine 独立测试未装配时保持 P63 原义）。 */
    private final ObjectProvider<PositionDelegateFacade> delegateFacade;

    public PostParticipantResolver(UserQueryFacade userQueryFacade) {
        this(userQueryFacade, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PostParticipantResolver(UserQueryFacade userQueryFacade,
                                   ObjectProvider<PositionDelegateFacade> delegateFacade) {
        this.userQueryFacade = userQueryFacade;
        this.delegateFacade = delegateFacade;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.POST);
    }

    @Override
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        Collection<?> values;
        Long deptId = null;
        if (context.getStrategyValue() instanceof Map<?, ?> mapping
                && mapping.get("postCodes") != null) {
            // 显式对象形态：{postCodes[, deptId]}；deptId 为业务部门上下文（精确部门委托）
            Object rawCodes = mapping.get("postCodes");
            values = rawCodes instanceof Collection<?> collection
                    ? collection : List.of(rawCodes);
            deptId = parseLong(mapping.get("deptId"));
        } else {
            values = context.getStrategyValue() instanceof Collection<?> collection
                    ? collection : List.of(context.getStrategyValue());
        }
        List<String> codes = values.stream().map(String::valueOf)
                .filter(item -> !item.isBlank()).distinct().toList();
        if (codes.isEmpty()) {
            return Optional.of(List.of());
        }
        Optional<List<Long>> memberIds = resolvePostActors(context, codes, deptId);
        if (memberIds.isEmpty()) {
            // 岗位集合/租户上下文缺失：无法解析任职用户，交由注册失败策略处置
            return Optional.of(List.of());
        }
        return Optional.of(memberIds.orElseThrow().stream()
                .map(String::valueOf).distinct().toList());
    }

    /** 委托感知解析：委托门面可用且租户上下文存在时按委托链解析，否则保持 P63 原义。 */
    private Optional<List<Long>> resolvePostActors(NodeParticipantContext context,
                                                   List<String> codes, Long deptId) {
        PositionDelegateFacade facade = delegateFacade == null ? null : delegateFacade.getIfAvailable();
        if (facade != null && context.getTenantId() != null) {
            return facade.resolvePostActors(context.getTenantId(), codes, deptId)
                    .map(PositionDelegateFacade.ResolvedPostActors::userIds);
        }
        return userQueryFacade.findActiveUserIdsByPostCodes(codes, context.getTenantId());
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_CONFIG_INVALID.getCode(),
                    "岗位策略 deptId 必须是部门 ID: " + value);
        }
    }
}
