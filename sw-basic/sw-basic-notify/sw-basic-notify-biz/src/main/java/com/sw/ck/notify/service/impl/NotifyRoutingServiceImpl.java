package com.sw.ck.notify.service.impl;

import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.entity.NotifyRule;
import com.sw.ck.notify.mapper.NotifyRuleMapper;
import com.sw.ck.notify.mapper.NotifySubscriptionMapper;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import com.sw.ck.notify.api.NotifyRoutingService;
import com.sw.ck.notify.service.NotifySubscriptionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统一投递路由实现（I6）。
 * <p>裁决顺序：启用规则（渠道顺序）→ 租户级渠道启停 → 用户订阅偏好；
 * 结果以 IN_APP 保底，保证必须送达事件至少产生站内信；可选项按订阅启用裁剪。</p>
 */
@Service
public class NotifyRoutingServiceImpl implements NotifyRoutingService {

    private final NotifyRuleMapper ruleMapper;
    private final NotifyChannelConfigService channelConfigService;
    private final NotifySubscriptionService subscriptionService;

    public NotifyRoutingServiceImpl(NotifyRuleMapper ruleMapper,
                                    NotifyChannelConfigService channelConfigService,
                                    NotifySubscriptionService subscriptionService) {
        this.ruleMapper = ruleMapper;
        this.channelConfigService = channelConfigService;
        this.subscriptionService = subscriptionService;
    }

    @Override
    public List<NotifyChannel> channelsFor(String eventType, Long recipientId) {
        List<NotifyRule> rules = enabledRules(eventType);
        Set<String> tokens = new LinkedHashSet<>();
        if (rules.isEmpty()) {
            tokens.add("IN_APP");
        } else {
            for (NotifyRule rule : rules) {
                if (rule.getChannelPriority() == null || rule.getChannelPriority().isBlank()) {
                    continue;
                }
                for (String token : rule.getChannelPriority().split(",")) {
                    String name = token.trim().toUpperCase(Locale.ROOT);
                    if (!name.isEmpty()) {
                        tokens.add(name);
                    }
                }
            }
        }
        List<NotifyChannel> channels = new ArrayList<>();
        for (String token : tokens) {
            NotifyChannel channel;
            try {
                channel = NotifyChannel.valueOf(token);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (channel == NotifyChannel.WECHAT_OFFICIAL || channel == NotifyChannel.WECHAT_MINI_PROGRAM) {
                continue;
            }
            // 租户级启停裁剪（IN_APP 恒启用）
            if (!channelConfigService.tenantEnabled(channel.name())) {
                continue;
            }
            // 订阅裁剪：用户显式关闭且该事件/渠道可关闭时跳过
            if (channel != NotifyChannel.IN_APP && isSubscriptionDisabled(recipientId, eventType, channel.name())) {
                continue;
            }
            channels.add(channel);
        }
        if (channels.isEmpty()) {
            channels.add(NotifyChannel.IN_APP);
        }
        return channels;
    }

    @Override
    public boolean required(String eventType) {
        return enabledRules(eventType).stream().anyMatch(r ->
                r.getRequiredFlag() != null && r.getRequiredFlag() == 1);
    }

    // ==================== 内部 ====================

    private List<NotifyRule> enabledRules(String eventType) {
        return ruleMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<NotifyRule>lambdaQuery()
                .eq(NotifyRule::getEventType, eventType)
                .eq(NotifyRule::getEnabled, 1));
    }

    /** 仅显式登记的关闭偏好生效；未登记默认启用（新事件不被误关）。 */
    private boolean isSubscriptionDisabled(Long recipientId, String eventType, String channel) {
        return subscriptionService.preferences(recipientId).stream()
                .anyMatch(v -> eventType.equals(v.getEventType())
                        && channel.equalsIgnoreCase(v.getChannel())
                        && Boolean.FALSE.equals(v.getEnabled()));
    }
}
