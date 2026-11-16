package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 设备指令生命周期（IotDeviceCommand.status 落库契约）。
 */
@Getter
public enum IotCommandStatusEnum {
        PENDING("PENDING", "待发送"),
        QUEUED("QUEUED", "已排队"),
        SENDING("SENDING", "发送中"),
        SENT("SENT", "已发送"),
        DELIVERED("DELIVERED", "已送达"),
        ACKED("ACKED", "设备已确认"),
        SUCCESS("SUCCESS", "执行成功"),
        FAILED("FAILED", "执行失败"),
        EXPIRED("EXPIRED", "已过期"),
        UNKNOWN("UNKNOWN", "回执未知"),
    ;

    private final String code;
    private final String label;

    IotCommandStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
