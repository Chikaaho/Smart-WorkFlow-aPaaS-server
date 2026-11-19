package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.api.common.BatchItemStatusEnum;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmCommunication;
import com.sw.ck.bpm.process.entity.BpmSignRecord;
import com.sw.ck.bpm.process.entity.BpmSignPositionEnum;
import com.sw.ck.bpm.process.mapper.BpmCommunicationMapper;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.util.List;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 任务级生命周期动作执行（自 ApprovalLifecycleServiceImpl 拆出，行为不变）：
 * 转办 / 委托 / 沟通征询 / 加签的资格校验、幂等、审计与通知。事务边界保留在
 * {@link ApprovalLifecycleServiceImpl} 门面的公开方法上。
 */
@Service
@Log4j2
class ApprovalTaskActionExecutor {

    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private ObjectProvider<TaskActionService> taskActionService;
    @Autowired
    private BpmCommunicationMapper communicationMapper;
    @Autowired
    private BpmSignRecordMapper signRecordMapper;
    @Autowired
    private ApprovalLifecycleSupport support;

    void executeTaskAction(String taskId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        BpmTaskVO task = requireTask(taskId);
        boolean assigned = String.valueOf(actor.getUserId()).equals(task.getAssignee());
        // empty = 无法判定（任务/用户标识缺失；此处任务已存在且登录用户非空）：
        // fail closed，按不可处理拒绝
        Optional<Boolean> handleDecision = bpmTaskFacade.canHandle(
                taskId, String.valueOf(actor.getUserId()));
        boolean candidate = handleDecision.isPresent() && handleDecision.get();
        if (!assigned && !candidate) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权处理该任务");
        }

        ApprovalAction action = request.getAction();
        TaskActionService nodePolicy = taskActionService.getIfAvailable();
        if (nodePolicy != null) nodePolicy.assertNodeActionAllowed(task, action);
        switch (action) {
            case TRANSFER -> doTransfer(task, actor, request);
            case DELEGATE -> doDelegate(task, actor, request);
            case COMMUNICATE -> doCommunicate(task, actor, request);
            case ADD_SIGN -> doAddSign(task, actor, request);
            default -> throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
    }

    private BpmTaskVO requireTask(String taskId) {
        // empty = 该任务不存在（原 null 返回路径）
        return bpmTaskFacade.getTask(taskId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在"));
    }

    private void doTransfer(BpmTaskVO task, LoginUser actor, ApprovalActionRequest request) {
        Long target = request.getTargetUserId();
        if (target == null) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(), "转办目标不能为空");
        }
        support.requireActiveUser(target, actor.getTenantId());
        if (target.equals(actor.getUserId())) {
            throw new BaseException(BpmErrorCode.ACTION_SELF_INVALID);
        }
        String originalAssignee = task.getAssignee();
        // 命令方法：present = APPLIED；任务不存在/已被处理继续抛原异常
        bpmTaskFacade.setAssignee(task.getTaskId(), String.valueOf(target))
        .orElseThrow(() -> new IllegalStateException(
                "BpmTaskFacade#setAssignee 契约恒 present，empty 属契约违约"));
        support.recordLifecycle(task, actor, ApprovalAction.TRANSFER, "TRANSFERRED", request,
                support.mapOf("targetUserId", target, "reason", support.nullSafe(request.getReason()),
                        "originalAssignee", originalAssignee));
        support.publishNotice(actor, task.getProcessInstanceId(), target, BpmNotifyTrigger.TASK_TRANSFERRED);
        log.info("任务已转办: taskId={}, from={} to={}", task.getTaskId(), actor.getUserId(), target);
    }

    private void doDelegate(BpmTaskVO task, LoginUser actor, ApprovalActionRequest request) {
        Long target = request.getTargetUserId();
        if (target == null) {
            throw new BaseException(BpmErrorCode.DELEGATE_RELATION_INVALID);
        }
        support.requireActiveUser(target, actor.getTenantId());
        if (target.equals(actor.getUserId())) {
            throw new BaseException(BpmErrorCode.ACTION_SELF_INVALID);
        }
        // 命令方法：present = APPLIED；任务不存在/已在委托链中继续抛原异常
        bpmTaskFacade.delegateTask(task.getTaskId(), String.valueOf(target))
        .orElseThrow(() -> new IllegalStateException(
                "BpmTaskFacade#delegateTask 契约恒 present，empty 属契约违约"));
        support.recordLifecycle(task, actor, ApprovalAction.DELEGATE, "DELEGATED", request,
                support.mapOf("targetUserId", target));
        support.publishNotice(actor, task.getProcessInstanceId(), target, BpmNotifyTrigger.TASK_DELEGATED);
        log.info("任务已委托: taskId={}, owner={}, delegatee={}",
                task.getTaskId(), actor.getUserId(), target);
    }

    private void doCommunicate(BpmTaskVO task, LoginUser actor, ApprovalActionRequest request) {
        List<Long> receivers = request.getReceivers();
        if (receivers == null || receivers.isEmpty()) {
            throw new BaseException(BpmErrorCode.COMMUNICATION_INVALID);
        }
        for (Long receiver : receivers) {
            support.requireActiveUser(receiver, actor.getTenantId());
        }
        for (Long receiver : receivers) {
            BpmCommunication existing = communicationMapper.selectOne(
                    Wrappers.<BpmCommunication>lambdaQuery()
                            .eq(BpmCommunication::getTaskId, task.getTaskId())
                            .eq(BpmCommunication::getReceiverId, receiver)
                            .ne(BpmCommunication::getStatus, BpmChildBatch.STATUS_CANCELLED));
            if (existing != null) {
                continue; // 幂等：同一任务同一接收人的沟通只发起一次
            }
            BpmCommunication comm = new BpmCommunication();
            comm.setProcessInstanceId(task.getProcessInstanceId());
            comm.setNodeKey(task.getTaskDefinitionKey());
            comm.setTaskId(task.getTaskId());
            comm.setRoundNo(1);
            comm.setInitiatorId(actor.getUserId());
            comm.setReceiverId(receiver);
            comm.setMessage(support.nullSafe(request.getMessage()));
            comm.setStatus(BatchItemStatusEnum.PENDING.getCode());
            communicationMapper.insert(comm);
            support.publishNotice(actor, task.getProcessInstanceId(), receiver,
                    BpmNotifyTrigger.TASK_COMMUNICATED);
        }
        support.recordLifecycle(task, actor, ApprovalAction.COMMUNICATE, "COMMUNICATED", request,
                support.mapOf("receivers", receivers));
        log.info("沟通征询已发起: taskId={}, receivers={}", task.getTaskId(), receivers);
    }

    private void doAddSign(BpmTaskVO task, LoginUser actor, ApprovalActionRequest request) {
        List<Long> participants = request.getParticipants();
        if (participants == null || participants.isEmpty()) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID);
        }
        for (Long participant : participants) {
            support.requireActiveUser(participant, actor.getTenantId());
            if (participant.equals(actor.getUserId())) {
                throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(), "不能向本人加签");
            }
        }
        String mode = ApprovalLifecycleSupport.MODE_SERIAL.equalsIgnoreCase(support.nullSafe(request.getMode(), ApprovalLifecycleSupport.MODE_PARALLEL))
                ? ApprovalLifecycleSupport.MODE_SERIAL : ApprovalLifecycleSupport.MODE_PARALLEL;
        String signPosition = support.nullSafe(request.getSignPosition(), BpmSignPositionEnum.BEFORE.getCode()).toUpperCase();
        if (!BpmSignPositionEnum.BEFORE.getCode().equals(signPosition) && !BpmSignPositionEnum.AFTER.getCode().equals(signPosition)) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(),
                    "加签位置只能是 BEFORE 或 AFTER");
        }
        if (BpmSignPositionEnum.AFTER.getCode().equals(signPosition)) {
            TaskActionService policy = taskActionService.getIfAvailable();
            if (policy != null) policy.assertNodeActionAllowed(task, ApprovalAction.APPROVE);
        }
        long pendingCount = signRecordMapper.selectCount(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getTaskId, task.getTaskId())
                        .eq(BpmSignRecord::getSignType, ApprovalLifecycleSupport.SIGN_TYPE_ADD)
                        .ne(BpmSignRecord::getSignStatus, BpmChildBatch.STATUS_CANCELLED));
        if (pendingCount > 0) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(),
                    "该任务已存在未结束的加签");
        }
        int seq = 0;
        for (Long participant : participants) {
            BpmSignRecord record = new BpmSignRecord();
            record.setProcessInstanceId(task.getProcessInstanceId());
            record.setNodeKey(task.getTaskDefinitionKey());
            record.setTaskId(task.getTaskId());
            record.setSignType(ApprovalLifecycleSupport.SIGN_TYPE_ADD);
            record.setModeType(mode);
            record.setOperatorId(actor.getUserId());
            record.setParticipantId(participant);
            record.setSeqNo(seq++);
            record.setSignStatus(BatchItemStatusEnum.PENDING.getCode());
            record.setDetail(support.toJson(support.mapOf(
                    "policy", support.nullSafe(request.getPolicy(), "ALL_PASS"),
                    "signPosition", signPosition,
                    "comment", support.nullSafe(request.getComment()),
                    "originalComment", support.nullSafe(request.getComment()),
                    "originalOpinionData", request.getOpinionData(),
                    "opinionFormId", request.getOpinionFormId(),
                    "opinionFormVersion", request.getOpinionFormVersion())));
            signRecordMapper.insert(record);
            support.publishNotice(actor, task.getProcessInstanceId(), participant,
                    BpmNotifyTrigger.TASK_SIGN_REQUESTED);
        }
        support.recordLifecycle(task, actor, ApprovalAction.ADD_SIGN, "SIGN_REQUESTED", request,
                support.mapOf("participants", participants, "mode", mode, "signPosition", signPosition));
        log.info("加签已发起: taskId={}, mode={}, position={}, participants={}",
                task.getTaskId(), mode, signPosition, participants);
    }
}
