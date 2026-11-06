package com.sw.ck.notify.service;

import com.sw.ck.notify.dto.NotifyChannelStatusDTO;

import java.util.List;
import java.util.Map;

/** 租户级渠道启停服务（I6）。 */
public interface NotifyChannelConfigService {

    List<NotifyChannelStatusDTO> listChannelStatus();

    void updateTenantChannel(String channel, boolean enabled, String senderDisplay, String configSummary);

    /**
     * 更新租户级渠道配置（0.2.4：可选凭据入库）。
     *
     * @param configSecret 渠道凭据明文（仅写入路径接收；null/空白 = 保留既有密文；
     *                     非空时以 AES-256-GCM 加密后落库，接口与日志不回读明文）
     */
    void updateTenantChannel(String channel, boolean enabled, String senderDisplay,
                             String configSummary, String configSecret);

    /** 租户内某渠道是否启用（未登记默认按租户配置为停用，IN_APP 恒启用）。 */
    boolean tenantEnabled(String channel);

    /**
     * 解密当前租户该渠道的凭据配置（仅服务端内部消费，如 SMTP 发信）。
     * 未配置密文/密文不可解时返回 empty（调用方回落 yml/env）。
     */
    java.util.Optional<Map<String, String>> resolveChannelConfig(String channel);
}
