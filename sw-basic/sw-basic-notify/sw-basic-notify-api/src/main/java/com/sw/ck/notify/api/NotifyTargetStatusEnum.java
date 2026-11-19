package com.sw.ck.notify.api;

import lombok.Getter;

/**
 * 通知目标解析状态（{@link NotifyTargetResolution#status} 契约）。
 * <p>RESOLVED 之外的状态均为不可发送终态，只允许进入审计与告警，不得伪造发送成功。</p>
 */
@Getter
public enum NotifyTargetStatusEnum {

    RESOLVED("RESOLVED", "解析成功"),
    UNRESOLVED("UNRESOLVED", "未能解析"),
    MISSING("MISSING", "目标缺失"),
    USER_NOT_FOUND("USER_NOT_FOUND", "用户不存在"),
    AMBIGUOUS("AMBIGUOUS", "多目标歧义"),
    INVALID("INVALID", "目标非法"),
    RESOLVER_UNAVAILABLE("RESOLVER_UNAVAILABLE", "解析器不可用"),
    ;

    private final String code;
    private final String label;

    NotifyTargetStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
