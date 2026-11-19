package com.sw.ck.system.sso;

import lombok.Getter;

/**
 * SSO 绑定状态（bind_status 落库契约）。
 */
@Getter
public enum SsoBindStatusEnum {
        ACTIVE("ACTIVE", "生效中"),
        UNBOUND("UNBOUND", "已解绑"),
    ;

    private final String code;
    private final String label;

    SsoBindStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
