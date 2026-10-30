package com.sw.ck.bpm.process.notify;

import com.sw.ck.notify.api.NotifyChannel;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 结束通知配置渠道与平台路由渠道的交集。
 * <p>配置只表达“希望使用的渠道”，实际投递仍以通知域租户启停/订阅/路由为权威；
 * 交集为空时不投递、不虚报送达。</p>
 */
public final class BpmNotifyChannels {

    private BpmNotifyChannels() {
    }

    public static List<NotifyChannel> filter(List<NotifyChannel> routed, List<String> configured) {
        if (routed == null) {
            return List.of();
        }
        if (configured == null || configured.isEmpty()) {
            return routed;
        }
        Set<String> allowed = configured.stream()
                .filter(token -> token != null && !token.isBlank())
                .map(token -> token.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return routed.stream().filter(channel -> allowed.contains(channel.name())).toList();
    }
}
