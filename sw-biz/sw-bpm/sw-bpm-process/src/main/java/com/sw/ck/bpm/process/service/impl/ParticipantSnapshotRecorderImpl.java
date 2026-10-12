package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.api.result.MutationOutcome;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ParticipantSnapshotRecorderImpl implements ParticipantSnapshotRecorder {

    private final ParticipantSnapshotMapper mapper;

    /** 运行时任务门面：按任务存活判定失效面（未装配时回退旧全量语义） */
    private ObjectProvider<BpmTaskFacade> taskFacadeProvider;

    public ParticipantSnapshotRecorderImpl(ParticipantSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Autowired(required = false)
    public void setTaskFacadeProvider(ObjectProvider<BpmTaskFacade> taskFacadeProvider) {
        this.taskFacadeProvider = taskFacadeProvider;
    }

    @Override
    @Transactional
    public Optional<MutationOutcome> record(String processInstanceId, String nodeKey, String taskId,
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
        // participantIds 为空时无候选需冻结：无行写入即语义已满足，仍为 APPLIED（非失败）
        return Optional.of(MutationOutcome.APPLIED);
    }

    @Override
    @Transactional
    public Optional<MutationOutcome> record(String processInstanceId, String nodeKey, String taskId,
                                            List<String> participantIds, Map<String, String> displayNames,
                                            Long tenantId) {
        for (String participantId : participantIds) {
            ParticipantSnapshot row = new ParticipantSnapshot();
            row.setProcessInstanceId(processInstanceId);
            row.setNodeKey(nodeKey);
            row.setTaskId(taskId);
            row.setParticipantId(participantId);
            // 冻结展示名：此后改名/停用不重写该轮次历史身份
            row.setParticipantName(displayNames == null ? null : displayNames.get(participantId));
            row.setParticipantStatus("PENDING");
            row.setTenantId(tenantId);
            mapper.insert(row);
        }
        // participantIds 为空时无候选需冻结：无行写入即语义已满足，仍为 APPLIED（非失败）
        return Optional.of(MutationOutcome.APPLIED);
    }

    @Override
    @Transactional
    public Optional<MutationOutcome> settle(String processInstanceId, String nodeKey, String taskId,
                                            String actorId, String action, Long tenantId) {
        int invalidatedRows = invalidateStalePending(processInstanceId, nodeKey, actorId, action, tenantId);

        int handledRows = 0;
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
            handledRows = mapper.update(null, handled);
        }
        // 无可结算候选（两处更新均未命中）属合法幂等，不得伪装成失败
        return Optional.of(invalidatedRows + handledRows > 0
                ? MutationOutcome.APPLIED : MutationOutcome.ALREADY_APPLIED);
    }

    /**
     * 失效面按任务存活收窄：共享任务（普通候选同 taskId）经任一候选处理后任务
     * 被引擎取消 → 其余候选失效；会签多实例子任务（ALL 语义，各持不同 taskId）
     * 在他人处理时仍存活，不得被误标无效——记账与运行时任务/会签票保持一致。
     * 引擎门面未装配（历史测试上下文）时回退旧“节点已由…处理”全量语义。
     */
    private int invalidateStalePending(String processInstanceId, String nodeKey, String actorId,
                                       String action, Long tenantId) {
        BpmTaskFacade facade = taskFacadeProvider == null ? null : taskFacadeProvider.getIfAvailable();
        if (facade == null) {
            LambdaUpdateWrapper<ParticipantSnapshot> invalidated = new LambdaUpdateWrapper<>();
            invalidated.eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId)
                    .eq(ParticipantSnapshot::getNodeKey, nodeKey)
                    .eq(ParticipantSnapshot::getParticipantStatus, "PENDING");
            if (tenantId != null) invalidated.eq(ParticipantSnapshot::getTenantId, tenantId);
            if (actorId != null) invalidated.ne(ParticipantSnapshot::getParticipantId, actorId);
            invalidated.set(ParticipantSnapshot::getParticipantStatus, "INVALIDATED")
                    .set(ParticipantSnapshot::getInvalidReason,
                            "节点已由 " + actorId + " 以 " + action + " 处理");
            return mapper.update(null, invalidated);
        }
        List<ParticipantSnapshot> pendingOthers = mapper.selectList(
                Wrappers.<ParticipantSnapshot>lambdaQuery()
                        .eq(ParticipantSnapshot::getProcessInstanceId, processInstanceId)
                        .eq(ParticipantSnapshot::getNodeKey, nodeKey)
                        .eq(ParticipantSnapshot::getParticipantStatus, "PENDING")
                        .eq(tenantId != null, ParticipantSnapshot::getTenantId, tenantId)
                        .ne(actorId != null, ParticipantSnapshot::getParticipantId, actorId));
        int invalidatedRows = 0;
        for (ParticipantSnapshot row : pendingOthers) {
            String rowTaskId = row.getTaskId();
            if (rowTaskId != null && facade.getTask(rowTaskId).isPresent()) {
                // 任务仍存活：该候选仍可处理，保持 PENDING 待其自行结算
                continue;
            }
            invalidatedRows += mapper.update(null, new LambdaUpdateWrapper<ParticipantSnapshot>()
                    .eq(ParticipantSnapshot::getId, row.getId())
                    .set(ParticipantSnapshot::getParticipantStatus, "INVALIDATED")
                    .set(ParticipantSnapshot::getInvalidReason,
                            "节点已由 " + actorId + " 以 " + action + " 处理"));
        }
        return invalidatedRows;
    }
}
