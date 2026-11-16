package com.sw.ck.system.sso;

import lombok.Getter;

/**
 * SSO Provider 标识（全 SSO 模块共用词汇）。
 */
@Getter
public enum SsoProviderEnum {
        WECOM("WECOM", "企业微信"),
        FEISHU("FEISHU", "飞书"),
        DINGTALK("DINGTALK", "钉钉"),
        WECHAT_WORK("WECHAT_WORK", "企业微信旧标识"),
    ;

    private final String code;
    private final String label;

    SsoProviderEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
