package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 流程触发器执行状态（IotProcessTrigger.status 落库契约）。
 */
@Getter
public enum IotTriggerStatusEnum {
        PENDING("PENDING", "待处理"),
        PROCESSING("PROCESSING", "处理中"),
        SUCCESS("SUCCESS", "触发成功"),
        FAILED("FAILED", "触发失败"),
    ;

    private final String code;
    private final String label;

    IotTriggerStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
