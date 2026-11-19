package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 流程模板状态（BpmProcessTemplate.status 落库契约）。
 */
@Getter
public enum BpmTemplateStatusEnum {

    ENABLED("ENABLED", "启用"),
    DISABLED("DISABLED", "停用"),
    ;

    private final String code;
    private final String label;

    BpmTemplateStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
