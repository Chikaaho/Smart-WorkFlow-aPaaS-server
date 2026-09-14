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
                .collect(Collectors.toUnmodifiableMap(NotifyChannelAdapter::channel,
                        Function.identity(), (left, right) -> {
                            throw new IllegalStateException("通知渠道适配器重复: " + left.channel());
                        }));
    }

    @Override
    public void send(SendNotifyCommand cmd) {
        NotifyMessage msg = new NotifyMessage();
        msg.setRecipientId(cmd.getRecipientId());
        msg.setTitle(cmd.getTitle());
        msg.setContent(cmd.getContent());
        msg.setBizType(cmd.getBizType().name());
        msg.setBizId(cmd.getBizId());
        msg.setRead(false);
        // 旧入口依赖数据库默认值，兼容尚未包含 P58 渠道列的历史测试/存量 schema。
        notifyMessageService.save(msg);
    }

    @Override
    public NotifySendResult send(NotifySendRequest request) {
        if (request == null || request.getChannel() == null) {
            return NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                    .status("FAILED").failureReason("通知请求或渠道为空").build();
        }
        // 1) 调用方显式幂等键优先
        if (hasText(request.getIdempotencyKey())) {
            NotifyMessage existing = notifyMessageService.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing != null) {
                return mirrorResult(request.getChannel(), existing);
            }
        }
        // 2) 业务稳定身份幂等：命中即返回既有业务通知，不产生第二次投递效果
        NotifyMessage identityExisting = findByIdentityRow(request);
        if (identityExisting != null) {
            return mirrorResult(request.getChannel(), identityExisting);
        }
        if (request.getChannel() == NotifyChannel.IN_APP) {
            NotifySendResult result = NotifySendResult.builder()
                    .channel(NotifyChannel.IN_APP).status("SUCCESS").build();
            persistDelivery(request, result);
            return result;
        }
        NotifyChannelAdapter adapter = adapters.get(request.getChannel());
        if (adapter == null) {
            return persistDelivery(request, NotifySendResult.builder()
                    .channel(request.getChannel()).status("FAILED")
                    .failureReason("未配置生产渠道适配器").build());
        }
        NotifySendResult result;
        try {
            result = adapter.send(request);
            if (result == null) {
                result = NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                        .failureReason("渠道适配器未返回结果").build();
            }
            if (result.getChannel() == null) {
                result = NotifySendResult.builder().channel(request.getChannel())
                        .status(result.getStatus())
                        .externalMessageId(result.getExternalMessageId())
                        .failureReason(result.getFailureReason())
                        .build();
            }
        } catch (Exception e) {
            log.info("通知渠道投递异常: channel={}, exceptionClass={}", request.getChannel(),
                    e.getClass().getSimpleName());
            result = NotifySendResult.builder().channel(request.getChannel())
                    .status("FAILED")
                    .failureReason("渠道适配器调用失败: "
                            + (e.getMessage() == null ? "未知异常" : e.getMessage()))
                    .build();
        }
        return persistDelivery(request, result);
    }

    @Override
    public NotifySendResult attemptDelivery(NotifySendRequest request) {
        if (request == null || request.getChannel() == null) {
            return NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                    .status("FAILED").failureReason("通知请求或渠道为空").build();
        }
        if (request.getChannel() == NotifyChannel.IN_APP) {
            return NotifySendResult.builder().channel(NotifyChannel.IN_APP).status("SUCCESS").build();
        }
        NotifyChannelAdapter adapter = adapters.get(request.getChannel());
        if (adapter == null) {
            return NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason("未配置生产渠道适配器").build();
        }
        try {
            NotifySendResult result = adapter.send(request);
            if (result == null) {
                return NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                        .failureReason("渠道适配器未返回结果").build();
            }
            if (result.getChannel() == null) {
                return NotifySendResult.builder().channel(request.getChannel())
                        .status(result.getStatus())
                        .externalMessageId(result.getExternalMessageId())
                        .failureReason(result.getFailureReason())
                        .build();
            }
            return result;
        } catch (Exception e) {
            return NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason(e.getMessage() == null ? "渠道适配器调用失败" : e.getMessage()).build();
        }
    }

    // ==================== 内部：身份与持久化 ====================

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

    private NotifySendResult persistDelivery(NotifySendRequest request, NotifySendResult result) {
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
            // 尝试流水为辅助审计，不阻断投递主链路
        }
    }
}
