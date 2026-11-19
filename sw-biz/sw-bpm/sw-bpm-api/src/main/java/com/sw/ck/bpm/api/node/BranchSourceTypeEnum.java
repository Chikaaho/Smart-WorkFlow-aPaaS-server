package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 动态分支候选来源类型契约（source.type）。
 */
@Getter
public enum BranchSourceTypeEnum {

    FORM_FIELD("FORM_FIELD", "表单字段"),
    VARIABLE("VARIABLE", "流程变量"),
    FIXED("FIXED", "固定列表"),
    ;

    private final String code;
    private final String label;

    BranchSourceTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
