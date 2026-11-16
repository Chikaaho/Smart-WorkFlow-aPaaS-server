package com.sw.ck.form.service;

import lombok.Getter;

/**
 * 表单可见条件逻辑契约（logic 字段取值）：ALL 全部满足 / ANY 任一满足。
 */
@Getter
public enum FormVisibilityLogicEnum {

    ALL("ALL", "全部满足"),
    ANY("ANY", "任一满足"),
    ;

    private final String code;
    private final String label;

    FormVisibilityLogicEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
