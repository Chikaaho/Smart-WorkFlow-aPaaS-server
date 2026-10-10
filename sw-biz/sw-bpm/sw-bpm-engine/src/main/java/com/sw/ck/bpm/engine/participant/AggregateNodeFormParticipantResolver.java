package com.sw.ck.bpm.engine.participant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.NodeParticipantResolver;
import com.sw.ck.bpm.api.participant.ParticipantStrategy;
import com.sw.ck.bpm.api.variable.NodeFormPersonAggregatePort;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 节点表单人员聚合策略（P64 阶段Ⅱ A07 聚合会签）。
 * <p>
 * 读取指定节点指定轮次全部有效并行任务的节点表单人员字段（经
 * {@link NodeFormPersonAggregatePort}，实现按业务编排域权威读取），按用户身份并集去重，
 * 生成下一轮会签参与者；来源任务/字段/轮次经端口实现与参与人快照可追溯。
 * 已撤回/取消/被新轮次取代的数据不进入有效集合（端口只返回有效最终提交）。
 * value 形状校验单一权威见 {@link ParticipantStrategy#nodeFormAggregateConfigError}。
 * </p>
 */
@Component
public class AggregateNodeFormParticipantResolver implements NodeParticipantResolver {

    private final ObjectProvider<NodeFormPersonAggregatePort> aggregatePort;
    private final UserQueryFacade userQueryFacade;

    public AggregateNodeFormParticipantResolver(ObjectProvider<NodeFormPersonAggregatePort> aggregatePort,
                                                UserQueryFacade userQueryFacade) {
        this.aggregatePort = aggregatePort;
        this.userQueryFacade = userQueryFacade;
    }

    @Override
    public Optional<String> strategy() {
        return Optional.of(ParticipantStrategy.NODE_FORM_AGGREGATE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<List<String>> resolve(NodeParticipantContext context) {
        if (!(context.getStrategyValue() instanceof Map<?, ?> binding)) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_AGGREGATE_INVALID.getCode(),
                    "NODE_FORM_AGGREGATE 配置必须是 {nodeKey, formField[, round]}");
        }
        Map<String, Object> config = (Map<String, Object>) binding;
        Optional<String> shapeError = ParticipantStrategy.nodeFormAggregateConfigError(config);
        if (shapeError.isPresent()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_AGGREGATE_INVALID.getCode(),
                    shapeError.get());
        }
        NodeFormPersonAggregatePort port = aggregatePort.getIfAvailable();
        if (port == null) {
            // 端口未装配（engine 独立测试）：交由注册失败策略处置
            return Optional.of(List.of());
        }
        String nodeKey = String.valueOf(config.get("nodeKey"));
        String formField = String.valueOf(config.get("formField"));
        String round = config.get("round") == null ? "CURRENT"
                : String.valueOf(config.get("round")).toUpperCase();
        int roundOffset = "PREVIOUS".equals(round) ? 1 : 0;
        Optional<List<String>> rawValues = port.readPersonFieldValues(
                context.getTenantId(), context.getProcessInstanceId(), nodeKey, formField, roundOffset);
        if (rawValues.isEmpty()) {
            // 实例/租户上下文缺失：无法解析，交由注册失败策略处置
            return Optional.of(List.of());
        }
        // 并集去重（稳定 ID；保持来源顺序）
        Set<String> distinct = new LinkedHashSet<>(rawValues.orElseThrow());
        if (distinct.isEmpty()) {
            return Optional.of(List.of());
        }
        // 失效/越权人员整体拒绝（与 FORM_FIELD 口径一致：准确性优先、可诊断）
        List<Long> parsed = distinct.stream().map(value -> {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException e) {
                throw new BaseException(BpmErrorCode.PARTICIPANT_AGGREGATE_INVALID.getCode(),
                        "聚合来源包含非用户 ID 值: " + value);
            }
        }).toList();
        Optional<List<Long>> active = userQueryFacade.findActiveUserIds(parsed, context.getTenantId());
        if (active.isEmpty()) {
            return Optional.of(List.of());
        }
        Set<Long> valid = new LinkedHashSet<>(active.orElseThrow());
        if (valid.size() != parsed.size()) {
            throw new BaseException(BpmErrorCode.PARTICIPANT_RESOLVE_EMPTY.getCode(),
                    "聚合来源包含失效或越权人员，已拒绝解析参与人");
        }
        return Optional.of(List.copyOf(distinct));
    }
}
