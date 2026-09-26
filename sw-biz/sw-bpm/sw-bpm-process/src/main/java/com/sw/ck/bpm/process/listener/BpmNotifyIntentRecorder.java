package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.process.notify.BpmNotifyMessages;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifyRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * 通知投递意图的事务内登记器（Phase 4 可靠业务事件 —— 通知 MUST_PERSIST_INTENT）。
 *
 * <p>在 {@link BpmNotifyEvent} 发布处<b>同步</b>执行（不是 {@code @Async}、不是 AFTER_COMMIT），
 * 因此意图行与业务数据处于同一事务：业务回滚则意图不存在；业务提交则意图已持久化，
 * 即使进程在“提交后、异步监听执行前”退出，通知也不会丢失（由提交后加速或恢复调度投递）。</p>
 *
 * <ul>
 *   <li>渠道序列由 {@link NotifyRoutingService} 唯一裁决（IN_APP 保底），与提交后投递共用；</li>
 *   <li>意图登记只落行、不做任何渠道 I/O（{@link NotifyFacade#recordIntent}）；</li>
 *   <li>幂等身份与提交后投递完全一致，重复发布不会产生第二条业务通知；</li>
 *   <li>发布点无事务时（不应存在）在日志中显式告警，使“无事务发布”不再静默丢失。</li>
 * </ul>
 */
@Component
public class BpmNotifyIntentRecorder {

    private static final Logger log = LoggerFactory.getLogger(BpmNotifyIntentRecorder.class);

    private final NotifyFacade notifyFacade;
    private final NotifyRoutingService notifyRoutingService;

    public BpmNotifyIntentRecorder(NotifyFacade notifyFacade, NotifyRoutingService notifyRoutingService) {
        this.notifyFacade = notifyFacade;
        this.notifyRoutingService = notifyRoutingService;
    }

    @EventListener
    public void onBpmNotify(BpmNotifyEvent event) {
        record(event);
    }

    /**
     * 登记该事件的全部渠道投递意图。
     *
     * @return 已登记/已存在的意图条数（0 = 未登记的触发器，已告警）
     */
    public int record(BpmNotifyEvent event) {
        if (event == null || event.getTrigger() == null) {
            return 0;
        }
        Optional<BpmNotifyMessages.Spec> spec = BpmNotifyMessages.specOf(event.getTrigger());
        if (spec.isEmpty()) {
            log.warn("未登记的通知触发器，跳过意图登记: trigger={}, bizId={}",
                    event.getTrigger(), event.getBizId());
            return 0;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("通知意图登记发生在无事务上下文（意图将独立提交，业务回滚不回滚该意图）:"
                            + " trigger={}, bizId={}, recipientId={}",
                    event.getTrigger(), event.getBizId(), event.getRecipientId());
        }
        String eventType = BpmNotifyMessages.eventType(event);
        // empty = 事件类型上下文不足：此处事件类型由枚举名给出，契约不产生 empty
        List<NotifyChannel> channels = notifyRoutingService.channelsFor(eventType, event.getRecipientId())
                .orElseThrow(() -> new IllegalStateException(
                        "通知路由未返回渠道序列: eventType=" + eventType));
        int recorded = 0;
        for (NotifyChannel channel : channels) {
            // recordIntent 契约恒 present（持久化失败抛异常，使意图与业务同失败）
            notifyFacade.recordIntent(BpmNotifyMessages.requestFor(event, spec.orElseThrow(), channel))
                    .orElseThrow(() -> new IllegalStateException(
                            "NotifyFacade#recordIntent 契约恒 present，empty 属契约违约"));
            recorded++;
        }
        log.debug("通知意图已登记: recipientId={}, eventType={}, bizId={}, channels={}, inTx={}",
                event.getRecipientId(), eventType, event.getBizId(), channels.size(),
                TransactionSynchronizationManager.isActualTransactionActive());
        return recorded;
    }
}
