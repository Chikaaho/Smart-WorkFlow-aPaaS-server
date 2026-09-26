package com.sw.ck.bpm.api.participant;

import com.sw.ck.bpm.api.result.MutationOutcome;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 引擎到流程业务持久化层的单向防腐接缝。 */
public interface ParticipantSnapshotRecorder {

    /**
     * 冻结本轮参与人快照。
     *
     * @return present = {@link MutationOutcome#APPLIED} 快照已冻结 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 同一轮次快照已存在；
     *         当前实现恒 APPLIED
     */
    Optional<MutationOutcome> record(String processInstanceId, String nodeKey, String taskId,
                                     List<String> participantIds, Long tenantId);

    /**
     * 记录快照并冻结参与人展示名（I1 历史身份不被改写）：此后用户改名/停用
     * 不影响该轮次的可读身份回显。displayNames 缺失的参与人名字段落为 null。
     * 默认实现退化为不带姓名的 record，兼容既有外部实现。
     *
     * @return present = {@link MutationOutcome#APPLIED} 快照已冻结 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 同一轮次快照已存在
     */
    default Optional<MutationOutcome> record(String processInstanceId, String nodeKey, String taskId,
                                             List<String> participantIds, Map<String, String> displayNames,
                                             Long tenantId) {
        return record(processInstanceId, nodeKey, taskId, participantIds, tenantId);
    }

    /**
     * 首个有效动作结算候选快照，其余候选转为不可处理并保留原因。
     *
     * @return present = {@link MutationOutcome#APPLIED} 本次完成结算 /
     *         {@link MutationOutcome#ALREADY_APPLIED} 无可结算候选（幂等）；
     *         兼容仅提供 record 的外部实现时返回
     *         {@link MutationOutcome#ALREADY_APPLIED}
     */
    default Optional<MutationOutcome> settle(String processInstanceId, String nodeKey, String taskId,
                                             String actorId, String action, Long tenantId) {
        // 兼容仅提供 record 的外部实现；生产实现覆盖此方法持久化结算状态。
        return Optional.of(MutationOutcome.ALREADY_APPLIED);
    }
}
