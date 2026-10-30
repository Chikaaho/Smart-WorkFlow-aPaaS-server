package com.sw.ck.bpm.api.event;

import lombok.Getter;

import java.io.Serializable;
import java.util.List;

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

    /**
     * 同一事件类型的业务发生次序（I6：新一轮办理/再次提醒为新的稳定发生标识；缺省 1）。
     */
    private final Long occurrenceNo;

    /**
     * 本次结束通知显式选择的渠道令牌（IN_APP/EMAIL）；null/空 = 沿用通知路由原裁决。
     * 实际投递仍与租户启停、订阅和路由渠道取交集。
     */
    private final List<String> channels;

    /**
     * 接收人是否为流程发起人：仅发起人收到“您的申请”文案；审批人/其他接收人使用中性文案。
     * 旧构造路径默认 true，保持原“结果通知发给发起人”的行为。
     */
    private final boolean initiatorRecipient;

    public BpmNotifyEvent(BpmNotifyTrigger trigger, Long recipientId,
                          Long tenantId, Long actorUserId, String bizId) {
        this(trigger, recipientId, tenantId, actorUserId, bizId, 1L, null, true);
    }

    public BpmNotifyEvent(BpmNotifyTrigger trigger, Long recipientId,
                          Long tenantId, Long actorUserId, String bizId, Long occurrenceNo) {
        this(trigger, recipientId, tenantId, actorUserId, bizId, occurrenceNo, null, true);
    }

    public BpmNotifyEvent(BpmNotifyTrigger trigger, Long recipientId,
                          Long tenantId, Long actorUserId, String bizId, Long occurrenceNo,
                          List<String> channels) {
        this(trigger, recipientId, tenantId, actorUserId, bizId, occurrenceNo, channels, true);
    }

    public BpmNotifyEvent(BpmNotifyTrigger trigger, Long recipientId,
                          Long tenantId, Long actorUserId, String bizId, Long occurrenceNo,
                          List<String> channels, boolean initiatorRecipient) {
        this.trigger = trigger;
        this.recipientId = recipientId;
        this.tenantId = tenantId;
        this.actorUserId = actorUserId;
        this.bizId = bizId;
        this.occurrenceNo = occurrenceNo == null ? 1L : occurrenceNo;
        this.channels = channels == null ? null : List.copyOf(channels);
        this.initiatorRecipient = initiatorRecipient;
    }
}
