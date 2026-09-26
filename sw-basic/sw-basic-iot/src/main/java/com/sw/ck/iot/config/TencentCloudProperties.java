package com.sw.ck.iot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云 IoT Explorer 配置属性。
 * <p>
 * SecretId/SecretKey 只能来自安全配置或环境变量，禁止进入数据库、普通日志、回执和前端。
 * </p>
 */
@Data
@ConfigurationProperties(prefix = "sw.iot.tencent")
public class TencentCloudProperties {

    /**
     * 是否启用腾讯云 IoT Provider（true: 使用腾讯 SDK；false: 不启用腾讯 SDK 接入）。
     */
    private boolean enabled = false;

    /**
     * 腾讯云地域（如 ap-guangzhou）。
     */
    private String region = "ap-guangzhou";

    /**
     * 云 API endpoint。
     */
    private String endpoint = "iotexplorer.tencentcloudapi.com";

    /**
     * 腾讯云 SecretId（从环境变量注入，禁止硬编码）。
     */
    private String secretId;

    /**
     * 腾讯云 SecretKey（从环境变量注入，禁止硬编码）。
     */
    private String secretKey;

    /**
     * 命令队列过期时间（分钟）。
     */
    private int queueExpiryMinutes = 1440; // 24 小时

    /**
     * 最大重试次数。
     */
    private int maxRetryCount = 3;

    /**
     * Provider 模式：{@code tencent} 装配腾讯云 Provider；{@code mock} 只在 dev 源根的
     * dev 装配类下有效（正式制品无模拟实现）；{@code none}（默认）不装配任何 provider。
     *
     * <p>默认值刻意不是 {@code mock}：未配置时生产侧不装配 provider，需要 provider 的操作
     * 在调用点 fail closed，不产生模拟成功。</p>
     */
    private String providerMode = "none";

    /**
     * 检查腾讯云凭证是否已配置。
     */
    public boolean hasCredentials() {
        return secretId != null && !secretId.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    /**
     * 检查是否应使用腾讯 Provider（显式 {@code tencent} 模式且凭证完整）。
     */
    public boolean shouldUseTencent() {
        return "tencent".equals(providerMode) && hasCredentials();
    }
}
