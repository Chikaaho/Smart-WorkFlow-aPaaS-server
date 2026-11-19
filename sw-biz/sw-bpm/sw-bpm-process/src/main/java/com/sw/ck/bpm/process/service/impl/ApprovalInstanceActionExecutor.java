package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmTaskDeadline;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.api.common.BatchItemStatusEnum;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.ChildOrchestrationService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 实例级生命周期动作执行（自 ApprovalLifecycleServiceImpl 拆出，行为不变）：
 * 发起人撤回、废弃、会签负向终局结算，及撤回/废弃终态化的子流程编排钩子与
 * 未决办理时限关闭。事务边界保留在 {@link ApprovalLifecycleServiceImpl}
 * 门面的公开方法上。
 */
@Service
@Log4j2
class ApprovalInstanceActionExecutor {

    @Autowired
    private BpmTaskFacade bpmTaskFacade;
    @Autowired
    private BpmInstanceService bpmInstanceService;
    @Autowired
    private ApprovalActionService approvalActionService;
    @Autowired
    private BpmTaskDeadlineMapper deadlineMapper;

    /** P64 阶段Ⅱ：主子流程编排（可选装配；无 CHILD 批次零行为）。 */
    @Autowired(required = false)
    private ObjectProvider<ChildOrchestrationService> childOrchestrationProvider;

    /** P63 动态分支端口（可选注入）：会签负向结算时关闭剩余动态分支。 */
    @Autowired
    private ObjectProvider<DynamicBranchPort> dynamicBranchPort;

    @Autowired
    private ApprovalLifecycleSupport support;

    /** 撤回/废弃终态化的子流程编排钩子：本实例作为子流程计失败项、作为父流程取消未完成批次。 */
    private void childOrchestrationHooks(BpmInstance instance, String terminalStatus) {
        ChildOrchestrationService orchestration =
                childOrchestrationProvider == null ? null
                        : childOrchestrationProvider.getIfAvailable();
        if (orchestration == null || instance == null) {
            return;
        }
        orchestration.onChildProcessTerminal(instance, terminalStatus);
        orchestration.cancelBatchesForParent(instance, terminalStatus);
    }

    void withdraw(String processInstanceId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmInstance instance = requireInstance(processInstanceId);
        if (!instance.getInitiatorId().equals(actor.getUserId())) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "仅发起人可撤回");
        }
        if (!InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            if (InstanceStatusEnum.WITHDRAWN.getCode().equals(instance.getStatus())) {
                return; // 幂等：已撤回重复请求
            }
            throw new BaseException(BpmErrorCode.WITHDRAW_NOT_PERMITTED);
        }
        // 撤回边界：本实例已有任何人工办理记录即越过不可撤回边界
        long handled = approvalActionService.findByProcessInstanceId(processInstanceId).size();
        if (handled > 0) {
            throw new BaseException(BpmErrorCode.WITHDRAW_NOT_PERMITTED);
        }
        // present = APPLIED（本次终止）/ ALREADY_APPLIED（已无运行实例）；标识缺失抛参数异常
        bpmTaskFacade.terminateProcess(processInstanceId, InstanceStatusEnum.WITHDRAWN.getCode())
        .orElseThrow(() -> new IllegalStateException(
                "BpmTaskFacade#terminateProcess 契约恒 present，empty 属契约违约"));
        bpmInstanceService.updateStatus(processInstanceId,
                InstanceStatusEnum.WITHDRAWN.getCode());
        // — P64 阶段Ⅱ（A05）：撤回终态化——本实例作为子流程计失败项、作为父流程取消未完成批次 —
        childOrchestrationHooks(instance, InstanceStatusEnum.WITHDRAWN.getCode());
        closeDeadlines(processInstanceId, "撤回关闭");
        support.recordInstance(instance, actor, ApprovalAction.WITHDRAW, InstanceStatusEnum.WITHDRAWN.getCode(),
                support.mapOf("reason", support.nullSafe(request.getReason())));
        support.publishNotice(actor, processInstanceId, instance.getInitiatorId(),
                BpmNotifyTrigger.PROCESS_WITHDRAWN);
        log.info("实例已撤回: processInstanceId={}, actor={}", processInstanceId, actor.getUserId());
    }

    void discard(String processInstanceId, ApprovalActionRequest request) {
        LoginUser actor = LoginUserHolder.get();
        BpmInstance instance = requireInstance(processInstanceId);
        if (!InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            if (InstanceStatusEnum.DISCARDED.getCode().equals(instance.getStatus())) {
                return; // 幂等
            }
            throw new BaseException(BpmErrorCode.ACTION_NOT_ALLOWED);
        }
        // present = APPLIED（本次终止）/ ALREADY_APPLIED（已无运行实例）；标识缺失抛参数异常
        bpmTaskFacade.terminateProcess(processInstanceId, InstanceStatusEnum.DISCARDED.getCode())
        .orElseThrow(() -> new IllegalStateException(
                "BpmTaskFacade#terminateProcess 契约恒 present，empty 属契约违约"));
        bpmInstanceService.updateStatus(processInstanceId,
                InstanceStatusEnum.DISCARDED.getCode());
        // — P64 阶段Ⅱ（A05）：废弃终态化——同撤回口径 —
        childOrchestrationHooks(instance, InstanceStatusEnum.DISCARDED.getCode());
        closeDeadlines(processInstanceId, "废弃关闭");
        support.recordInstance(instance, actor, ApprovalAction.DISCARD, InstanceStatusEnum.DISCARDED.getCode(),
                support.mapOf("reason", support.nullSafe(request.getReason()),
                        "beforeStatus", instance.getStatus()));
        support.publishNotice(actor, processInstanceId, instance.getInitiatorId(),
                BpmNotifyTrigger.PROCESS_DISCARDED);
        log.info("实例已废弃: processInstanceId={}, operator={}", processInstanceId, actor.getUserId());
    }

    void settleConsensusNegative(String tenantId, String processInstanceId,
                                 String nodeKey, String reason) {
        Optional<BpmInstance> found = bpmInstanceService
                .findByProcessInstanceId(processInstanceId);
        BpmInstance instance = found.orElse(null);
        if (instance == null
                || !InstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())) {
            return; // 幂等：已终局
        }
        // present = APPLIED（本次终止）/ ALREADY_APPLIED（已无运行实例）；标识缺失抛参数异常
        bpmTaskFacade.terminateProcess(processInstanceId, "CONSENSUS_REJECTED")
        .orElseThrow(() -> new IllegalStateException(
                "BpmTaskFacade#terminateProcess 契约恒 present，empty 属契约违约"));
        bpmInstanceService.updateStatus(processInstanceId,
                InstanceStatusEnum.REJECTED.getCode());
        closeDeadlines(processInstanceId, "会签负向结算关闭");
        DynamicBranchPort branchPort = dynamicBranchPort == null ? null : dynamicBranchPort.getIfAvailable();
        if (branchPort != null) {
            branchPort.closeRemaining(tenantId, processInstanceId, nodeKey, "CONSENSUS_NEGATIVE_SETTLED")
            .orElseThrow(() -> new IllegalStateException(
                    "DynamicBranchPort#closeRemaining 契约恒 present，empty 属契约违约"));
        }
        if (tenantId == null) {
            // 租户变量在流程启动时已强制非空；缺失属异常路径，fail closed 不落租户 0
            throw new IllegalArgumentException("会签负向结算缺少租户上下文: processInstanceId=" + processInstanceId);
        }
        Long tenantLong = Long.valueOf(tenantId);
        support.recordInstance(instance, syntheticLogin(tenantLong), ApprovalAction.DISAPPROVE,
                "CONSENSUS_SETTLED", support.mapOf("reason", support.nullSafe(reason, "会签负向结算"),
                        "nodeKey", nodeKey));
        support.publishNotice(syntheticLogin(tenantLong), processInstanceId, instance.getInitiatorId(),
                BpmNotifyTrigger.PROCESS_REJECTED);
        log.info("会签负向结算完成: processInstanceId={}, nodeKey={}", processInstanceId, nodeKey);
    }

    private BpmInstance requireInstance(String processInstanceId) {
        return bpmInstanceService.findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(),
                        "流程实例不存在"));
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

    private void closeDeadlines(String processInstanceId, String reason) {
        List<BpmTaskDeadline> pending = deadlineMapper.selectList(
                Wrappers.<BpmTaskDeadline>lambdaQuery()
                        .eq(BpmTaskDeadline::getProcessInstanceId, processInstanceId)
                        .eq(BpmTaskDeadline::getRunState, BatchItemStatusEnum.PENDING.getCode()));
        for (BpmTaskDeadline record : pending) {
            BpmTaskDeadline patch = new BpmTaskDeadline();
            patch.setId(record.getId());
            patch.setRunState(BpmChildBatch.STATUS_CANCELLED);
            patch.setLastReason(reason);
            patch.setHandledAt(LocalDateTime.now());
            deadlineMapper.updateById(patch);
        }
    }
}
