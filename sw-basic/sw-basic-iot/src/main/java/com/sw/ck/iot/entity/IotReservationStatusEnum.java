package com.sw.ck.iot.entity;

import lombok.Getter;

/**
 * 指令预约生命周期（IotCommandReservation.status 落库契约）。
 */
@Getter
public enum IotReservationStatusEnum {
        PENDING("PENDING", "待调度"),
        DISPATCHING("DISPATCHING", "调度中"),
        DISPATCHED("DISPATCHED", "已下发"),
        SUCCEEDED("SUCCEEDED", "预约成功"),
        FAILED("FAILED", "预约失败"),
        EXPIRED("EXPIRED", "已过期"),
        CANCELED("CANCELED", "已取消"),
    ;

    private final String code;
    private final String label;

    IotReservationStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
