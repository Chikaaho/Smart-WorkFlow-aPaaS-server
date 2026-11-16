package com.sw.ck.system.sso;

import lombok.Getter;

/**
 * SSO 目标占用检查结果（DictFacadeImpl 占用检查契约）。
 */
@Getter
public enum SsoBindingOccupancyEnum {
        OCCUPIED("OCCUPIED", "已被占用"),
        MISSING("MISSING", "不存在"),
    ;

    private final String code;
    private final String label;

    SsoBindingOccupancyEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
