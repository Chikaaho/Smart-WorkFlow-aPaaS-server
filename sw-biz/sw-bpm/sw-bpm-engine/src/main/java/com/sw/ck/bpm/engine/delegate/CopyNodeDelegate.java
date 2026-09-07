package com.sw.ck.bpm.engine.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.participant.NodeActionAuditPort;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 抄送节点委托：全量解析收件人、经 NotifyFacade 发送、逐人落审计。 */
@Component("copyNodeDelegate")
public class CopyNodeDelegate extends NodeDelegateSupport implements JavaDelegate {

    private final NotifyFacade notifyFacade;
    private final NodeActionAuditPort auditPort;

    public CopyNodeDelegate(RepositoryService repositoryService, ObjectMapper objectMapper,
                            ParticipantResolverRegistry participantResolverRegistry,
                            NotifyFacade notifyFacade, ObjectProvider<NodeActionAuditPort> auditPort) {
        super(repositoryService, objectMapper, participantResolverRegistry);
        this.notifyFacade = notifyFacade;
        this.auditPort = auditPort.getIfAvailable();
    }

    @Override
    public void execute(DelegateExecution execution) {
        var config = nodeConfig(execution);
        NodeParticipantContext context = participantContext(execution, config);
        var recipients = participantResolverRegistry.resolve(context);
        String title = asString(config.get("title"));
        String content = asString(config.get("content"));
        if (title == null) title = "流程抄送";
        if (content == null) content = "您有一条流程抄送";
        for (String recipient : recipients) {
            String status = "SUCCESS";
            String reason = null;
            try {
                Long recipientId = parseLong(recipient);
                if (recipientId == null) throw new IllegalArgumentException("收件人标识非法");
                NotifySendResult result = notifyFacade.send(NotifySendRequest.builder()
                        .recipientId(recipientId).title(title).content(content)
                        .bizType(NotifyBizType.WF_TODO).bizId(execution.getProcessInstanceId())
                        .tenantId(context.getTenantId()).channel(NotifyChannel.IN_APP)
                        .idempotencyKey(execution.getProcessInstanceId() + ":"
                                + execution.getCurrentActivityId() + ":" + recipient).build());
                if (!"SUCCESS".equals(result.getStatus())) {
                    status = result.getStatus() == null ? "FAILED" : result.getStatus();
                    reason = result.getFailureReason();
                    if (shouldBlock(config)) {
                        throw new com.sw.ck.common.exception.BaseException(
                                com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                                reason == null ? "抄送投递失败" : reason);
                    }
                }
            } catch (Exception e) {
                status = "FAILED";
                reason = e.getMessage();
                if (shouldBlock(config)) throw new com.sw.ck.common.exception.BaseException(
                        com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_DELIVERY_FAILED.getCode(),
                        reason == null ? "抄送投递失败" : reason);
            } finally {
                if (auditPort != null) auditPort.recordCopy(execution.getProcessInstanceId(),
                        execution.getCurrentActivityId(), null, recipient, status, reason, context.getTenantId());
            }
        }
    }
}
