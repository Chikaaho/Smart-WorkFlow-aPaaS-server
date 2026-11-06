package com.sw.ck.notify.dto;

import lombok.Data;

/** 渠道状态 DTO（I6）：系统级配置状态与租户级启停。 */
@Data
public class NotifyChannelStatusDTO {
    private String channel;
    /** 系统级 Adapter 是否已装配（配置完整且启用，或 IN_APP/站内信恒 true） */
    private Boolean systemConfigured;
    /** 租户级是否启用 */
    private Boolean tenantEnabled;
    private String senderDisplay;
    private String configSummary;
    /** 渠道凭据是否已入库（密文存在；仅状态，不回读明文） */
    private Boolean hasSecret;
    /** 渠道凭据明文（仅写入路径使用；服务端加密落库后不保存、不回显） */
    private String configSecret;
}
