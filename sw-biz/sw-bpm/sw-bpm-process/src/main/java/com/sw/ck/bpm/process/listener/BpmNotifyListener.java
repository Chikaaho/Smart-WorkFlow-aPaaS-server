package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.SendNotifyCommand;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 监听 {@link BpmNotifyEvent}，将流程待办/审批通知写入通知表。
 * <p>
 * 使用 {@code @TransactionalEventListener(AFTER_COMMIT)} 确保只在流程事务提交后触发，
 * 配合 {@code @Async} 异步执行，不阻塞完整路径。
 * </p>
 *
 * <h3>上下文还原</h3>
 * 异步线程中 {@link LoginUserHolder} 不可用（ThreadLocal 不跨线程），
 * 所有上下文信息（actorUserId、tenantId）均从事件 payload 获取。
 * 入口处还原 LoginUserHolder，使 MyBatis-Plus 拦截器自动注入 tenant_id/审计列；
 * finally 中 clear。
 *
 * <h3>映射规则</h3>
 * <ul>
 *   <li>{@code TODO_CREATED} → {@code WF_TODO}：通知审批人有新待办</li>
 *   <li>{@code PROCESS_APPROVED} → {@code WF_APPROVED}：通知发起人审批已通过</li>
 * </ul>
 */
@Component
public class BpmNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(BpmNotifyListener.class);

    private final NotifyFacade notifyFacade;

    public BpmNotifyListener(NotifyFacade notifyFacade) {
        this.notifyFacade = notifyFacade;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBpmNotify(BpmNotifyEvent event) {
        // 从事件 payload 还原上下文（异步线程中 LoginUserHolder 为空）
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
                default:
                    log.warn("未知 BpmNotifyTrigger: {}，跳过通知", event.getTrigger());
                    return;
            }

            SendNotifyCommand cmd = new SendNotifyCommand(
                    event.getRecipientId(),
                    title,
                    content,
                    bizType,
                    event.getBizId(),
                    event.getTenantId()
            );
            notifyFacade.send(cmd);

            log.debug("通知已发送: recipientId={}, bizType={}, bizId={}",
                    event.getRecipientId(), bizType, event.getBizId());
        } finally {
            LoginUserHolder.clear();
        }
    }
}
