package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * IoT 上/下行方向通用词表（Topic.direction 与消息日志 direction 共用）。
 */
@Getter
public enum IotDirectionEnum {
        UP("UP", "上行"),
        DOWN("DOWN", "下行"),
        BOTH("BOTH", "双向"),
    ;

    private final String code;
    private final String label;

    IotDirectionEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
