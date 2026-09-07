package com.sw.ck.bpm.engine.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

/** 通知节点委托：统一渠道入口，第三方未配置适配器时明确失败。 */
@Component("notificationNodeDelegate")
public class NotificationNodeDelegate extends NodeDelegateSupport implements JavaDelegate {

    private final NotifyFacade notifyFacade;

    public NotificationNodeDelegate(RepositoryService repositoryService, ObjectMapper objectMapper,
                                    ParticipantResolverRegistry participantResolverRegistry,
                                    NotifyFacade notifyFacade) {
        super(repositoryService, objectMapper, participantResolverRegistry);
        this.notifyFacade = notifyFacade;
    }

    @Override
    public void execute(DelegateExecution execution) {
        var config = nodeConfig(execution);
        NodeParticipantContext context = participantContext(execution, config);
        var recipients = participantResolverRegistry.resolve(context);
        NotifyChannel channel;
        try { channel = NotifyChannel.valueOf(String.valueOf(config.getOrDefault("channel", "IN_APP"))); }
        catch (IllegalArgumentException e) { throw new com.sw.ck.common.exception.BaseException(
                com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_CONFIG_INVALID.getCode(), "通知渠道不合法"); }
        String title = asString(config.get("title"));
        String content = asString(config.get("content"));
        if (title == null || content == null) throw new com.sw.ck.common.exception.BaseException(
                com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_CONFIG_INVALID.getCode(), "通知标题和正文不能为空");
        for (String recipient : recipients) {
            Long recipientId = parseLong(recipient);
            if (recipientId == null) throw new com.sw.ck.common.exception.BaseException(
                    com.sw.ck.bpm.api.exception.BpmErrorCode.PARTICIPANT_CONFIG_INVALID);
            NotifySendResult result = notifyFacade.send(NotifySendRequest.builder()
                    .recipientId(recipientId).title(title).content(content)
                    .bizType(NotifyBizType.SYSTEM).bizId(execution.getProcessInstanceId())
                    .tenantId(context.getTenantId()).channel(channel)
                    .idempotencyKey(execution.getProcessInstanceId() + ":"
                            + execution.getCurrentActivityId() + ":" + recipient).build());
            if (!"SUCCESS".equals(result.getStatus()) && shouldBlock(config)) {
                throw new com.sw.ck.common.exception.BaseException(
                        com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                        result.getFailureReason() == null ? "通知投递失败" : result.getFailureReason());
            }
        }
    }
}
