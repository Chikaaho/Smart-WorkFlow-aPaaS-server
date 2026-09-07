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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知门面实现。
 * <p>
 * 将 {@link SendNotifyCommand} 映射为 {@link NotifyMessage} 实体并落库。
 * {@code tenant_id / 审计列 / deleted / version} 由 MyBatis-Plus 拦截器自动注入，
 * 本实现不手填、不读 ThreadLocal、不使用 {@code cmd.tenantId}。
 * </p>
 */
@Service
public class NotifyFacadeImpl implements NotifyFacade {

    private final NotifyMessageService notifyMessageService;
    private final Map<NotifyChannel, NotifyChannelAdapter> adapters;
    private final NotifySendAttemptMapper attemptMapper;

    /** 兼容既有单元/集成测试及直接调用方；未显式注册第三方渠道，尝试流水记录失败时静默跳过。 */
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
        // tenant_id / 审计列 / deleted / version 由拦截器自动注入，不手填
        notifyMessageService.save(msg);
    }

    @Override
    public NotifySendResult send(NotifySendRequest request) {
        if (request == null || request.getChannel() == null) {
            return NotifySendResult.builder().channel(NotifyChannel.IN_APP)
                    .status("FAILED").failureReason("通知请求或渠道为空").build();
        }
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            NotifyMessage existing = notifyMessageService.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing != null) {
                return NotifySendResult.builder().channel(request.getChannel())
                        .status(existing.getDeliveryStatus() == null ? "SUCCESS" : existing.getDeliveryStatus())
                        .externalMessageId(existing.getExternalMessageId())
                        .failureReason(existing.getFailureReason()).build();
            }
        }
        if (request.getChannel() == NotifyChannel.IN_APP) {
            NotifySendResult result = NotifySendResult.builder()
                    .channel(NotifyChannel.IN_APP).status("SUCCESS").build();
            persistDelivery(request, result);
            return result;
        }
        NotifyChannelAdapter adapter = adapters.get(request.getChannel());
        if (adapter == null) {
            NotifySendResult result = NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason("未配置生产渠道适配器").build();
            persistDelivery(request, result);
            return result;
        }
        NotifySendResult result;
        try {
            result = adapter.send(request);
        } catch (Exception e) {
            result = NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason(e.getMessage() == null ? "渠道适配器调用失败" : e.getMessage()).build();
        }
        if (result == null) {
            result = NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason("渠道适配器未返回结果").build();
        }
        if (result.getChannel() == null) {
            result = NotifySendResult.builder().channel(request.getChannel()).status(result.getStatus())
                    .externalMessageId(result.getExternalMessageId())
                    .failureReason(result.getFailureReason()).build();
        }
        persistDelivery(request, result);
        return result;
    }

    private void persistDelivery(NotifySendRequest request, NotifySendResult result) {
        NotifyMessage msg = new NotifyMessage();
        msg.setRecipientId(request.getRecipientId());
        msg.setTitle(request.getTitle());
        msg.setContent(request.getContent());
        msg.setBizType(request.getBizType() == null ? "SYSTEM" : request.getBizType().name());
        msg.setBizId(request.getBizId());
        msg.setRead(false);
        msg.setChannel(result.getChannel() == null ? request.getChannel().name() : result.getChannel().name());
        msg.setDeliveryStatus(result.getStatus() == null ? "FAILED" : result.getStatus());
        msg.setExternalMessageId(result.getExternalMessageId());
        msg.setFailureReason(result.getFailureReason());
        msg.setIdempotencyKey(request.getIdempotencyKey());
        // 节点运行可能发生在无 LoginUserHolder 的引擎线程，显式请求租户是本入口的上下文来源。
        msg.setTenantId(request.getTenantId());
        notifyMessageService.save(msg);
        // 首次投递尝试流水（v0.0.2）
        recordAttempt(msg.getId(), 1, msg.getChannel(), result);
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
                return NotifySendResult.builder().channel(request.getChannel()).status(result.getStatus())
                        .externalMessageId(result.getExternalMessageId())
                        .failureReason(result.getFailureReason()).build();
            }
            return result;
        } catch (Exception e) {
            return NotifySendResult.builder().channel(request.getChannel()).status("FAILED")
                    .failureReason(e.getMessage() == null ? "渠道适配器调用失败" : e.getMessage()).build();
        }
    }

    /** 记录一次投递尝试流水；流水失败不影响投递结果。 */
    private void recordAttempt(Long messageId, int attemptNo, String channel, NotifySendResult result) {
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
            attemptMapper.insert(attempt);
        } catch (Exception e) {
            // 尝试流水为辅助审计，不阻断投递主链路
        }
    }
}
