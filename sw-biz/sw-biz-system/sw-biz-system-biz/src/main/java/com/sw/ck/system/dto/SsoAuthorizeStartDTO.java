package com.sw.ck.system.dto;

import lombok.Data;

/**
 * SSO 授权发起结果：Provider 授权 URL 与一次性 state。
 */
@Data
public class SsoAuthorizeStartDTO {

    private String authorizeUrl;

    private String state;
}
