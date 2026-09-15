package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
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
            NotifyBizType bizType;
            String title;
            String content;
            switch (event.getTrigger()) {
                case TODO_CREATED:
                    bizType = NotifyBizType.WF_TODO;
                    title = "您有一条待办";
                    content = "您有一条新的待办任务待处理";
                    break;
                case PROCESS_APPROVED:
                    bizType = NotifyBizType.WF_APPROVED;
                    title = "您的申请已通过";
                    content = "您发起的申请已审批通过";
                    break;
                case PROCESS_REJECTED:
                    bizType = NotifyBizType.WF_REJECTED;
                    title = "您的申请已驳回";
                    content = "您发起的申请已审批驳回";
                    break;
                case PROCESS_RETURNED:
                    bizType = NotifyBizType.WF_RETURNED;
                    title = "您的申请已退回";
                    content = "您发起的申请需要重新处理";
                    break;
                case TASK_TRANSFERRED:
                    bizType = NotifyBizType.WF_TODO;
                    title = "您收到一条转办任务";
                    content = "有任务已转办给您，请及时处理";
                    break;
                case TASK_DELEGATED:
                    bizType = NotifyBizType.WF_TODO;
                    title = "您收到一条委托任务";
                    content = "有任务被委托给您办理";
                    break;
                case TASK_COMMUNICATED:
                    bizType = NotifyBizType.WF_TODO;
                    title = "您收到一条沟通征询";
                    content = "有人向您征询审批意见，请查看";
                    break;
                case PROCESS_WITHDRAWN:
                    bizType = NotifyBizType.WF_REJECTED;
                    title = "您的申请已撤回";
                    content = "发起人撤回了该申请";
                    break;
                case PROCESS_DISAPPROVED:
                    bizType = NotifyBizType.WF_REJECTED;
                    title = "您的申请未通过";
                    content = "您的发起申请被不通过意见拦截";
                    break;
                case PROCESS_DISCARDED:
                    bizType = NotifyBizType.WF_REJECTED;
                    title = "您的申请已废弃";
                    content = "该实例已由授权主体废弃";
                    break;
                case TASK_SIGN_REQUESTED:
                    bizType = NotifyBizType.WF_TODO;
                    title = "您收到一条加签任务";
                    content = "有任务向您追加签批，请处理";
                    break;
                case TASK_DEADLINE_ALERT:
                    bizType = NotifyBizType.WF_TODO;
                    title = "办理时限提醒";
                    content = "您的一条审批任务临近/已超时限";
                    break;
                default:
                    log.warn("未知 BpmNotifyTrigger: {}，跳过通知", event.getTrigger());
                    return;
            }

            String eventType = event.getTrigger().name();
            List<NotifyChannel> channels = notifyRoutingService.channelsFor(eventType, event.getRecipientId());
            for (NotifyChannel channel : channels) {
                notifyFacade.send(NotifySendRequest.builder()
                        .channel(channel)
                        .recipientId(event.getRecipientId())
                        .title(title)
                        .content(content)
                        .bizType(bizType)
                        .bizId(event.getBizId())
                        .tenantId(event.getTenantId())
                        .eventType(eventType)
                        .occurrenceNo(event.getOccurrenceNo())
                        .linkType(linkType(event.getTrigger().name()))
                        .linkId(event.getBizId())
                        .build());
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

    /** 深链对象类型：流程终态类事件指向流程实例，其余指向任务。 */
    private String linkType(String trigger) {
        return trigger.startsWith("PROCESS") ? "WF_PROCESS" : "WF_TASK";
    }
}
