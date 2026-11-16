package com.sw.ck.system.dto;

import lombok.Data;

/**
 * 当前用户 SSO 绑定项（外部标识只回摘要前缀）。
 */
@Data
public class SsoBindingItemDTO {

    private String provider;

    private String externalDigestPrefix;
}
