package com.sw.ck.notify.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.api.NotifyTargetResolution;
import com.sw.ck.notify.api.NotifyTargetResolver;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import lombok.Data;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** P58 开发验收用真实通知入口；生产构建不包含本类。 */
@RestController
@RequestMapping("/notify/debug")
@Profile("dev")
public class P58DebugNotifyController {

    private final NotifyFacade notifyFacade;
    private final ObjectProvider<NotifyTargetResolver> targetResolver;
    private final JdbcTemplate jdbcTemplate;

    public P58DebugNotifyController(NotifyFacade notifyFacade,
                                    ObjectProvider<NotifyTargetResolver> targetResolver,
                                    JdbcTemplate jdbcTemplate) {
        this.notifyFacade = notifyFacade;
        this.targetResolver = targetResolver;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/send")
    public R<NotifySendResult> send(@RequestBody Request request) {
        LoginUser loginUser = LoginUserHolder.get();
        NotifyChannel channel = request.getChannel() == null || request.getChannel().isBlank()
                ? NotifyChannel.SMS : NotifyChannel.valueOf(request.getChannel().trim().toUpperCase());
        NotifySendRequest command = NotifySendRequest.builder()
                .recipientId(request.getRecipientId() == null ? loginUser.getUserId() : request.getRecipientId())
                .title(request.getTitle())
                .content(request.getContent())
                .bizType(NotifyBizType.SYSTEM)
                .bizId(request.getBizId())
                .tenantId(loginUser.getTenantId())
                .channel(channel)
                .idempotencyKey(request.getIdempotencyKey())
                .build();
        return R.ok(notifyFacade.send(command));
    }

    /** PHONE 解析验收入口：仅返回状态/摘要与副作用计数，不经过任何渠道适配器。 */
    @PostMapping("/resolve-phone")
    public R<PhoneResolution> resolvePhone(@RequestBody PhoneRequest request) {
        LoginUser loginUser = LoginUserHolder.get();
        NotifyTargetResolver resolver = targetResolver.getIfAvailable();
        Long tenantId = loginUser == null ? null : loginUser.getTenantId();
        Long beforeMessages = count("sw_notify_message", tenantId);
        Long beforeAttempts = count("sw_notify_send_attempt", tenantId);
        NotifyTargetResolution resolution = resolver == null
                ? NotifyTargetResolution.of("RESOLVER_UNAVAILABLE", "SYS_USER", null)
                : resolver.resolvePhoneForTenant(tenantId, request.getUserId());
        return R.ok(new PhoneResolution(request.getUserId(), tenantId, resolution.getStatus(),
                resolution.getSource(), resolution.getValueDigest(), beforeMessages, beforeAttempts,
                count("sw_notify_message", tenantId), count("sw_notify_send_attempt", tenantId)));
    }

    private Long count(String table, Long tenantId) {
        if (tenantId == null) return 0L;
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    @Data
    public static class Request {
        private Long recipientId;
        private String title;
        private String content;
        private String bizId;
        private String channel;
        private String idempotencyKey;
    }

    @Data
    public static class PhoneRequest {
        private Long userId;
        /** 仅用于证明客户端字段不会成为解析来源；服务端刻意忽略该字段。 */
        private String rawPhone;
    }

    @Data
    public static class PhoneResolution {
        private final Long userId;
        private final Long tenantId;
        private final String status;
        private final String source;
        private final String valueDigest;
        private final Long messagesBefore;
        private final Long attemptsBefore;
        private final Long messagesAfter;
        private final Long attemptsAfter;
    }
}
