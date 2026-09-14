package com.sw.ck.notify.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知渠道系统级配置（I6）。
 * <p>键位 {@code sw.notify.channels.<CHANNEL>.<PROP>}。秘密只允许经环境变量注入；
 * 本配置在启动期 fail-fast（{@link NotifyChannelStartupValidator}）：渠道被启用但
 * 必需配置缺失或仍为占位值时拒绝启动。未启用渠道不校验（默认禁用不算已交付）。</p>
 */
@Data
@ConfigurationProperties(prefix = "sw.notify.channels")
public class NotifyChannelProperties {

    /** 渠道 → 渠道级配置；enabled 为系统级开关。 */
    private Map<String, ChannelProps> channels = new LinkedHashMap<>();

    @Data
    public static class ChannelProps {
        private Boolean enabled = Boolean.FALSE;

        // ---- EMAIL ----
        private String host;
        private Integer port;
        private Boolean starttls = Boolean.TRUE;
        /** 发件人地址（租户内发件展示名走 DB） */
        private String from;
        private String username;
        /** SMTP 密码，必须由环境变量/秘密管理注入 */
        private String password;

        // ---- FEISHU ----
        private String appId;
        private String feishuAppSecret;
        /** 自建应用访问基址（默认官方域；沙箱可在配置覆盖） */
        private String apiBase = "https://open.feishu.cn";

        // ---- DINGTALK ----
        private String appKey;
        private String agentId;
        private String appSecret;

        // ---- WECHAT_WORK ----
        private String corpId;
        private String corpSecret;

        // ---- SMS（通用短信契约；Provider 选型由 Owner 裁决后按同一契约落地） ----
        private String provider;
        private String signName;
        private String templateCode;
        private String accessKey;
        private String accessSecret;
        private String endpoint;
    }
}
