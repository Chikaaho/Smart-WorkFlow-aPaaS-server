package com.sw.ck.notify.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.notify.dto.NotifyChannelStatusDTO;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/** 渠道配置控制器（I6）。 */
@RestController
@RequestMapping("/notify/channels")
public class NotifyChannelController {

    private final NotifyChannelConfigService notifyChannelConfigService;

    public NotifyChannelController(NotifyChannelConfigService notifyChannelConfigService) {
        this.notifyChannelConfigService = notifyChannelConfigService;
    }

    /** 渠道状态列表（系统级装配状态 + 租户级启停）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('notify:channel:view')")
    public R<List<com.sw.ck.notify.dto.NotifyChannelStatusDTO>> list() {
        return R.ok(notifyChannelConfigService.listChannelStatus());
    }

    /** 租户级启停；启用前由服务端完成配置完整性判定，构造请求仍须拒绝。 */
    @PostMapping("/{channel}")
    @PreAuthorize("@ss.hasPermi('notify:channel:manage')")
    public R<Void> update(@PathVariable String channel,
                          @RequestBody com.sw.ck.notify.dto.NotifyChannelStatusDTO body) {
        notifyChannelConfigService.updateTenantChannel(channel,
                Boolean.TRUE.equals(body.getTenantEnabled()),
                body.getSenderDisplay(), body.getConfigSummary());
        return R.ok();
    }
}
