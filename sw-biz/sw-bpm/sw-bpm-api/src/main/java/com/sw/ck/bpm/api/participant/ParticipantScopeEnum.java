package com.sw.ck.bpm.api.participant;

import lombok.Getter;

/**
 * 表单字段参与人作用域契约（scope）：MAIN 主表字段 / TABLE 表格列。
 */
@Getter
public enum ParticipantScopeEnum {

    MAIN("MAIN", "主表字段"),
    TABLE("TABLE", "表格列"),
    ;

    private final String code;
    private final String label;

    ParticipantScopeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
