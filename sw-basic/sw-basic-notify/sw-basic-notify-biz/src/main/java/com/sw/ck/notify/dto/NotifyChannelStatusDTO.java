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
}
