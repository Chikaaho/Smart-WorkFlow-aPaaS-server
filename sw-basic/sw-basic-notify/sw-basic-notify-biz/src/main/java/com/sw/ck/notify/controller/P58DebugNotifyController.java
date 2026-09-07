package com.sw.ck.notify.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import lombok.Data;
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

    public P58DebugNotifyController(NotifyFacade notifyFacade) {
        this.notifyFacade = notifyFacade;
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

    @Data
    public static class Request {
        private Long recipientId;
        private String title;
        private String content;
        private String bizId;
        private String channel;
        private String idempotencyKey;
    }
}
