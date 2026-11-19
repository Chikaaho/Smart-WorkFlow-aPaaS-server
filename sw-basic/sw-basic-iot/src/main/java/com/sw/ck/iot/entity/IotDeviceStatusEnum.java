package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 设备在线状态（IotDevice.status 落库契约）。
 */
@Getter
public enum IotDeviceStatusEnum {
        ONLINE("ONLINE", "在线"),
        OFFLINE("OFFLINE", "离线"),
    ;

    private final String code;
    private final String label;

    IotDeviceStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
