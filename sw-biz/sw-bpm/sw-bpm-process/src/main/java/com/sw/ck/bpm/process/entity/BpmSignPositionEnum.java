package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 加签位置契约（BpmSignRecord.signPosition 落库契约）。
 */
@Getter
public enum BpmSignPositionEnum {

    BEFORE("BEFORE", "前加签"),
    AFTER("AFTER", "后加签"),
    ;

    private final String code;
    private final String label;

    BpmSignPositionEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
