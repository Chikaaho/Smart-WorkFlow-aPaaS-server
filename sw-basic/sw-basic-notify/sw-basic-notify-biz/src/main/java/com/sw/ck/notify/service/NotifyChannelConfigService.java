package com.sw.ck.notify.service;

import com.sw.ck.notify.dto.NotifyChannelStatusDTO;

import java.util.List;

/** 租户级渠道启停服务（I6）。 */
public interface NotifyChannelConfigService {

    List<NotifyChannelStatusDTO> listChannelStatus();

    void updateTenantChannel(String channel, boolean enabled, String senderDisplay, String configSummary);

    /** 租户内某渠道是否启用（未登记默认按租户配置为停用，IN_APP 恒启用）。 */
    boolean tenantEnabled(String channel);
}
