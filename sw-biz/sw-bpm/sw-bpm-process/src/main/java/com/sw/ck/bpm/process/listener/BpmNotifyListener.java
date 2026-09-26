package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.process.notify.BpmNotifyMessages;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifyRoutingService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * 监听 {@link BpmNotifyEvent}（I6 收敛版）。
 * <p>
 * 使用 {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code @Async}，审批事务
 * 提交后异步执行，投递失败不回滚已合法提交的审批动作。
 * </p>
 *
 * <h3>I6 统一投递权威</h3>
 * <ul>
 *   <li>事件类型 = 触发器常量（TODO_CREATED / PROCESS_APPROVED / …）；</li>
 *   <li>渠道与订阅由 {@link NotifyRoutingService} 唯一裁决（IN_APP 保底）；</li>
 *   <li>每次投递携带业务稳定身份（租户+事件+业务对象+发生次序+接收人+渠道），
 *       由通知域幂等托管，重复发布/重试不产生第二条业务通知；</li>
 *   <li>深链仅承载受控对象类型与稳定 ID（打开时由服务端重新鉴权）。</li>
 * </ul>
 *
 * <h3>上下文还原</h3>
 * 异步线程中 {@link LoginUserHolder} 不可用，全部上下文取自事件 payload，
 * 入口还原、finally clear。
 */
@Component
public class BpmNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(BpmNotifyListener.class);

    private final NotifyFacade notifyFacade;
    private final NotifyRoutingService notifyRoutingService;

    public BpmNotifyListener(NotifyFacade notifyFacade, NotifyRoutingService notifyRoutingService) {
        this.notifyFacade = notifyFacade;
        this.notifyRoutingService = notifyRoutingService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBpmNotify(BpmNotifyEvent event) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(event.getActorUserId());
        loginUser.setTenantId(event.getTenantId());
        LoginUserHolder.set(loginUser);
        try {
            Optional<BpmNotifyMessages.Spec> spec = BpmNotifyMessages.specOf(event.getTrigger());
            if (spec.isEmpty()) {
                log.warn("未知 BpmNotifyTrigger: {}，跳过通知", event.getTrigger());
                return;
            }
            String eventType = BpmNotifyMessages.eventType(event);
            // empty = 事件类型缺失等上下文不足：此处事件类型由枚举名给出，契约不产生 empty
            List<NotifyChannel> channels = notifyRoutingService.channelsFor(eventType, event.getRecipientId())
                    .orElseThrow(() -> new IllegalStateException(
                            "通知路由未返回渠道序列: eventType=" + eventType));
            for (NotifyChannel channel : channels) {
                // 事务内已由 BpmNotifyIntentRecorder 登记 PENDING 意图；本次调用认领并投递该意图，
                // 命中已投递身份则直接镜像结果，不产生第二条业务通知。
                // send 当前契约恒 present（渠道失败以结果状态表达，不以上空表达失败）
                notifyFacade.send(BpmNotifyMessages.requestFor(event, spec.orElseThrow(), channel))
                        .orElseThrow(() -> new IllegalStateException(
                                "NotifyFacade#send 契约恒 present，empty 属契约违约"));
            }
            log.debug("通知已发送: recipientId={}, eventType={}, bizId={}, channels={}",
                    event.getRecipientId(), eventType, event.getBizId(), channels.size());
        } catch (Exception e) {
            log.warn("通知投递异常（不回滚已提交审批）: trigger={}, bizId={}, exceptionClass={}",
                    event.getTrigger(), event.getBizId(), e.getClass().getSimpleName());
        } finally {
            LoginUserHolder.clear();
        }
    }
}
