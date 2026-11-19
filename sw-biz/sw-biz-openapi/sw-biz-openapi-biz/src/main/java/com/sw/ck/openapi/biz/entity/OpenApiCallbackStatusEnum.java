package com.sw.ck.openapi.biz.entity;

import lombok.Getter;

/**
 * OpenAPI 回调任务状态（OpenApiCallbackTask.status 落库契约）。
 */
@Getter
public enum OpenApiCallbackStatusEnum {
        PENDING("PENDING", "待投递"),
        SENDING("SENDING", "投递中"),
        SUCCESS("SUCCESS", "投递成功"),
        FAILED("FAILED", "投递失败"),
        RETRY_EXHAUSTED("RETRY_EXHAUSTED", "重试额度耗尽"),
    ;

    private final String code;
    private final String label;

    OpenApiCallbackStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
