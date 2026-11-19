package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 指令语义模式（IotDeviceCommand.semanticMode 落库契约）。
 */
@Getter
public enum IotCommandSemanticModeEnum {
        DEFERRED("DEFERRED", "延迟执行"),
        ONLINE_CONFIRM("ONLINE_CONFIRM", "在线确认"),
    ;

    private final String code;
    private final String label;

    IotCommandSemanticModeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
