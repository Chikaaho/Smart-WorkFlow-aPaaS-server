package com.sw.ck.notify.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifySendAttempt;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.mapper.NotifySendAttemptMapper;
import com.sw.ck.notify.service.NotifyDeliveryRecoveryService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投递恢复与重试实现（I6）。
 * <p>
 * 进程内 AFTER_COMMIT 事件只是唤醒手段；服务重启或瞬时故障后，本调度从持久状态
 * 扫描「可重试失败且已到期」的未完成投递，按指数退避重试直至明确终态或达到最大尝试次数。
 * 跨租户扫描经 {@link TenantLineSuspension} 挂起租户过滤，逐行显式还原权威租户上下文。
 * </p>
 */
@Service
public class NotifyDeliveryRecoveryServiceImpl implements NotifyDeliveryRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(NotifyDeliveryRecoveryServiceImpl.class);

    private static final int MAX_RETRY_COUNT = 5;
    private static final int MAX_BATCH = 100;

    private final NotifyMessageMapper messageMapper;
    private final NotifySendAttemptMapper attemptMapper;
    private final NotifyFacade notifyFacade;

    public NotifyDeliveryRecoveryServiceImpl(NotifyMessageMapper messageMapper,
                                             NotifySendAttemptMapper attemptMapper,
                                             NotifyFacade notifyFacade) {
        this.messageMapper = messageMapper;
        this.attemptMapper = attemptMapper;
        this.notifyFacade = notifyFacade;
    }

    @Override
    @Scheduled(fixedDelayString = "${sw.notify.recovery.fixed-delay-ms:60000}")
    public int recoverDue() {
        List<NotifyMessage> due;
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            due = messageMapper.selectList(Wrappers.<NotifyMessage>lambdaQuery()
                    .eq(NotifyMessage::getFailureClass, "RETRYABLE")
                    .in(NotifyMessage::getDeliveryStatus, "FAILED", "TIMEOUT", "PENDING")
                    .isNotNull(NotifyMessage::getNextRetryTime)
                    .le(NotifyMessage::getNextRetryTime, LocalDateTime.now())
                    .lt(NotifyMessage::getRetryCount, MAX_RETRY_COUNT)
                    .orderByAsc(NotifyMessage::getNextRetryTime)
                    .last("LIMIT " + MAX_BATCH));
        }
        int recovered = 0;
        for (NotifyMessage msg : due) {
            if (recoverOne(msg)) {
                recovered++;
            }
        }
        if (recovered > 0) {
            log.info("投递恢复完成: rebound={}", recovered);
        }
        return recovered;
    }

    private boolean recoverOne(NotifyMessage msg) {
        int priorRetry = msg.getRetryCount() == null ? 0 : msg.getRetryCount();
        boolean claimed;
        NotifySendResult result;
        // 恢复轮运行在无登录身份的调度线程：跨租户扫描与写回均显式挂起租户过滤，
        // 每次写回都携带行自身的权威 tenant_id，业务列不受切换影响。
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            int claim = messageMapper.update(null, Wrappers.<NotifyMessage>lambdaUpdate()
                    .set(NotifyMessage::getDeliveryStatus, "RESENDING")
                    .setSql("retry_count = retry_count + 1")
                    .eq(NotifyMessage::getId, msg.getId())
                    .eq(NotifyMessage::getDeliveryStatus, msg.getDeliveryStatus())
                    .eq(NotifyMessage::getRetryCount, priorRetry));
            if (claim == 0) {
                return false;
            }
            claimed = true;
            int attemptNo = priorRetry + 2;
            try {
                LoginUserHolder.set(systemUser(msg));
                result = notifyFacade.attemptDelivery(buildRequest(msg));
            } catch (Exception e) {
                log.warn("投递恢复异常，按失败回写: id={}, exceptionClass={}", msg.getId(),
                        e.getClass().getSimpleName());
                result = NotifySendResult.builder().channel(NotifyChannel.valueOf(msg.getChannel()))
                        .status("FAILED").failureReason("恢复投递异常: " + e.getClass().getSimpleName()).build();
            } finally {
                LoginUserHolder.clear();
            }
            String status = result.getStatus() == null ? "FAILED" : result.getStatus();
            String failureClass = "SUCCESS".equals(status) ? null : classify(result.getFailureReason());
            int newRetry = priorRetry + 1;
            if (!"SUCCESS".equals(status) && newRetry >= MAX_RETRY_COUNT) {
                // 明确终态：重试预算耗尽后不再属于可重试，交由记录页人工处理
                failureClass = "RETRY_EXHAUSTED";
            }
            finishAttempt(msg, attemptNo, result, failureClass);
            writeTerminal(msg, result, failureClass);
            log.info("投递恢复完成: id={}, attemptNo={}, status={}, failureClass={}",
                    msg.getId(), attemptNo, status, failureClass);
        }
        return claimed && result != null;
    }

    private void finishAttempt(NotifyMessage msg, int attemptNo, NotifySendResult result, String failureClass) {
        try {
            NotifySendAttempt attempt = new NotifySendAttempt();
            attempt.setTenantId(msg.getTenantId());
            attempt.setMessageId(msg.getId());
            attempt.setAttemptNo(attemptNo);
            attempt.setChannel(msg.getChannel());
            attempt.setStatus(result.getStatus() == null ? "FAILED" : result.getStatus());
            attempt.setFailureReason(result.getFailureReason());
            attempt.setExternalMessageId(result.getExternalMessageId());
            attempt.setFailureClass(failureClass);
            attempt.setStartedAt(LocalDateTime.now());
            attempt.setFinishedAt(LocalDateTime.now());
            attemptMapper.insert(attempt);
        } catch (Exception e) {
            log.warn("恢复轮尝试流水记录失败（不阻断恢复）: id={}, attemptNo={}, error={}", msg.getId(), attemptNo,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void writeTerminal(NotifyMessage msg, NotifySendResult result, String failureClass) {
        String status = result.getStatus() == null ? "FAILED" : result.getStatus();
        boolean done = "SUCCESS".equals(status) || !"RETRYABLE".equals(failureClass);
        messageMapper.update(null, Wrappers.<NotifyMessage>lambdaUpdate()
                .set(NotifyMessage::getDeliveryStatus, status)
                .set(NotifyMessage::getExternalMessageId, result.getExternalMessageId())
                .set(NotifyMessage::getFailureReason, result.getFailureReason())
                .set(NotifyMessage::getFailureClass, failureClass)
                .set(NotifyMessage::getNextRetryTime, done ? null : nextBackoff(msg.getRetryCount() == null ? 1 : msg.getRetryCount() + 1))
                .eq(NotifyMessage::getId, msg.getId()));
    }

    private String classify(String failureReason) {
        if (failureReason != null && failureReason.contains("未配置生产渠道适配器")) {
            return "NON_RETRYABLE";
        }
        return "RETRYABLE";
    }

    private LocalDateTime nextBackoff(int attempt) {
        long minutes = (long) Math.min(60, 1L << Math.min(Math.max(attempt, 0), 6));
        return LocalDateTime.now().plusMinutes(minutes);
    }

    private NotifySendRequest buildRequest(NotifyMessage msg) {
        return NotifySendRequest.builder()
                .channel(NotifyChannel.valueOf(msg.getChannel()))
                .recipientId(msg.getRecipientId())
                .title(msg.getTitle())
                .content(msg.getContent())
                .bizType(parseBizType(msg.getBizType()))
                .bizId(msg.getBizId())
                .tenantId(msg.getTenantId())
                .eventType(msg.getEventType())
                .occurrenceNo(msg.getOccurrenceNo())
                .templateId(msg.getTemplateId())
                .templateVersion(msg.getTemplateVersion())
                .linkType(msg.getLinkType())
                .linkId(msg.getLinkId())
                .build();
    }

    private NotifyBizType parseBizType(String raw) {
        if (raw == null || raw.isBlank()) {
            return NotifyBizType.SYSTEM;
        }
        try {
            return NotifyBizType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return NotifyBizType.SYSTEM;
        }
    }

    /** 系统态上下文：仅携带租户与该接收人身份（恢复轮只做权威目标解析与写回）。 */
    private LoginUser systemUser(NotifyMessage msg) {
        LoginUser user = new LoginUser();
        user.setUserId(msg.getRecipientId());
        user.setUsername("notify-recovery");
        user.setTenantId(msg.getTenantId());
        return user;
    }
}
