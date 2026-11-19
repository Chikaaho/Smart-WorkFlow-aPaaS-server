package com.sw.ck.notify.api;

import lombok.Getter;

/**
 * 发送失败归类（NotifyMessage.failure_class 落库契约）。
 * <p>决定投递恢复任务是否继续重试：RETRYABLE/RETRY_EXHAUSTED 可重试，
 * NON_RETRYABLE 终态不再重试。</p>
 */
@Getter
public enum NotifyFailureClassEnum {

    RETRYABLE("RETRYABLE", "可重试失败"),
    RETRY_EXHAUSTED("RETRY_EXHAUSTED", "重试额度耗尽"),
    NON_RETRYABLE("NON_RETRYABLE", "不可重试失败"),
    ;

    private final String code;
    private final String label;

    NotifyFailureClassEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
