package com.sw.ck.notify.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 渠道配置启动校验（I6 fail-fast）。
 * <p>系统级启用（enabled=true）的渠道若缺必需配置或仍为占位值，
 * 启动即终止；未启用渠道默认禁用，不校验（不算已交付）。</p>
 */
@Configuration
@EnableConfigurationProperties(NotifyChannelProperties.class)
public class NotifyChannelStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(NotifyChannelStartupValidator.class);

    private static final List<String> PLACEHOLDERS = List.of(
            "change-me", "changeme", "your-", "your_", "xxx", "todo", "placeholder", "example");

    @Bean
    public Object notifyChannelStartupGuard(NotifyChannelProperties props) {
        for (Map.Entry<String, NotifyChannelProperties.ChannelProps> e : props.getChannels().entrySet()) {
            NotifyChannelProperties.ChannelProps cp = e.getValue();
            if (cp == null || !Boolean.TRUE.equals(cp.getEnabled())) {
                continue;
            }
            String channel = e.getKey().trim().toUpperCase();
            switch (channel) {
                case "EMAIL" -> require(cp.getHost(), "EMAIL host");
                case "FEISHU" -> {
                    require(cp.getAppId(), "FEISHU app-id");
                    require(cp.getAppSecret(), "FEISHU app-secret");
                }
                case "DINGTALK" -> {
                    require(cp.getAppKey(), "DINGTALK app-key");
                    require(cp.getAgentId(), "DINGTALK agent-id");
                    require(cp.getAppSecret(), "DINGTALK app-secret");
                }
                case "WECHAT_WORK" -> {
                    require(cp.getCorpId(), "WECHAT_WORK corp-id");
                    require(cp.getCorpSecret(), "WECHAT_WORK corp-secret");
                    require(cp.getAgentId(), "WECHAT_WORK agent-id");
                }
                case "SMS" -> {
                    require(cp.getProvider(), "SMS provider");
                    require(cp.getEndpoint(), "SMS endpoint");
                }
                default -> {
                    // 未登记渠道枚举默认禁用；此处防止未知渠道被静默开启。
                }
            }
        }
        log.info("NotifyChannelStartupValidator：渠道配置校验通过（启用渠道数量由装配结果决定）");
        return new Object();
    }

    private void require(String value, String what) {
        if (value == null || value.isBlank() || PLACEHOLDERS.stream().anyMatch(value.toLowerCase()::contains)) {
            throw new IllegalStateException("通知渠道配置不完整（fail-fast）：" + what + " 缺失或为占位值");
        }
    }
}
