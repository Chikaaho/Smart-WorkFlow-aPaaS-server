package com.sw.ck.bpm.api.participant;

import java.util.List;

/** 引擎到流程业务持久化层的单向防腐接缝。 */
public interface ParticipantSnapshotRecorder {

    void record(String processInstanceId, String nodeKey, String taskId,
                List<String> participantIds, Long tenantId);

    /** 首个有效动作结算候选快照，其余候选转为不可处理并保留原因。 */
    default void settle(String processInstanceId, String nodeKey, String taskId,
                        String actorId, String action, Long tenantId) {
        // 兼容仅提供 record 的外部实现；生产实现覆盖此方法持久化结算状态。
    }
}
