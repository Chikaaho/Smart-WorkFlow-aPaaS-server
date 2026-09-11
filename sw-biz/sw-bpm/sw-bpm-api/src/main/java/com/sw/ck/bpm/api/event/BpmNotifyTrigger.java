package com.sw.ck.bpm.api.event;

/**
 * BPM 通知触发类型枚举。
 * <p>
 * 标识 {@link BpmNotifyEvent} 的触发原因，由 listener 根据此枚举
 * 映射到 {@code NotifyBizType} 和通知文案。
 * </p>
 */
public enum BpmNotifyTrigger {

    /**
     * 新待办创建：流程发起后，首个审批节点的 task 创建时触发。
     * 收件人为该 task 的审批人（approver）。
     */
    TODO_CREATED,

    /**
     * 流程审批通过：流程实例结束时触发（所有节点完成）。
     * 收件人为流程发起人（initiator）。
     */
    PROCESS_APPROVED,

    /** 审批动作导致流程拒绝终态。 */
    PROCESS_REJECTED,

    /** 审批动作将流程退回已通过人工节点。 */
    PROCESS_RETURNED,

    // ==================== I3 新动作通知（复用既有站内信容器） ====================

    /** 任务被转办：通知转入人。 */
    TASK_TRANSFERRED,

    /** 任务被委托：通知受托人。 */
    TASK_DELEGATED,

    /** 沟通征询：通知沟通接收人（不授权限）。 */
    TASK_COMMUNICATED,

    /** 发起人撤回。 */
    PROCESS_WITHDRAWN,

    /** 实例废弃。 */
    PROCESS_DISCARDED,

    /** 针对节点结算规则的不通过意见终局（区别于 REJECT）。 */
    PROCESS_DISAPPROVED,

    /** 加签任务到达：通知被追加人。 */
    TASK_SIGN_REQUESTED,

    /** 时限提醒/升级催办。 */
    TASK_DEADLINE_ALERT
}
