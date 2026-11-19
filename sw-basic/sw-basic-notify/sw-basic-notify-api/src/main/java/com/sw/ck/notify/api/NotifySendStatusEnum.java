package com.sw.ck.notify.api;

import lombok.Getter;

/**
 * 通知发送终态（{@link NotifySendResult#status} 契约）。
 * <p>适配器返回值与消息流水落库共用同一词汇；code 即契约字符串。</p>
 */
@Getter
public enum NotifySendStatusEnum {

    SUCCESS("SUCCESS", "发送成功"),
    FAILED("FAILED", "发送失败"),
    ;

    private final String code;
    private final String label;

    NotifySendStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
