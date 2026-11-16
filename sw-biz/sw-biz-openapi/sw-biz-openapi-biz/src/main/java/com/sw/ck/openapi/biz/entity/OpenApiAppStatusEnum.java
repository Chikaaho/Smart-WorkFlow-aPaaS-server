package com.sw.ck.openapi.biz.entity;

import lombok.Getter;

/**
 * OpenAPI 应用状态（OpenApiApp.status 落库契约）。
 */
@Getter
public enum OpenApiAppStatusEnum {
        ENABLED("ENABLED", "启用"),
        DISABLED("DISABLED", "停用"),
    ;

    private final String code;
    private final String label;

    OpenApiAppStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
