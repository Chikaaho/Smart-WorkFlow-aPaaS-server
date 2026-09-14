package com.sw.ck.notify.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.notify.dto.NotifySubscriptionSaveReq;
import com.sw.ck.notify.dto.NotifySubscriptionView;
import com.sw.ck.notify.service.NotifySubscriptionService;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户订阅偏好控制器（I6）；仅允许调整可选事件/渠道。 */
@RestController
@RequestMapping("/notify/subscriptions")
public class NotifyPreferenceController {

    private final NotifySubscriptionService notifySubscriptionService;

    public NotifyPreferenceController(NotifySubscriptionService notifySubscriptionService) {
        this.notifySubscriptionService = notifySubscriptionService;
    }

    @GetMapping
    public R<List<com.sw.ck.notify.dto.NotifySubscriptionView>> mine() {
        return R.ok(notifySubscriptionService.preferences(LoginUserHolder.get().getUserId()));
    }

    @PostMapping
    public R<Void> save(@RequestBody com.sw.ck.notify.dto.NotifySubscriptionSaveReq req) {
        notifySubscriptionService.save(LoginUserHolder.get().getUserId(), req);
        return R.ok();
    }
}
