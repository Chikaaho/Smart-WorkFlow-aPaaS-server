package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.entity.BpmCommunication;
import com.sw.ck.bpm.process.entity.BpmConsensusVote;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmSignRecord;
import com.sw.ck.bpm.process.entity.BpmTaskDeadline;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.entity.ParticipantSnapshot;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.bpm.process.mapper.BpmCommunicationMapper;
import com.sw.ck.bpm.process.mapper.BpmConsensusVoteMapper;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.ApprovalLifecycleService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * I3 生命周期动作统一实现。
 * <p>
 * 每个动作保证：动作资格（身份 + 任务/实例归属 + 状态门）、目标对象有效且同租户、
 * 幂等（重复请求不产生第二次业务效果）、审计（ApprovalActionRecord / 生命周期表）、
 * 通知（统一触发器，失败不反转审批状态）。
 * </p>
 */
@Service
public class ApprovalLifecycleServiceImpl implements ApprovalLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalLifecycleServiceImpl.class);

    private static final String SIGN_TYPE_ADD = "ADD_SIGN";
    private static final String SIGN_TYPE_SUPPLEMENT = "SUPPLEMENT_SIGN";
    private static final String MODE_SERIAL = "SERIAL";
    private static final String MODE_PARALLEL = "PARALLEL";

    private final BpmTaskFacade bpmTaskFacade;
    private final BpmInstanceService bpmInstanceService;
    private final BpmProcessDefService bpmProcessDefService;
    private final ApprovalActionService approvalActionService;
    private final BpmAuthorizeRuleMapper authorizeRuleMapper;
    private final BpmCommunicationMapper communicationMapper;
    private final BpmSignRecordMapper signRecordMapper;
    private final BpmTaskDeadlineMapper deadlineMapper;
    private final BpmConsensusVoteMapper consensusVoteMapper;
    private final ParticipantSnapshotMapper participantSnapshotMapper;
    private final ObjectProvider<UserQueryFacade> userQueryFacade;
    private final ObjectProvider<TaskActionService> taskActionService;
    private final ObjectProvider<DynamicBranchPort> dynamicBranchPort;
    private final DomainEventPublisher domainEventPublisher;
    private final ObjectMapper objectMapper;

    public ApprovalLifecycleServiceImpl(BpmTaskFacade bpmTaskFacade,
                                        BpmInstanceService bpmInstanceService,
                                        BpmProcessDefService bpmProcessDefService,
                                        ApprovalActionService approvalActionService,
                                        BpmAuthorizeRuleMapper authorizeRuleMapper,
                                        BpmCommunicationMapper communicationMapper,
                                        BpmSignRecordMapper signRecordMapper,
                                        BpmTaskDeadlineMapper deadlineMapper,
                                        BpmConsensusVoteMapper consensusVoteMapper,
                                        ParticipantSnapshotMapper participantSnapshotMapper,
                                        ObjectProvider<UserQueryFacade> userQueryFacade,
                                        ObjectProvider<TaskActionService> taskActionService,
                                        ObjectProvider<DynamicBranchPort> dynamicBranchPort,
                                        DomainEventPublisher domainEventPublisher,
                                        ObjectMapper objectMapper) {
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.bpmProcessDefService = bpmProcessDefService;
        this.approvalActionService = approvalActionService;
        this.authorizeRuleMapper = authorizeRuleMapper;
        this.communicationMapper = communicationMapper;
        this.signRecordMapper = signRecordMapper;
        this.deadlineMapper = deadlineMapper;
        this.consensusVoteMapper = consensusVoteMapper;
        this.participantSnapshotMapper = participantSnapshotMapper;
        this.userQueryFacade = userQueryFacade;
        this.taskActionService = taskActionService;
        this.dynamicBranchPort = dynamicBranchPort;
        this.domainEventPublisher = domainEventPublisher;
        this.objectMapper = objectMapper;
    }

    // ==================== 任务级动作 ====================

    @Override
    @Transactional
    public R<Void> executeTaskAction(String taskId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        BpmTaskDTO task = requireTask(taskId);
        boolean assigned = String.valueOf(actor.getUserId()).equals(task.getAssignee());
        boolean candidate = bpmTaskFacade.canHandle(taskId, String.valueOf(actor.getUserId()));
        if (!assigned && !candidate) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权处理该任务");
        }

        ApprovalAction action = request.getAction();
        switch (action) {
            case TRANSFER -> doTransfer(task, actor, request);
            case DELEGATE -> doDelegate(task, actor, request);
            case COMMUNICATE -> doCommunicate(task, actor, request);
            case ADD_SIGN -> doAddSign(task, actor, request);
            default -> throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        return R.ok();
    }

    private void doTransfer(BpmTaskDTO task, LoginUser actor, ApprovalActionRequest request) {
        Long target = request.getTargetUserId();
        if (target == null) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(), "转办目标不能为空");
        }
        requireActiveUser(target, actor.getTenantId());
        if (target.equals(actor.getUserId())) {
            throw new BaseException(BpmErrorCode.ACTION_SELF_INVALID);
        }
        String originalAssignee = task.getAssignee();
        bpmTaskFacade.setAssignee(task.getTaskId(), String.valueOf(target));
        recordLifecycle(task, actor, ApprovalAction.TRANSFER, "TRANSFERRED", request,
                mapOf("targetUserId", target, "reason", nullSafe(request.getReason()),
                        "originalAssignee", originalAssignee));
        publishNotice(actor, task.getProcessInstanceId(), target, BpmNotifyTrigger.TASK_TRANSFERRED);
        log.info("任务已转办: taskId={}, from={} to={}", task.getTaskId(), actor.getUserId(), target);
    }

    private void doDelegate(BpmTaskDTO task, LoginUser actor, ApprovalActionRequest request) {
        Long target = request.getTargetUserId();
        if (target == null) {
            throw new BaseException(BpmErrorCode.DELEGATE_RELATION_INVALID);
        }
        requireActiveUser(target, actor.getTenantId());
        if (target.equals(actor.getUserId())) {
            throw new BaseException(BpmErrorCode.ACTION_SELF_INVALID);
        }
        bpmTaskFacade.delegateTask(task.getTaskId(), String.valueOf(target));
        recordLifecycle(task, actor, ApprovalAction.DELEGATE, "DELEGATED", request,
                mapOf("targetUserId", target));
        publishNotice(actor, task.getProcessInstanceId(), target, BpmNotifyTrigger.TASK_DELEGATED);
        log.info("任务已委托: taskId={}, owner={}, delegatee={}",
                task.getTaskId(), actor.getUserId(), target);
    }

    private void doCommunicate(BpmTaskDTO task, LoginUser actor, ApprovalActionRequest request) {
        List<Long> receivers = request.getReceivers();
        if (receivers == null || receivers.isEmpty()) {
            throw new BaseException(BpmErrorCode.COMMUNICATION_INVALID);
        }
        for (Long receiver : receivers) {
            requireActiveUser(receiver, actor.getTenantId());
        }
        for (Long receiver : receivers) {
            BpmCommunication existing = communicationMapper.selectOne(
                    Wrappers.<BpmCommunication>lambdaQuery()
                            .eq(BpmCommunication::getTaskId, task.getTaskId())
                            .eq(BpmCommunication::getReceiverId, receiver)
                            .ne(BpmCommunication::getStatus, "CANCELLED"));
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
            comm.setMessage(nullSafe(request.getMessage()));
            comm.setStatus("PENDING");
            communicationMapper.insert(comm);
            publishNotice(actor, task.getProcessInstanceId(), receiver,
                    BpmNotifyTrigger.TASK_COMMUNICATED);
        }
        recordLifecycle(task, actor, ApprovalAction.COMMUNICATE, "COMMUNICATED", request,
                mapOf("receivers", receivers));
        log.info("沟通征询已发起: taskId={}, receivers={}", task.getTaskId(), receivers);
    }

    private void doAddSign(BpmTaskDTO task, LoginUser actor, ApprovalActionRequest request) {
        List<Long> participants = request.getParticipants();
        if (participants == null || participants.isEmpty()) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID);
        }
        for (Long participant : participants) {
            requireActiveUser(participant, actor.getTenantId());
            if (participant.equals(actor.getUserId())) {
                throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(), "不能向本人加签");
            }
        }
        String mode = MODE_SERIAL.equalsIgnoreCase(nullSafe(request.getMode(), MODE_PARALLEL))
                ? MODE_SERIAL : MODE_PARALLEL;
        long pendingCount = signRecordMapper.selectCount(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getTaskId, task.getTaskId())
                        .eq(BpmSignRecord::getSignType, SIGN_TYPE_ADD)
                        .ne(BpmSignRecord::getSignStatus, "CANCELLED"));
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
            record.setSignType(SIGN_TYPE_ADD);
            record.setModeType(mode);
            record.setOperatorId(actor.getUserId());
            record.setParticipantId(participant);
            record.setSeqNo(seq++);
            record.setSignStatus("PENDING");
            record.setDetail(toJson(mapOf("policy", nullSafe(request.getPolicy(), "ALL_PASS"),
                    "comment", nullSafe(request.getComment()))));
            signRecordMapper.insert(record);
            publishNotice(actor, task.getProcessInstanceId(), participant,
                    BpmNotifyTrigger.TASK_SIGN_REQUESTED);
        }
        recordLifecycle(task, actor, ApprovalAction.ADD_SIGN, "SIGN_REQUESTED", request,
                mapOf("participants", participants, "mode", mode));
        log.info("加签已发起: taskId={}, mode={}, participants={}", task.getTaskId(), mode, participants);
    }

    // ==================== 实例级动作 ====================

    @Override
    @Transactional
    public R<Void> withdraw(String processInstanceId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmInstance instance = requireInstance(processInstanceId);
        if (!instance.getInitiatorId().equals(actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅发起人可撤回");
        }
        if (!InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            if (InstanceStatusEnum.WITHDRAWN.getCode().equals(instance.getStatus())) {
                return R.ok(); // 幂等：已撤回重复请求
            }
            throw new BaseException(BpmErrorCode.WITHDRAW_NOT_PERMITTED);
        }
        // 撤回边界：本实例已有任何人工办理记录即越过不可撤回边界
        long handled = approvalActionService.findByProcessInstanceId(processInstanceId).size();
        if (handled > 0) {
            throw new BaseException(BpmErrorCode.WITHDRAW_NOT_PERMITTED);
        }
        bpmTaskFacade.terminateProcess(processInstanceId, "WITHDRAWN");
        bpmInstanceService.updateStatus(processInstanceId,
                InstanceStatusEnum.WITHDRAWN.getCode());
        closeDeadlines(processInstanceId, "撤回关闭");
        recordInstance(instance, actor, ApprovalAction.WITHDRAW, "WITHDRAWN",
                mapOf("reason", nullSafe(request.getReason())));
        publishNotice(actor, processInstanceId, instance.getInitiatorId(),
                BpmNotifyTrigger.PROCESS_WITHDRAWN);
        log.info("实例已撤回: processInstanceId={}, actor={}", processInstanceId, actor.getUserId());
        return R.ok();
    }

    @Override
    @Transactional
    public R<Void> discard(String processInstanceId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmInstance instance = requireInstance(processInstanceId);
        if (!InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            if (InstanceStatusEnum.DISCARDED.getCode().equals(instance.getStatus())) {
                return R.ok(); // 幂等
            }
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        bpmTaskFacade.terminateProcess(processInstanceId, "DISCARDED");
        bpmInstanceService.updateStatus(processInstanceId,
                InstanceStatusEnum.DISCARDED.getCode());
        closeDeadlines(processInstanceId, "废弃关闭");
        recordInstance(instance, actor, ApprovalAction.DISCARD, "DISCARDED",
                mapOf("reason", nullSafe(request.getReason()),
                        "beforeStatus", instance.getStatus()));
        publishNotice(actor, processInstanceId, instance.getInitiatorId(),
                BpmNotifyTrigger.PROCESS_DISCARDED);
        log.info("实例已废弃: processInstanceId={}, operator={}", processInstanceId, actor.getUserId());
        return R.ok();
    }

    // ==================== 加签/补签表态 ====================

    @Override
    @Transactional
    public R<Void> expressSign(Long recordId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmSignRecord record = signRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BaseException(BpmErrorCode.SIGN_RECORD_NOT_FOUND);
        }
        if (!record.getParticipantId().equals(actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅追加人本人可表态");
        }
        if ("DONE".equals(record.getSignStatus())) {
            return R.ok(); // 幂等
        }
        if (!"PENDING".equals(record.getSignStatus())) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        if (MODE_SERIAL.equals(record.getModeType())
                && SIGN_TYPE_ADD.equals(record.getSignType())) {
            long before = signRecordMapper.selectCount(
                    Wrappers.<BpmSignRecord>lambdaQuery()
                            .eq(BpmSignRecord::getTaskId, record.getTaskId())
                            .eq(BpmSignRecord::getSignType, SIGN_TYPE_ADD)
                            .lt(BpmSignRecord::getSeqNo, record.getSeqNo())
                            .ne(BpmSignRecord::getSignStatus, "DONE"));
            if (before > 0) {
                throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(),
                        "串行加签存在前置未表态记录");
            }
        }
        String result = request.getAction() == ApprovalAction.DISAPPROVE
                ? "DISAPPROVE" : "APPROVE";
        BpmSignRecord patch = new BpmSignRecord();
        patch.setId(record.getId());
        patch.setVersion(record.getVersion());
        patch.setSignStatus("DONE");
        patch.setResultStatus(result);
        patch.setDetail(toJson(mergeDetails(record.getDetail(), mapOf(
                "result", result, "opinionData", request.getOpinionData(),
                "actor", actor.getUserId()))));
        signRecordMapper.updateById(patch);

        // 补签记录的 taskId 是 "SUPPLEMENT#<pid>"，不是 Flowable 任务——用签名记录自身的
        // 实例/节点/记录 ID 构造生命周期任务信息，保证补签表态同样落 ApprovalActionRecord
        // 与意见表单快照（Z5/Z8：SUPPLEMENT_SIGN 行不得缺快照）。
        BpmTaskDTO lifecycleTask = bpmTaskFacade.getTask(record.getTaskId());
        if (lifecycleTask == null) {
            lifecycleTask = new BpmTaskDTO();
            lifecycleTask.setTaskId(record.getTaskId());
            lifecycleTask.setProcessInstanceId(record.getProcessInstanceId());
            lifecycleTask.setTaskDefinitionKey(nullSafe(record.getNodeKey(), "SUPPLEMENT"));
        }
        recordLifecycle(lifecycleTask, actor,
                SIGN_TYPE_ADD.equals(record.getSignType())
                        ? ApprovalAction.ADD_SIGN : ApprovalAction.SUPPLEMENT_SIGN,
                "SIGN_" + result, request, mapOf("recordId", record.getId()));

        if (SIGN_TYPE_ADD.equals(record.getSignType())) {
            trySettleOriginalTask(record);
        }
        return R.ok();
    }

    @Override
    @Transactional
    public R<Void> cancelSign(Long recordId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmSignRecord record = signRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BaseException(BpmErrorCode.SIGN_RECORD_NOT_FOUND);
        }
        boolean operator = record.getOperatorId().equals(actor.getUserId());
        boolean participant = record.getParticipantId().equals(actor.getUserId());
        if (!operator && !participant) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "仅操作人/本人可取消待处理加签");
        }
        if (!"PENDING".equals(record.getSignStatus())) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        BpmSignRecord patch = new BpmSignRecord();
        patch.setId(record.getId());
        patch.setVersion(record.getVersion());
        patch.setSignStatus("CANCELLED");
        patch.setCancelReason(nullSafe(request.getReason(), "操作人取消"));
        signRecordMapper.updateById(patch);
        recordLifecycle(bpmTaskFacade.getTask(record.getTaskId()), actor,
                SIGN_TYPE_ADD.equals(record.getSignType())
                        ? ApprovalAction.ADD_SIGN : ApprovalAction.SUPPLEMENT_SIGN,
                "SIGN_CANCELLED", request, mapOf("recordId", record.getId()));
        return R.ok();
    }

    /** 加签全部表态完成后按策略结算原任务（AUTO_FINISH 直接受控完成）。 */
    private void trySettleOriginalTask(BpmSignRecord settled) {
        List<BpmSignRecord> all = signRecordMapper.selectList(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getTaskId, settled.getTaskId())
                        .ne(BpmSignRecord::getSignStatus, "CANCELLED"));
        boolean allDone = all.stream().allMatch(item -> "DONE".equals(item.getSignStatus()));
        if (!allDone) {
            return;
        }
        String policy = extractDetailField(all.get(0).getDetail(), "policy");
        boolean negative;
        if ("ALL_PASS".equals(policy)) {
            negative = all.stream().anyMatch(item -> "DISAPPROVE".equals(item.getResultStatus()));
        } else {
            negative = all.stream().noneMatch(item -> "APPROVE".equals(item.getResultStatus()));
        }
        BpmTaskDTO task = bpmTaskFacade.getTask(settled.getTaskId());
        if (task == null) {
            log.warn("原任务已消失，跳过加签结算: taskId={}", settled.getTaskId());
            return;
        }
        boolean autoFinish = "AUTO_FINISH".equals(policy);
        if (!autoFinish) {
            log.info("加签表决完成（原待办保留，由原责任人办理）: taskId={}, negative={}",
                    settled.getTaskId(), negative);
            return;
        }
        LoginUser synthetic = new LoginUser();
        synthetic.setUserId(all.get(0).getOperatorId());
        synthetic.setTenantId(all.get(0).getTenantId() == null ? 0L : all.get(0).getTenantId());
        LoginUserHolder.set(synthetic);
        try {
            ApprovalActionRequest settleRequest = new ApprovalActionRequest();
            settleRequest.setAction(negative ? ApprovalAction.DISAPPROVE : ApprovalAction.APPROVE);
            settleRequest.setOpinionData(mapOf("source", "ADD_SIGN"));
            taskActionService.getObject().execute(task.getTaskId(), settleRequest);
        } finally {
            LoginUserHolder.clear();
        }
        log.info("加签受控完成: taskId={}, negative={}", settled.getTaskId(), negative);
    }

    // ==================== 沟通回复 ====================

    @Override
    @Transactional
    public R<Void> replyCommunication(Long communicationId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmCommunication comm = communicationMapper.selectById(communicationId);
        if (comm == null) {
            throw new BaseException(BpmErrorCode.COMMUNICATION_INVALID);
        }
        if (!comm.getReceiverId().equals(actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅沟通接收人可回复");
        }
        if ("REPLIED".equals(comm.getStatus())) {
            return R.ok(); // 幂等
        }
        BpmCommunication patch = new BpmCommunication();
        patch.setId(comm.getId());
        patch.setStatus("REPLIED");
        patch.setReplyMessage(nullSafe(request.getMessage()));
        patch.setReplyTime(LocalDateTime.now());
        communicationMapper.updateById(patch);
        log.info("沟通已回复: communicationId={}, receiver={}", communicationId, actor.getUserId());
        return R.ok();
    }

    // ==================== 授权代理规则 ====================

    @Override
    @Transactional
    public R<Long> saveAuthorizeRule(ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        Long principal = actor.getUserId();
        Long agent = request.getTargetUserId();
        if (agent == null || principal.equals(agent)) {
            throw new BaseException(BpmErrorCode.ACTION_SELF_INVALID);
        }
        requireActiveUser(agent, actor.getTenantId());
        LocalDateTime start = parseTime(request.getStartAt(),
                BpmErrorCode.AUTHORIZATION_INVALID);
        LocalDateTime end = parseTime(request.getEndAt(), BpmErrorCode.AUTHORIZATION_INVALID);
        if (start != null && end != null && !start.isBefore(end)) {
            throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(), "生效区间不合法");
        }
        String scopeType = nullSafe(request.getScopeType(), "GLOBAL");
        if (!List.of("GLOBAL", "PROCESS", "NODE", "BUSINESS").contains(scopeType)) {
            throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(), "范围类型不合法");
        }

        // 冲突拒绝：同一 principal + 同范围 + 重叠生效期的 ACTIVE 规则只保留一条
        List<BpmAuthorizeRule> existing = authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, principal)
                        .eq(BpmAuthorizeRule::getAgentId, agent)
                        .eq(BpmAuthorizeRule::getScopeType, scopeType)
                        .eq(BpmAuthorizeRule::getStatus, "ACTIVE"));
        for (BpmAuthorizeRule item : existing) {
            if (overlaps(item.getStartAt(), item.getEndAt(), start, end)) {
                throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(),
                        "存在冲突的生效期间规则");
            }
        }
        // 循环代理拒绝：agent 的生效规则又指回 principal（A→B、B→A）
        List<BpmAuthorizeRule> agentRules = authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, agent)
                        .eq(BpmAuthorizeRule::getStatus, "ACTIVE"));
        for (BpmAuthorizeRule item : agentRules) {
            if (principal.equals(item.getAgentId())) {
                throw new BaseException(BpmErrorCode.AUTHORIZATION_INVALID.getCode(),
                        "授权形成循环代理");
            }
        }

        BpmAuthorizeRule rule = new BpmAuthorizeRule();
        rule.setPrincipalId(principal);
        rule.setAgentId(agent);
        rule.setScopeType(scopeType);
        rule.setProcessDefKey(blankToNull(request.getProcessDefKey()));
        rule.setNodeKey(blankToNull(request.getNodeKey()));
        rule.setBusinessKey(blankToNull(request.getBusinessKey()));
        rule.setConditionJson(request.getConditionJson() == null ? "{}" : request.getConditionJson());
        rule.setStartAt(start);
        rule.setEndAt(end);
        rule.setStatus("ACTIVE");
        rule.setDetail(nullSafe(request.getReason()));
        authorizeRuleMapper.insert(rule);
        log.info("代理规则已创建: principal={}, agent={}, scope={}", principal, agent, scopeType);
        return R.ok(rule.getId());
    }

    @Override
    @Transactional
    public R<Void> revokeAuthorizeRule(Long ruleId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmAuthorizeRule rule = authorizeRuleMapper.selectById(ruleId);
        if (rule == null) {
            return R.ok();
        }
        if (!rule.getPrincipalId().equals(actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅授权主体可撤销");
        }
        BpmAuthorizeRule patch = new BpmAuthorizeRule();
        patch.setId(rule.getId());
        patch.setStatus("REVOKED");
        authorizeRuleMapper.updateById(patch);
        log.info("代理规则已撤销: ruleId={}", ruleId);
        return R.ok();
    }

    @Override
    public List<BpmAuthorizeRule> listAuthorizeRules() {
        LoginUser actor = LoginUserHolder.get();
        if (actor == null) {
            return List.of();
        }
        return authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, actor.getUserId())
                        .orderByDesc(BpmAuthorizeRule::getUpdateTime));
    }

    // ==================== 引擎端口适配 ====================


    private String resolveProxy(Long tenantId, String processInstanceId, String nodeKey,
                                String taskId, List<String> resolvedUsers) {
        if (resolvedUsers == null || resolvedUsers.size() != 1) {
            return null;
        }
        String principalRaw = resolvedUsers.get(0);
        if (principalRaw == null || !principalRaw.matches("\\d+")) {
            return null;
        }
        Long principal = Long.valueOf(principalRaw);
        List<BpmAuthorizeRule> candidates = authorizeRuleMapper.selectList(
                Wrappers.<BpmAuthorizeRule>lambdaQuery()
                        .eq(BpmAuthorizeRule::getPrincipalId, principal)
                        .eq(BpmAuthorizeRule::getStatus, "ACTIVE"));
        BpmProcessDef def = findDefByInstance(processInstanceId);
        String businessKey = bpmTaskFacade.getBusinessKey(processInstanceId);
        List<BpmAuthorizeRule> matched = candidates.stream()
                .filter(this::inEffect)
                .filter(rule -> scopeMatches(rule, def == null ? null : def.getProcessKey(),
                        nodeKey, businessKey))
                .toList();
        if (matched.size() != 1) {
            if (matched.size() > 1) {
                log.warn("命中多条互斥代理规则，代理不生效（冲突拒绝）: principal={}, nodeKey={}",
                        principal, nodeKey);
            }
            return null;
        }
        BpmAuthorizeRule rule = matched.get(0);
        recordProxyJoining(processInstanceId, nodeKey, taskId, principal, rule.getAgentId(), tenantId);
        return String.valueOf(rule.getAgentId());
    }

    /** 代理接管审计：任务进入时记录 AUTHORIZE 动作与代理关系。 */
    private void recordProxyJoining(String processInstanceId, String nodeKey, String taskId,
                                    Long principal, Long agent, Long tenantId) {
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(processInstanceId);
        record.setNodeKey(nodeKey == null ? "UNKNOWN" : nodeKey);
        record.setTaskId(taskId);
        record.setActorId(agent);
        record.setProxyForUserId(principal);
        record.setAction(ApprovalAction.AUTHORIZE.name());
        record.setDetail(toJson(mapOf("event", "PROXY_TAKEOVER")));
        record.setSettlementStatus("PROXY_JOINED");
        if (tenantId == null) {
            // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
            throw new IllegalArgumentException("代理接管审计缺少租户上下文: processInstanceId=" + processInstanceId);
        }
        record.setTenantId(tenantId);
        approvalActionService.save(record);
    }

    private boolean inEffect(BpmAuthorizeRule rule) {
        LocalDateTime now = LocalDateTime.now();
        if (rule.getStartAt() != null && rule.getStartAt().isAfter(now)) {
            return false;
        }
        return rule.getEndAt() == null || rule.getEndAt().isAfter(now);
    }

    private boolean scopeMatches(BpmAuthorizeRule rule, String processDefKey,
                                 String nodeKey, String businessKey) {
        return switch (nullSafe(rule.getScopeType(), "GLOBAL")) {
            case "GLOBAL" -> true;
            case "PROCESS" -> processDefKey != null
                    && processDefKey.equals(rule.getProcessDefKey());
            case "NODE" -> processDefKey != null && processDefKey.equals(rule.getProcessDefKey())
                    && nodeKey != null && nodeKey.equals(rule.getNodeKey());
            case "BUSINESS" -> businessKey != null
                    && businessKey.equals(rule.getBusinessKey());
            default -> false;
        };
    }

    private void registerDeadline(Long tenantId, String processInstanceId, String nodeKey,
                                  String taskId, String nodeConfig) {
        if (nodeConfig == null || nodeConfig.isBlank()) {
            return;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(nodeConfig, Map.class);
            Object deadline = config.get("deadline");
            if (!(deadline instanceof Map)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> deadlineMap = (Map<String, Object>) deadline;
            LocalDateTime due = parseTimeSilently(String.valueOf(deadlineMap.get("dueAt")));
            if (due == null && deadlineMap.get("dueMinutes") instanceof Number minutes) {
                due = LocalDateTime.now().plusMinutes(minutes.longValue());
            }
            if (due == null) {
                throw new BaseException(BpmErrorCode.AUTO_ACTION_INVALID);
            }
            Object auto = deadlineMap.get("autoAction");
            if (auto != null
                    && !List.of("APPROVE", "DISAPPROVE", "TRANSFER").contains(String.valueOf(auto))) {
                throw new BaseException(BpmErrorCode.AUTO_ACTION_INVALID);
            }
            BpmTaskDeadline record = new BpmTaskDeadline();
            record.setProcessInstanceId(processInstanceId);
            record.setNodeKey(nodeKey);
            record.setTaskId(taskId);
            record.setDueAt(due);
            record.setRemindFired(0);
            record.setEscalationFired(0);
            record.setAutoAction(auto == null ? null : String.valueOf(auto));
            record.setAutoActionConfig(deadlineMap.get("autoActionConfig") == null
                    ? "{}" : toJson(deadlineMap.get("autoActionConfig")));
            record.setRunState("PENDING");
            if (tenantId == null) {
                // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
                throw new IllegalArgumentException("办理时限登记缺少租户上下文: taskId=" + taskId);
            }
            record.setTenantId(tenantId);
            deadlineMapper.insert(record);
            log.info("办理时限已登记: taskId={}, dueAt={}, autoAction={}", taskId, due, auto);
        } catch (BaseException e) {
            throw e;
        } catch (Exception e) {
            // 时限登记失败不阻断任务创建；无时限则不扫描
            log.warn("时限登记失败（忽略）: taskId={}, error={}", taskId, e.getMessage());
        }
    }

    private void closeDeadlines(String processInstanceId, String reason) {
        List<BpmTaskDeadline> pending = deadlineMapper.selectList(
                Wrappers.<BpmTaskDeadline>lambdaQuery()
                        .eq(BpmTaskDeadline::getProcessInstanceId, processInstanceId)
                        .eq(BpmTaskDeadline::getRunState, "PENDING"));
        for (BpmTaskDeadline record : pending) {
            BpmTaskDeadline patch = new BpmTaskDeadline();
            patch.setId(record.getId());
            patch.setRunState("CANCELLED");
            patch.setLastReason(reason);
            patch.setHandledAt(LocalDateTime.now());
            deadlineMapper.updateById(patch);
        }
    }

    @Override
    @Transactional
    public void settleConsensusNegative(String tenantId, String processInstanceId,
                                        String nodeKey, String reason) {
        Optional<BpmInstance> found = bpmInstanceService
                .findByProcessInstanceId(processInstanceId);
        BpmInstance instance = found.orElse(null);
        if (instance == null
                || !InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            return; // 幂等：已终局
        }
        bpmTaskFacade.terminateProcess(processInstanceId, "CONSENSUS_REJECTED");
        bpmInstanceService.updateStatus(processInstanceId,
                InstanceStatusEnum.REJECTED.getCode());
        closeDeadlines(processInstanceId, "会签负向结算关闭");
        DynamicBranchPort branchPort = dynamicBranchPort == null ? null : dynamicBranchPort.getIfAvailable();
        if (branchPort != null) {
            branchPort.closeRemaining(tenantId, processInstanceId, nodeKey, "CONSENSUS_NEGATIVE_SETTLED");
        }
        if (tenantId == null) {
            // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
            throw new IllegalArgumentException("会签负向结算缺少租户上下文: processInstanceId=" + processInstanceId);
        }
        Long tenantLong = Long.valueOf(tenantId);
        recordInstance(instance, syntheticLogin(tenantLong), ApprovalAction.DISAPPROVE,
                "CONSENSUS_SETTLED", mapOf("reason", nullSafe(reason, "会签负向结算"),
                        "nodeKey", nodeKey));
        publishNotice(syntheticLogin(tenantLong), processInstanceId, instance.getInitiatorId(),
                BpmNotifyTrigger.PROCESS_REJECTED);
        log.info("会签负向结算完成: processInstanceId={}, nodeKey={}", processInstanceId, nodeKey);
    }

    @Override
    public void assertNoPendingSign(String taskId) {
        long pending = signRecordMapper.selectCount(
                Wrappers.<BpmSignRecord>lambdaQuery()
                        .eq(BpmSignRecord::getTaskId, taskId)
                        .eq(BpmSignRecord::getSignType, SIGN_TYPE_ADD)
                        .eq(BpmSignRecord::getSignStatus, "PENDING"));
        if (pending > 0) {
            throw new BaseException(BpmErrorCode.ADD_SIGN_INVALID.getCode(),
                    "存在待处理加签，不能直接办理");
        }
    }

    @Override
    public com.sw.ck.bpm.api.participant.ConsensusVotePort consensusVotePort() {
        return new com.sw.ck.bpm.api.participant.ConsensusVotePort() {
            @Override
            public boolean record(String tenantId, String processInstanceId, String nodeKey,
                                  String taskId, String actorId, String outcome) {
                try {
                    Long actor = Long.valueOf(actorId);
                    if (tenantId == null) {
                        // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed
                        throw new IllegalArgumentException("会签投票缺少租户上下文: taskId=" + taskId);
                    }
                    Long tenant = Long.valueOf(tenantId);
                    if (consensusVoteMapper.selectCount(
                            Wrappers.<BpmConsensusVote>lambdaQuery()
                                    .eq(BpmConsensusVote::getTaskId, taskId)
                                    .eq(BpmConsensusVote::getActorId, actor)) > 0) {
                        return false; // 幂等：同任务同人一票
                    }
                    BpmConsensusVote vote = new BpmConsensusVote();
                    vote.setProcessInstanceId(processInstanceId);
                    vote.setNodeKey(nodeKey);
                    vote.setTaskId(taskId);
                    vote.setActorId(actor);
                    vote.setOutcome(outcome);
                    vote.setTenantId(tenant);
                    // 引擎监听线程无登录态：审计列显式兜底，不允许拦截器缺省失败
                    if (vote.getCreateTime() == null) {
                        java.time.LocalDateTime now = java.time.LocalDateTime.now();
                        vote.setCreateTime(now);
                        vote.setUpdateTime(now);
                    }
                    if (vote.getDeleted() == null) {
                        vote.setDeleted(0);
                    }
                    if (vote.getVersion() == null) {
                        vote.setVersion(0L);
                    }
                    return consensusVoteMapper.insert(vote) > 0;
                } catch (DuplicateKeyException duplicate) {
                    // 唯一键 uk_sw_bpm_vote_task_actor 兜底并发（多实例/重复命令）
                    return false;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            @Override
            public long count(String tenantId, String processInstanceId, String nodeKey,
                              String outcome) {
                return consensusVoteMapper.selectCount(
                        Wrappers.<BpmConsensusVote>lambdaQuery()
                                .eq(BpmConsensusVote::getProcessInstanceId, processInstanceId)
                                .eq(BpmConsensusVote::getNodeKey, nodeKey)
                                .eq(BpmConsensusVote::getOutcome, outcome));
            }

            @Override
            public long total(String tenantId, String processInstanceId, String nodeKey) {
                try {
                    QueryWrapper<ParticipantSnapshot> qw = new QueryWrapper<>();
                    qw.select("COUNT(DISTINCT participant_id) AS total")
                            .eq("process_instance_id", processInstanceId)
                            .eq("node_key", nodeKey)
                            .eq("deleted", 0);
                    java.util.List<java.util.Map<String, Object>> rows =
                            participantSnapshotMapper.selectMaps(qw);
                    Object total = rows.isEmpty() ? null : rows.get(0).get("total");
                    return total instanceof Number number ? number.longValue() : -1L;
                } catch (RuntimeException e) {
                    return -1L; // 端口不可用：调用方回退变量口径
                }
            }
        };
    }


    @Override
    public com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort lifecycleTaskEntryPort() {
        return new com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort() {
            @Override
            public String onTaskCreate(Long tenantId, String processInstanceId, String nodeKey,
                                       String taskId, java.util.List<String> resolvedUsers,
                                       String nodeConfig) {
                String proxyAgent = resolveProxy(tenantId, processInstanceId, nodeKey, taskId,
                        resolvedUsers);
                registerDeadline(tenantId, processInstanceId, nodeKey, taskId, nodeConfig);
                return proxyAgent;
            }

            @Override
            public java.util.List<String> resolveParticipantsByFunction(Long tenantId,
                                                                        String processInstanceId,
                                                                        String nodeKey, String taskId,
                                                                        java.util.Map<String, Object> variables,
                                                                        String nodeConfig) {
                return null; // 函数型参与人由配置适配器直连实现（见 BpmLifecyclePortConfiguration）
            }
        };
    }

    // ==================== 内部工具 ====================

    private BpmTaskDTO requireTask(String taskId) {
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId);
        if (task == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }
        return task;
    }

    private BpmInstance requireInstance(String processInstanceId) {
        return bpmInstanceService.findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(),
                        "流程实例不存在"));
    }

    private void requireActiveUser(Long userId, Long tenantId) {
        UserQueryFacade facade = userQueryFacade.getIfAvailable();
        if (facade == null) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(), "用户权威不可用");
        }
        List<Long> active = facade.findActiveUserIds(List.of(userId), tenantId);
        if (active == null || !active.contains(userId)) {
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED.getCode(),
                    "目标人员无效或不属于当前租户: " + userId);
        }
    }

    private void recordLifecycle(BpmTaskDTO task, LoginUser actor, ApprovalAction action,
                                 String settlement, ApprovalActionRequest request,
                                 Map<String, Object> detail) {
        if (task == null) {
            return;
        }
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(task.getProcessInstanceId());
        record.setNodeKey(task.getTaskDefinitionKey() == null
                ? task.getTaskId() : task.getTaskDefinitionKey());
        record.setTaskId(task.getTaskId());
        record.setActorId(actor.getUserId());
        record.setAction(action.name());
        if (request.getTargetUserId() != null) {
            record.setTargetUserId(request.getTargetUserId());
        }
        if (request.getOpinionFormId() != null) {
            record.setOpinionFormId(request.getOpinionFormId());
            record.setOpinionFormVersion(request.getOpinionFormVersion());
        }
        try {
            record.setOpinionData(objectMapper.writeValueAsString(
                    request.getOpinionData() == null ? Map.of() : request.getOpinionData()));
            record.setDetail(objectMapper.writeValueAsString(detail == null ? Map.of() : detail));
            // I3 意见表单不可变快照：生命周期动作（加签/补签表态等）执行意见表单时
            // 与主审批同口径落快照，保证五类轮次的历史回显不漂移（§4.10）。
            if (request.getOpinionFormId() != null) {
                Map<String, Object> opinionSnapshot = new java.util.LinkedHashMap<>();
                opinionSnapshot.put("opinionForm", taskActionService.getObject().resolveOpinionForm(task));
                opinionSnapshot.put("opinionFormId", request.getOpinionFormId());
                opinionSnapshot.put("opinionFormVersion", request.getOpinionFormVersion());
                record.setOpinionFormSnapshot(objectMapper.writeValueAsString(opinionSnapshot));
            }
        } catch (Exception e) {
            throw new BaseException(BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        record.setSettlementStatus(settlement);
        record.setTenantId(actor.getTenantId());
        // V74 唯一键 (tenant, task, actor, action) 幂等写入：加签取消等复用同键动作行，
        // 以最新结算状态覆盖，不产生第二行也不触发唯一键冲突
        approvalActionService.upsertDuplicate(record);
    }

    private void recordInstance(BpmInstance instance, LoginUser actor, ApprovalAction action,
                                String settlement, Map<String, Object> detail) {
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(instance.getProcessInstanceId());
        record.setNodeKey("INSTANCE");
        record.setTaskId("ACTION#" + instance.getProcessInstanceId());
        record.setActorId(actor.getUserId());
        record.setAction(action.name());
        try {
            record.setDetail(objectMapper.writeValueAsString(detail));
        } catch (Exception e) {
            record.setDetail("{}");
        }
        record.setSettlementStatus(settlement);
        record.setTenantId(instance.getTenantId() == null
                ? actor.getTenantId() : instance.getTenantId());
        approvalActionService.save(record);
    }

    private void publishNotice(LoginUser actor, String processInstanceId, Long recipient,
                               BpmNotifyTrigger trigger) {
        try {
            if (actor == null || actor.getTenantId() == null) {
                // 通知事件租户缺失属异常路径，fail closed 不落租户 0
                throw new IllegalArgumentException("通知事件缺少租户上下文: processInstanceId=" + processInstanceId);
            }
            domainEventPublisher.publish(new BpmNotifyEvent(
                    trigger, recipient,
                    actor.getTenantId(),
                    actor.getUserId(),
                    processInstanceId));
        } catch (Exception e) {
            log.warn("通知事件发布失败（审批状态不回滚）: trigger={}, error={}", trigger, e.getMessage());
        }
    }

    private LoginUser syntheticLogin(Long tenantId) {
        LoginUser synthetic = new LoginUser();
        synthetic.setUserId(0L);
        if (tenantId == null) {
            // 合成身份仅用于引擎线程结算/通知；租户缺失属异常路径，fail closed
            throw new IllegalArgumentException("合成身份缺少租户上下文");
        }
        synthetic.setTenantId(tenantId);
        return synthetic;
    }

    private BpmProcessDef findDefByInstance(String processInstanceId) {
        BpmInstance instance = bpmInstanceService
                .findByProcessInstanceId(processInstanceId).orElse(null);
        if (instance == null || instance.getProcessDefKey() == null) {
            return null;
        }
        return bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
    }

    private boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd,
                             LocalDateTime bStart, LocalDateTime bEnd) {
        LocalDateTime as = aStart == null ? LocalDateTime.MIN : aStart;
        LocalDateTime ae = aEnd == null ? LocalDateTime.MAX : aEnd;
        LocalDateTime bs = bStart == null ? LocalDateTime.MIN : bStart;
        LocalDateTime be = bEnd == null ? LocalDateTime.MAX : bEnd;
        return bs.isBefore(ae) && as.isBefore(be);
    }

    private LocalDateTime parseTime(String raw, BpmErrorCode error) {
        LocalDateTime result = parseTimeSilently(raw);
        if (raw != null && !raw.isBlank() && result == null
                && !"null".equalsIgnoreCase(raw.trim())) {
            throw new BaseException(error.getCode(), "时间格式不合法: " + raw);
        }
        return result;
    }

    private LocalDateTime parseTimeSilently(String raw) {
        if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String nullSafe(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank()
                ? fallback : String.valueOf(value);
    }

    private String nullSafe(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private Map<String, Object> mergeDetails(String raw, Map<String, Object> extra) {
        try {
            Map<String, Object> merged = raw == null || raw.isBlank()
                    ? new LinkedHashMap<>()
                    : objectMapper.readValue(raw, Map.class);
            merged.putAll(extra);
            return merged;
        } catch (Exception e) {
            return new LinkedHashMap<>(extra);
        }
    }

    private String extractDetailField(String detail, String key) {
        try {
            if (detail == null || detail.isBlank()) {
                return null;
            }
            Object value = objectMapper.readValue(detail, Map.class).get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }
}
