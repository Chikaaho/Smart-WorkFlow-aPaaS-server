package com.sw.ck.bpm.process.notify;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifySendRequest;

import java.util.Optional;

/**
 * {@link BpmNotifyEvent} → 通知请求的<b>唯一</b>文案与身份映射。
 *
 * <p>Phase 4 可靠业务事件：事务内意图登记（{@code BpmNotifyIntentRecorder}）与提交后加速投递
 * （{@code BpmNotifyListener}）必须使用同一份映射，避免“登记的身份/文案”与“投递的身份/文案”
 * 出现口径漂移导致幂等身份不一致。</p>
 */
public final class BpmNotifyMessages {

    private BpmNotifyMessages() {
    }

    /**
     * 事件 → 通知语义（bizType/标题/正文/深链类型）。
     *
     * @return empty = 未登记的触发器（调用方跳过并告警，不得伪造通知）
     */
    public static Optional<Spec> specOf(BpmNotifyTrigger trigger) {
        if (trigger == null) {
            return Optional.empty();
        }
        return switch (trigger) {
            case TODO_CREATED -> Optional.of(new Spec(NotifyBizType.WF_TODO, "您有一条待办", "您有一条新的待办任务待处理"));
            case PROCESS_APPROVED -> Optional.of(new Spec(NotifyBizType.WF_APPROVED, "您的申请已通过", "您发起的申请已审批通过"));
            case PROCESS_REJECTED -> Optional.of(new Spec(NotifyBizType.WF_REJECTED, "您的申请已驳回", "您发起的申请已审批驳回"));
            case PROCESS_RETURNED -> Optional.of(new Spec(NotifyBizType.WF_RETURNED, "您的申请已退回", "您发起的申请需要重新处理"));
            case TASK_TRANSFERRED -> Optional.of(new Spec(NotifyBizType.WF_TODO, "您收到一条转办任务", "有任务已转办给您，请及时处理"));
            case TASK_DELEGATED -> Optional.of(new Spec(NotifyBizType.WF_TODO, "您收到一条委托任务", "有任务被委托给您办理"));
            case TASK_COMMUNICATED -> Optional.of(new Spec(NotifyBizType.WF_TODO, "您收到一条沟通征询", "有人向您征询审批意见，请查看"));
            case PROCESS_WITHDRAWN -> Optional.of(new Spec(NotifyBizType.WF_REJECTED, "您的申请已撤回", "发起人撤回了该申请"));
            case PROCESS_DISAPPROVED -> Optional.of(new Spec(NotifyBizType.WF_REJECTED, "您的申请未通过", "您的发起申请被不通过意见拦截"));
            case PROCESS_DISCARDED -> Optional.of(new Spec(NotifyBizType.WF_REJECTED, "您的申请已废弃", "该实例已由授权主体废弃"));
            case TASK_SIGN_REQUESTED -> Optional.of(new Spec(NotifyBizType.WF_TODO, "您收到一条加签任务", "有任务向您追加签批，请处理"));
            case TASK_DEADLINE_ALERT -> Optional.of(new Spec(NotifyBizType.WF_TODO, "办理时限提醒", "您的一条审批任务临近/已超时限"));
        };
    }

    /** 深链对象类型：流程终态类事件指向流程实例，其余指向任务。 */
    public static String linkType(BpmNotifyTrigger trigger) {
        return trigger != null && trigger.name().startsWith("PROCESS") ? "WF_PROCESS" : "WF_TASK";
    }

    /** 事件类型标识（= 触发器常量名），幂等身份组成之一。 */
    public static String eventType(BpmNotifyEvent event) {
        return event.getTrigger() == null ? null : event.getTrigger().name();
    }

    /**
     * 构造指定渠道的通知请求（身份与文案来自同一映射）。
     *
     * @param event   业务通知事件
     * @param spec    语义映射结果
     * @param channel 目标渠道
     */
    public static NotifySendRequest requestFor(BpmNotifyEvent event, Spec spec,
                                               com.sw.ck.notify.api.NotifyChannel channel) {
        return NotifySendRequest.builder()
                .channel(channel)
                .recipientId(event.getRecipientId())
                .title(spec.title())
                .content(spec.content())
                .bizType(spec.bizType())
                .bizId(event.getBizId())
                .tenantId(event.getTenantId())
                .eventType(eventType(event))
                .occurrenceNo(event.getOccurrenceNo())
                .linkType(linkType(event.getTrigger()))
                .linkId(event.getBizId())
                .build();
    }

    /** 通知语义映射结果。 */
    public record Spec(NotifyBizType bizType, String title, String content) {
    }
}
