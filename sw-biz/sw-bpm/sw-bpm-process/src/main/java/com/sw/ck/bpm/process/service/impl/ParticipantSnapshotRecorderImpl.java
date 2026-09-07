package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ParticipantSnapshotRecorderImpl implements ParticipantSnapshotRecorder {

    private final ParticipantSnapshotMapper mapper;

    public ParticipantSnapshotRecorderImpl(ParticipantSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void record(String processInstanceId, String nodeKey, String taskId,
                       List<String> participantIds, Long tenantId) {
        for (String participantId : participantIds) {
            ParticipantSnapshot row = new ParticipantSnapshot();
            row.setProcessInstanceId(processInstanceId);
            row.setNodeKey(nodeKey);
            row.setTaskId(taskId);
            row.setParticipantId(participantId);
            row.setParticipantStatus("PENDING");
            row.setTenantId(tenantId);
            mapper.insert(row);
        }
    }

    @Override
    @Transactional
    public void settle(String processInstanceId, String nodeKey, String taskId,
                       String actorId, String action, Long tenantId) {
        LambdaUpdateWrapper<ParticipantSnapshot> invalidated = new LambdaUpdateWrapper<>();
        // 普通候选共享同一个 taskId；会签的每个多实例子任务拥有不同 taskId。
        // 结算必须覆盖同一流程实例 + 节点下仍为 PENDING 的其他候选，
        // 否则 ANY/阈值完成后 Flowable 已取消任务，但快照仍会错误显示可处理。
        invalidated.eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId)
                .eq(ParticipantSnapshot::getNodeKey, nodeKey)
                .eq(ParticipantSnapshot::getParticipantStatus, "PENDING");
        if (tenantId != null) invalidated.eq(ParticipantSnapshot::getTenantId, tenantId);
        if (actorId != null) invalidated.ne(ParticipantSnapshot::getParticipantId, actorId);
        invalidated.set(ParticipantSnapshot::getParticipantStatus, "INVALIDATED")
                .set(ParticipantSnapshot::getInvalidReason,
                        "节点已由 " + actorId + " 以 " + action + " 处理");
        mapper.update(null, invalidated);

        if (actorId != null) {
            LambdaUpdateWrapper<ParticipantSnapshot> handled = new LambdaUpdateWrapper<>();
            handled.eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId)
                    .eq(ParticipantSnapshot::getNodeKey, nodeKey)
                    .eq(ParticipantSnapshot::getTaskId, taskId)
                    .eq(ParticipantSnapshot::getParticipantId, actorId)
                    .eq(ParticipantSnapshot::getParticipantStatus, "PENDING")
                    .set(ParticipantSnapshot::getParticipantStatus, "HANDLED")
                    .set(ParticipantSnapshot::getInvalidReason, action);
            if (tenantId != null) handled.eq(ParticipantSnapshot::getTenantId, tenantId);
            mapper.update(null, handled);
        }
    }
}
