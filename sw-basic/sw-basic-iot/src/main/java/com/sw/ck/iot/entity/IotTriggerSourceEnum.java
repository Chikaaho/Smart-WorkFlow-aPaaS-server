package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 触发器来源（IotProcessTrigger.triggerSource 落库契约）。
 */
@Getter
public enum IotTriggerSourceEnum {
        SCRIPT("SCRIPT", "脚本"),
        MESSAGE("MESSAGE", "消息"),
    ;

    private final String code;
    private final String label;

    IotTriggerSourceEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
