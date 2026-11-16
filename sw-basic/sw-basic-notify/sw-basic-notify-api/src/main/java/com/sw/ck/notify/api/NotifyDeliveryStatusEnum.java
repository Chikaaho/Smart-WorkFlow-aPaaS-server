package com.sw.ck.notify.api;

import lombok.Getter;

/**
 * 消息投递状态（NotifyMessage.delivery_status 落库契约）。
 * <p>PENDING 待投递 → RESENDING 重投中 → SUCCESS/FAILED/TIMEOUT 终态。</p>
 */
@Getter
public enum NotifyDeliveryStatusEnum {

    PENDING("PENDING", "待投递"),
    RESENDING("RESENDING", "重投中"),
    SUCCESS("SUCCESS", "投递成功"),
    FAILED("FAILED", "投递失败"),
    TIMEOUT("TIMEOUT", "投递超时"),
    ;

    private final String code;
    private final String label;

    NotifyDeliveryStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
