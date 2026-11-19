package com.sw.ck.system.vo;

import lombok.Data;


/**
 * 租户 SSO 配置列表项（secret 只回是否已配置）。
 */
@Data
public class SsoConfigItemVO {

    private String provider;

    private boolean enabled;

    private String appId;

    private boolean secretConfigured;

    private String identityMode;

    private String configuredEnterpriseId;

    private String callbackUrl;

    private String updateTime;

    private boolean deferred;
}
