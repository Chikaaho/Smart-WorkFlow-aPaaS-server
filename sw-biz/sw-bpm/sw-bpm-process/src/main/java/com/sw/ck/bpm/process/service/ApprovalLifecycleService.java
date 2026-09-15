package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.common.response.R;

import java.util.List;

/**
 * I3 人工审批生命周期统一执行核心。
 * <p>
 * 转办/委托/代理/撤回/沟通/废弃/加签/补签的唯一执行路径：
 * HTTP 端点与未来命令通道共享同一业务校验、权限、幂等、审计与通知语义。
 * </p>
 */
public interface ApprovalLifecycleService {

    /** 任务级动作（TRANSFER/DELEGATE/COMMUNICATE/ADD_SIGN；SIGN 表态走独立入口）。 */
    R<Void> executeTaskAction(String taskId, ApprovalActionRequest request);

    /** 实例级撤回（发起人专用）。 */
    R<Void> withdraw(String processInstanceId, ApprovalActionRequest request);

    /** 实例级废弃（授权主体专用，Controller 端权限为唯一防线之一）。 */
    R<Void> discard(String processInstanceId, ApprovalActionRequest request);

    /** 加签/补签表态（APPROVE/DISAPPROVE）。 */
    R<Void> expressSign(Long recordId, ApprovalActionRequest request);

    /** 取消待处理加签记录（操作人可撤销自己的加签）。 */
    R<Void> cancelSign(Long recordId, ApprovalActionRequest request);

    /** 沟通回复（接收人专用；不产生审批结算权）。 */
    R<Void> replyCommunication(Long communicationId, ApprovalActionRequest request);

    /** 创建/更新授权代理规则（本人或有权管理员）。 */
    R<Long> saveAuthorizeRule(ApprovalActionRequest request);

    /** 撤销代理规则（授权人或有权管理员）。 */
    R<Void> revokeAuthorizeRule(Long ruleId, ApprovalActionRequest request);

    /** 查询当前用户的代理规则。 */
    List<com.sw.ck.bpm.process.entity.BpmAuthorizeRule> listAuthorizeRules();

    /** 任务存在待处理加签时阻断普通结算。 */
    void assertNoPendingSign(String taskId);

    /** 会签投票端口实现（ConsensusVotePort 适配）。 */
    com.sw.ck.bpm.api.participant.ConsensusVotePort consensusVotePort();

    /** 任务进入生命周期端口实现（代理匹配 + 时限登记）。 */
    com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort lifecycleTaskEntryPort();


    /** 会签负向终局结算（引擎会签监听器回调）。 */
    void settleConsensusNegative(String tenantId, String processInstanceId,
                                 String nodeKey, String reason);
}
