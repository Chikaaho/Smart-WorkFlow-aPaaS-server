package com.sw.ck.notify.impl;

import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.SendNotifyCommand;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.entity.NotifySendAttempt;
import com.sw.ck.notify.mapper.NotifySendAttemptMapper;
import com.sw.ck.notify.service.NotifyMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知门面实现（I6 收敛版）。
 * <p>
 * 投递语义：
 * <ul>
 *   <li>一次业务通知按（租户 + 事件类型 + 业务对象 + 发生次序 + 接收人 + 渠道）
 *       形成稳定身份；同一身份的并发/重复请求命中唯一索引后只产生一次业务投递效果；</li>
 *   <li>IN_APP 恒成功且先完成站内信持久化；外部渠道先落业务通知与投递意图，
 *       再尝试适配器；失败按可重试分类写入下次重试时间，由投递恢复调度接管；</li>
 *   <li>每次尝试追加 {@code sw_notify_send_attempt} 流水，不覆盖历史、不新建第二条业务通知。</li>
 * </ul>
 */
@Service
public class NotifyFacadeImpl implements NotifyFacade {

    private static final Logger log = LoggerFactory.getLogger(NotifyFacadeImpl.class);

    private static final String NON_RETRYABLE = "NON_RETRYABLE";
    private static final String RETRYABLE = "RETRYABLE";

    private final NotifyMessageService notifyMessageService;
    private final Map<NotifyChannel, NotifyChannelAdapter> adapters;
    private final NotifySendAttemptMapper attemptMapper;

    /** 兼容既有单元/集成测试及直接调用方；未显式注册第三方渠道，尝试流水不记录。 */
    public NotifyFacadeImpl(NotifyMessageService notifyMessageService) {
        this(notifyMessageService, List.of(), null);
    }

    /** 兼容两参构造（既有测试）；尝试流水不记录。 */
    public NotifyFacadeImpl(NotifyMessageService notifyMessageService,
                            List<NotifyChannelAdapter> adapters) {
        this(notifyMessageService, adapters, null);
    }

    @Autowired
    public NotifyFacadeImpl(NotifyMessageService notifyMessageService,
                            List<NotifyChannelAdapter> adapters,
                            NotifySendAttemptMapper attemptMapper) {
        this.notifyMessageService = notifyMessageService;
        this.attemptMapper = attemptMapper;
        this.adapters = adapters == null ? Map.of() : adapters.stream()
                .collect(Collectors.toUnmodifiableMap(NotifyFacadeImpl::channelOf,
                        Function.identity(), (left, right) -> {
                            throw new IllegalStateException("通知渠道适配器重复: " + channelOf(left));
                        }));
    }

    /** 适配器身份是注册期契约：缺失渠道标识属装配错误，装配期直接失败。 */
    private static NotifyChannel channelOf(NotifyChannelAdapter adapter) {
        return adapter.channel().orElseThrow(
                () -> new IllegalStateException("通知渠道适配器缺少渠道标识"));
    }

    @Override
    public Optional<NotifySendResult> send(SendNotifyCommand cmd) {
        NotifyMessage msg = new NotifyMessage();
        msg.setRecipientId(cmd.getRecipientId());
        msg.setTitle(cmd.getTitle());
        msg.setContent(cmd.getContent());
        msg.setBizType(cmd.getBizType().name());
        msg.setBizId(cmd.getBizId());
        msg.setRead(false);
        // 旧入口依赖数据库默认值，兼容尚未包含 P58 渠道列的历史测试/存量 schema。
        notifyMessageService.save(msg);
        // 站内信入口恒成功：结果镜像落库行语义，持久化失败仍抛。
        return Optional.of(NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                .status("SUCCESS").build());
    }

    @Override
    public Optional<NotifySendResult> send(NotifySendRequest request) {
        if (request == null || request.getChannel() == null) {
            return Optional.of(NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                    .status("FAILED").failureReason("通知请求或渠道为空").build());
        }
        NotifySendResult forged = forgedTenantGuard(request);
        if (forged != null) {
            return Optional.of(forged);
        }
        // 1) 调用方显式幂等键优先
        if (hasText(request.getIdempotencyKey())) {
            NotifyMessage existing = notifyMessageService.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing != null) {
                return Optional.of(mirrorResult(request.getChannel(), existing));
            }
        }
        // 2) 业务稳定身份幂等：命中既有业务通知；但“仅登记、尚未投递”的持久意图
        //    （status=PENDING，由 recordIntent 在业务事务内写入）必须由本次调用完成投递，
        //    否则提交后加速路径会把通知无限期推迟给恢复调度。
        NotifyMessage identityExisting = findByIdentityRow(request);
        if (identityExisting != null && !isUndeliveredIntent(identityExisting)) {
            return Optional.of(mirrorResult(request.getChannel(), identityExisting));
        }
        if (request.getChannel() == NotifyChannel.IN_APP) {
            NotifySendResult result = NotifySendResult.builder()
                    .channel(NotifyChannel.IN_APP).status("SUCCESS").build();
            persistDelivery(request, result);
            return Optional.of(result);
        }
        NotifyChannelAdapter adapter = adapters.get(request.getChannel());
        if (adapter == null) {
            return Optional.of(persistDelivery(request, NotifySendResult.builder()
                    .channel(request.getChannel()).status("FAILED")
                    .failureReason("未配置生产渠道适配器").build()));
        }
        NotifySendResult result;
        try {
            Optional<NotifySendResult> delivered = adapter.send(request);
            if (delivered.isEmpty()) {
                result = NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                        .failureReason("渠道适配器未返回结果").build();
            } else {
                result = delivered.orElseThrow();
                if (result.getChannel() == null) {
                    result = NotifySendResult.builder().channel(request.getChannel())
                            .status(result.getStatus())
                            .externalMessageId(result.getExternalMessageId())
                            .failureReason(result.getFailureReason())
                            .build();
                }
            }
        } catch (Exception e) {
            log.info("通知渠道投递异常: channel={}, exceptionClass={}", request.getChannel(),
                    e.getClass().getSimpleName());
            result = NotifySendResult.builder().channel(request.getChannel())
                    .status("FAILED")
                    .failureReason("投递失败："
                            + com.sw.ck.common.trace.DiagnosticText.sanitize(e.getMessage(), 300))
                    .build();
        }
        if (identityExisting != null) {
            // 既有持久意图：认领并回写结果，不产生第二条业务通知
            NotifySendResult claimed = claimForDelivery(identityExisting, request);
            if (claimed != null) {
                return Optional.of(claimed);
            }
            return Optional.of(writeBackIntent(identityExisting, request, result));
        }
        return Optional.of(persistDelivery(request, result));
    }

    @Override
    public Optional<NotifySendResult> attemptDelivery(NotifySendRequest request) {
        if (request == null || request.getChannel() == null) {
            return Optional.of(NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                    .status("FAILED").failureReason("通知请求或渠道为空").build());
        }
        NotifySendResult forged = forgedTenantGuard(request);
        if (forged != null) {
            return Optional.of(forged);
        }
        if (request.getChannel() == NotifyChannel.IN_APP) {
            return Optional.of(NotifySendResult.builder().channel(NotifyChannel.IN_APP).status("SUCCESS").build());
        }
        NotifyChannelAdapter adapter = adapters.get(request.getChannel());
        if (adapter == null) {
            return Optional.of(NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason("未配置生产渠道适配器").build());
        }
        try {
            Optional<NotifySendResult> delivered = adapter.send(request);
            if (delivered.isEmpty()) {
                return Optional.of(NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                        .failureReason("渠道适配器未返回结果").build());
            }
            NotifySendResult result = delivered.orElseThrow();
            if (result.getChannel() == null) {
                return Optional.of(NotifySendResult.builder().channel(request.getChannel())
                        .status(result.getStatus())
                        .externalMessageId(result.getExternalMessageId())
                        .failureReason(result.getFailureReason())
                        .build());
            }
            return Optional.of(result);
        } catch (Exception e) {
            return Optional.of(NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason("投递失败："
                            + com.sw.ck.common.trace.DiagnosticText.sanitize(e.getMessage(), 300)).build());
        }
    }

    // ==================== 内部：身份与持久化 ====================

    /** 方向 §3.6 反向护栏：登录上下文存在时请求租户不得与之冲突；返回非空即拒绝。 */
    private NotifySendResult forgedTenantGuard(NotifySendRequest request) {
        com.sw.ck.security.holder.LoginUser principal = com.sw.ck.security.holder.LoginUserHolder.get();
        if (principal != null && principal.getTenantId() != null
                && request.getTenantId() != null
                && !principal.getTenantId().equals(request.getTenantId())) {
            log.warn("通知租户伪造拒绝: loginTenant={}, requestTenant={}", principal.getTenantId(), request.getTenantId());
            return NotifySendResult.builder().channel(request.getChannel())
                    .status("FAILED")
                    .failureReason("请求租户与认证租户不一致，拒绝写入")
                    .build();
        }
        return null;
    }

    private NotifyMessage findByIdentityRow(NotifySendRequest request) {
        if (request.getTenantId() == null || !hasText(request.getEventType())
                || !hasText(request.getBizId()) || request.getRecipientId() == null) {
            return null;
        }
        return notifyMessageService.findByIdentity(request.getTenantId(), request.getEventType(),
                request.getBizId(),
                request.getOccurrenceNo() == null ? 1L : request.getOccurrenceNo(),
                request.getRecipientId(), request.getChannel().name());
    }

    private NotifySendResult mirrorResult(NotifyChannel channel, NotifyMessage existing) {
        return NotifySendResult.builder()
                .channel(channel)
                .status(existing.getDeliveryStatus() == null ? "PENDING" : existing.getDeliveryStatus())
                .externalMessageId(existing.getExternalMessageId())
                .failureReason(existing.getFailureReason())
                .build();
    }

    @Override
    public Optional<NotifySendResult> recordIntent(NotifySendRequest request) {
        if (request == null || request.getChannel() == null) {
            return Optional.of(NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                    .status("FAILED").failureReason("通知请求或渠道为空").build());
        }
        NotifySendResult forged = forgedTenantGuard(request);
        if (forged != null) {
            return Optional.of(forged);
        }
        if (hasText(request.getIdempotencyKey())) {
            NotifyMessage byKey = notifyMessageService.findByIdempotencyKey(request.getIdempotencyKey());
            if (byKey != null) {
                return Optional.of(mirrorResult(request.getChannel(), byKey));
            }
        }
        NotifyMessage identityExisting = findByIdentityRow(request);
        if (identityExisting != null) {
            return Optional.of(mirrorResult(request.getChannel(), identityExisting));
        }
        if (request.getChannel() == NotifyChannel.IN_APP) {
            // 站内信行即投递结果：无外部渠道，登记即终态
            NotifySendResult result = NotifySendResult.builder()
                    .channel(NotifyChannel.IN_APP).status("SUCCESS").build();
            return Optional.of(persistDelivery(request, result));
        }
        return Optional.of(persistPendingIntent(request));
    }

    /** 未投递的持久意图判据：仅 recordIntent 会产生 PENDING 状态行。 */
    private static boolean isUndeliveredIntent(NotifyMessage message) {
        return "PENDING".equals(message.getDeliveryStatus());
    }

    /**
     * 登记可投递意图：与业务事务同事务落行，状态 PENDING + 可重试 + 立即到期，
     * 由提交后加速或恢复调度完成实际投递。不执行任何渠道 I/O。
     */
    private NotifySendResult persistPendingIntent(NotifySendRequest request) {
        NotifyMessage msg = newIntentRow(request);
        msg.setDeliveryStatus("PENDING");
        msg.setFailureClass(RETRYABLE);
        // 立即到期：提交后加速未执行时，恢复调度可在下一轮直接领取
        msg.setNextRetryTime(LocalDateTime.now());
        try {
            notifyMessageService.save(msg);
        } catch (DataAccessException concurrent) {
            NotifyMessage winner = findByIdentityRow(request);
            if (winner == null && hasText(request.getIdempotencyKey())) {
                winner = notifyMessageService.findByIdempotencyKey(request.getIdempotencyKey());
            }
            if (winner != null) {
                return mirrorResult(request.getChannel(), winner);
            }
            throw concurrent;
        }
        return NotifySendResult.builder().channel(request.getChannel())
                .status("PENDING").build();
    }

    /**
     * 认领既有意图行用于投递（PENDING → RESENDING 的条件更新）。
     *
     * @return 非空 = 已被其他执行者认领，返回该行当前镜像（本次不投递）；
     *         null = 认领成功，调用方继续投递并回写
     */
    private NotifySendResult claimForDelivery(NotifyMessage message, NotifySendRequest request) {
        int claimed = notifyMessageService.getBaseMapper().update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<NotifyMessage>lambdaUpdate()
                        .set(NotifyMessage::getDeliveryStatus, "RESENDING")
                        .setSql("retry_count = retry_count + 1")
                        // 领取即刷新租约时间：进程崩溃后恢复调度据此回收（wrapper 更新不自动填充）
                        .set(NotifyMessage::getUpdateTime, LocalDateTime.now())
                        .eq(NotifyMessage::getId, message.getId())
                        .eq(NotifyMessage::getDeliveryStatus, "PENDING"));
        if (claimed == 0) {
            NotifyMessage latest = findByIdentityRow(request);
            return latest == null ? mirrorResult(request.getChannel(), message)
                    : mirrorResult(request.getChannel(), latest);
        }
        return null;
    }

    /** 投递结果回写到既有意图行 + 尝试流水（不新建第二条业务通知）。 */
    private NotifySendResult writeBackIntent(NotifyMessage message, NotifySendRequest request,
                                             NotifySendResult result) {
        String status = result.getStatus() == null ? "FAILED" : result.getStatus();
        String failureClass = failureClassOf(result);
        NotifyMessage update = new NotifyMessage();
        update.setId(message.getId());
        update.setDeliveryStatus(status);
        update.setExternalMessageId(result.getExternalMessageId());
        update.setFailureReason(result.getFailureReason());
        update.setFailureClass(failureClass);
        update.setNextRetryTime(RETRYABLE.equals(failureClass) ? nextRetryTime(1) : null);
        notifyMessageService.updateById(update);
        recordAttempt(message.getId(), 1, request.getChannel().name(), result, failureClass);
        return NotifySendResult.builder()
                .channel(request.getChannel())
                .status(status)
                .externalMessageId(result.getExternalMessageId())
                .failureReason(result.getFailureReason())
                .build();
    }

    /** 意图行公共字段（与 persistDelivery 同构，集中一处避免两处口径漂移）。 */
    private NotifyMessage newIntentRow(NotifySendRequest request) {
        NotifyMessage msg = new NotifyMessage();
        msg.setRecipientId(request.getRecipientId());
        msg.setTitle(request.getTitle());
        msg.setContent(request.getContent());
        msg.setBizType(request.getBizType() == null ? "SYSTEM" : request.getBizType().name());
        msg.setBizId(request.getBizId());
        msg.setRead(false);
        msg.setChannel(request.getChannel().name());
        msg.setIdempotencyKey(request.getIdempotencyKey());
        msg.setEventType(hasText(request.getEventType()) ? request.getEventType() : "SYSTEM");
        msg.setOccurrenceNo(request.getOccurrenceNo() == null ? 1L : request.getOccurrenceNo());
        msg.setTemplateId(request.getTemplateId());
        msg.setTemplateVersion(request.getTemplateVersion());
        msg.setLinkType(request.getLinkType());
        msg.setLinkId(request.getLinkId());
        msg.setRetryCount(0);
        msg.setTenantId(request.getTenantId());
        return msg;
    }

    private NotifySendResult persistDelivery(NotifySendRequest request, NotifySendResult result) {
        // 方向 §3.6 反向护栏二：持久化层防御性复核（登录权威租户优先）。
        String status = result.getStatus() == null ? "FAILED" : result.getStatus();
        String failureClass = failureClassOf(result);
        NotifyMessage msg = new NotifyMessage();
        msg.setRecipientId(request.getRecipientId());
        msg.setTitle(request.getTitle());
        msg.setContent(request.getContent());
        msg.setBizType(request.getBizType() == null ? "SYSTEM" : request.getBizType().name());
        msg.setBizId(request.getBizId());
        msg.setRead(false);
        msg.setChannel(request.getChannel().name());
        msg.setDeliveryStatus(status);
        msg.setExternalMessageId(result.getExternalMessageId());
        msg.setFailureReason(result.getFailureReason());
        msg.setIdempotencyKey(request.getIdempotencyKey());
        msg.setEventType(hasText(request.getEventType()) ? request.getEventType() : "SYSTEM");
        msg.setOccurrenceNo(request.getOccurrenceNo() == null ? 1L : request.getOccurrenceNo());
        msg.setTemplateId(request.getTemplateId());
        msg.setTemplateVersion(request.getTemplateVersion());
        msg.setLinkType(request.getLinkType());
        msg.setLinkId(request.getLinkId());
        msg.setRetryCount(0);
        msg.setFailureClass(failureClass);
        msg.setNextRetryTime(RETRYABLE.equals(failureClass)
                ? nextRetryTime(0) : null);
        // 节点运行可能发生在无 LoginUserHolder 的引擎线程，显式请求租户是本入口的上下文来源。
        msg.setTenantId(request.getTenantId());
        try {
            notifyMessageService.save(msg);
        } catch (DataAccessException concurrent) {
            // 唯一索引兜底并发：读出获胜行并镜像其状态，不产生二次投递
            NotifyMessage winner = findByIdentityRow(request);
            if (winner == null && hasText(request.getIdempotencyKey())) {
                winner = notifyMessageService.findByIdempotencyKey(request.getIdempotencyKey());
            }
            if (winner != null) {
                return mirrorResult(request.getChannel(), winner);
            }
            throw concurrent;
        }
        recordAttempt(msg.getId(), 1, msg.getChannel(), result, failureClass);
        return result;
    }

    private String failureClassOf(NotifySendResult result) {
        if (result == null || "SUCCESS".equals(result.getStatus())) {
            return null;
        }
        if ("TIMEOUT".equals(result.getStatus())) {
            return RETRYABLE;
        }
        String reason = result.getFailureReason();
        if (reason != null && reason.contains("未配置生产渠道适配器")) {
            return NON_RETRYABLE;
        }
        return RETRYABLE;
    }

    /** 下一次自动重试时间：指数退避（1/2/4/8/16/32 分钟，封顶 60 分钟）。 */
    private LocalDateTime nextRetryTime(int retryCount) {
        long minutes = (long) Math.min(60, 1L << Math.min(Math.max(retryCount, 0), 6));
        return LocalDateTime.now().plusMinutes(minutes);
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /** 记录一次投递尝试流水；流水失败不影响投递结果。 */
    private void recordAttempt(Long messageId, int attemptNo, String channel, NotifySendResult result,
                               String failureClass) {
        if (messageId == null) {
            return;
        }
        try {
            NotifySendAttempt attempt = new NotifySendAttempt();
            attempt.setMessageId(messageId);
            attempt.setAttemptNo(attemptNo);
            attempt.setChannel(channel);
            attempt.setStatus(result.getStatus() == null ? "FAILED" : result.getStatus());
            attempt.setFailureReason(result.getFailureReason());
            attempt.setExternalMessageId(result.getExternalMessageId());
            if (failureClass != null) {
                attempt.setFailureClass(failureClass);
            }
            LocalDateTime now = LocalDateTime.now();
            attempt.setStartedAt(now);
            attempt.setFinishedAt(now);
            attemptMapper.insert(attempt);
        } catch (Exception e) {
            log.warn("尝试流水记录失败（不阻断投递）: messageId={}, error={}", messageId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
