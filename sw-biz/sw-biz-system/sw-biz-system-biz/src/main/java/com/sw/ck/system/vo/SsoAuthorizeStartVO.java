package com.sw.ck.system.vo;

import lombok.Data;


/**
 * SSO 授权发起结果：Provider 授权 URL 与一次性 state。
 */
@Data
public class SsoAuthorizeStartVO {

    private String authorizeUrl;

    private String state;
}
