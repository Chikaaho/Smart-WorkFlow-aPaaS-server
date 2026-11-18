package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.participant.ConsensusVotePort;
import com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.BpmAuthorizeRule;
import com.sw.ck.bpm.process.service.ApprovalLifecycleService;
import com.sw.ck.common.response.R;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * I3 生命周期动作统一实现——门面。
 * <p>
 * 每个动作保证：动作资格（身份 + 任务/实例归属 + 状态门）、目标对象有效且同租户、
 * 幂等（重复请求不产生第二次业务效果）、审计（ApprovalActionRecord / 生命周期表）、
 * 通知（统一触发器，失败不反转审批状态）。
 * </p>
 * <p>
 * 实现按生命周期动作拆分至同包协作类（行为与拆分前一致，公开契约不变）：
 * {@link ApprovalTaskActionExecutor}（转办/委托/沟通/加签发起）、
 * {@link ApprovalSignActionExecutor}（加签/补签表态/取消/结算）、
 * {@link ApprovalInstanceActionExecutor}（撤回/废弃/会签负向结算）、
 * {@link ApprovalAuthorizeRuleService}（代理规则管理）、
 * {@link ApprovalCommunicationExecutor}（沟通回复）、
 * {@link ApprovalLifecyclePortAdapter}（引擎端口适配：会签投票/任务进入）、
 * {@link ApprovalLifecycleSupport}（共享校验/审计/通知/工具）。
 * {@code @Transactional} 事务边界保留在本门面的公开方法上（经 Spring 代理调用，
 * 语义与拆分前一致），协作类方法均在既有事务内执行。
 * </p>
 */
@Service
public class ApprovalLifecycleServiceImpl implements ApprovalLifecycleService {

    @Autowired
    private ApprovalTaskActionExecutor taskActionExecutor;
    @Autowired
    private ApprovalSignActionExecutor signActionExecutor;
    @Autowired
    private ApprovalInstanceActionExecutor instanceActionExecutor;
    @Autowired
    private ApprovalAuthorizeRuleService authorizeRuleService;
    @Autowired
    private ApprovalCommunicationExecutor communicationExecutor;
    @Autowired
    private ApprovalLifecyclePortAdapter lifecyclePortAdapter;

    // ==================== 任务级动作 ====================

    @Override
    @Transactional
    public R<Void> executeTaskAction(String taskId, ApprovalActionRequest request) {
        taskActionExecutor.executeTaskAction(taskId, request);
        return R.ok();
    }

    // ==================== 实例级动作 ====================

    @Override
    @Transactional
    public R<Void> withdraw(String processInstanceId, ApprovalActionRequest request) {
        instanceActionExecutor.withdraw(processInstanceId, request);
        return R.ok();
    }

    @Override
    @Transactional
    public R<Void> discard(String processInstanceId, ApprovalActionRequest request) {
        instanceActionExecutor.discard(processInstanceId, request);
        return R.ok();
    }

    // ==================== 加签/补签表态 ====================

    @Override
    @Transactional
    public R<Void> expressSign(Long recordId, ApprovalActionRequest request) {
        signActionExecutor.expressSign(recordId, request);
        return R.ok();
    }

    @Override
    @Transactional
    public R<Void> cancelSign(Long recordId, ApprovalActionRequest request) {
        signActionExecutor.cancelSign(recordId, request);
        return R.ok();
    }

    // ==================== 沟通回复 ====================

    @Override
    @Transactional
    public R<Void> replyCommunication(Long communicationId, ApprovalActionRequest request) {
        communicationExecutor.replyCommunication(communicationId, request);
        return R.ok();
    }

    // ==================== 授权代理规则 ====================

    @Override
    @Transactional
    public R<Long> saveAuthorizeRule(ApprovalActionRequest request) {
        return R.ok(authorizeRuleService.saveAuthorizeRule(request));
    }

    @Override
    @Transactional
    public R<Void> revokeAuthorizeRule(Long ruleId, ApprovalActionRequest request) {
        authorizeRuleService.revokeAuthorizeRule(ruleId, request);
        return R.ok();
    }

    @Override
    public List<BpmAuthorizeRule> listAuthorizeRules() {
        return authorizeRuleService.listAuthorizeRules();
    }

    // ==================== 引擎端口适配 ====================

    @Override
    public void settleConsensusNegative(String tenantId, String processInstanceId,
                                        String nodeKey, String reason) {
        instanceActionExecutor.settleConsensusNegative(tenantId, processInstanceId, nodeKey, reason);
    }

    @Override
    public void assertNoPendingSign(String taskId) {
        signActionExecutor.assertNoPendingSign(taskId);
    }

    @Override
    public ConsensusVotePort consensusVotePort() {
        return lifecyclePortAdapter.consensusVotePort();
    }

    @Override
    public LifecycleTaskEntryPort lifecycleTaskEntryPort() {
        return lifecyclePortAdapter.lifecycleTaskEntryPort();
    }
}
