package com.sw.ck.notify.api;

import lombok.Getter;

/**
 * 通知目标权威来源（{@link NotifyTargetResolution#source} 契约）。
 * <p>当前唯一权威来源为系统用户表；新增来源时在此扩展，禁止调用方自造字符串。</p>
 */
@Getter
public enum NotifyTargetSourceEnum {

    SYS_USER("SYS_USER", "系统用户表"),
    ;

    private final String code;
    private final String label;

    NotifyTargetSourceEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
