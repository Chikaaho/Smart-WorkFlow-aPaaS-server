package com.sw.ck.bpm.api.event;

import lombok.Getter;

import java.io.Serializable;

/**
 * BPM 通知事件。
 * <p>
     * 由 bpm-process 在流程关键节点（待办创建、流程通过/退回/驳回）经 {@code DomainEventPublisher} 发布，
 * 由 bpm-process 的 {@code BpmNotifyListener} 异步 {@code AFTER_COMMIT} 消费。
 * </p>
 *
 * <p>
 * 定义于 {@code -api} 模块，确保 bpm 不直接依赖 notify-api。
 * listener 在 bpm-process 内完成 trigger → {@code NotifyBizType} + 文案的映射。
 * </p>
 */
@Getter
public class BpmNotifyEvent implements Serializable {

    /**
     * 触发类型：TODO_CREATED / PROCESS_APPROVED / PROCESS_REJECTED / PROCESS_RETURNED。
     */
    private final BpmNotifyTrigger trigger;

    /**
     * 接收人用户 ID（TODO_CREATED = approver；流程结果事件 = initiator）。
     */
    private final Long recipientId;

    /**
     * 租户 ID，用于异步 listener 还原上下文。
     */
    private final Long tenantId;

    /**
     * 操作人用户 ID（TODO_CREATED = submitter；流程结果事件 = 当前审批人）。
     * listener 据此还原 LoginUserHolder.set(userId=actorUserId)，使拦截器自动注入审计列。
     */
    private final Long actorUserId;

    /**
     * 业务 ID（TODO_CREATED = taskId；流程结果事件 = processInstanceId）。
     */
    private final String bizId;

    public BpmNotifyEvent(BpmNotifyTrigger trigger, Long recipientId,
                          Long tenantId, Long actorUserId, String bizId) {
        this.trigger = trigger;
        this.recipientId = recipientId;
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.bizId = bizId;
    }
}
