package com.sw.ck.bpm.api.node;

import lombok.Getter;

/**
 * 审批意见字段类型契约（意见采集表单允许的字段类型白名单）。
 * <p>USER 取外部数据源授权主体常量同值（人员字段），保持历史契约不变。</p>
 */
@Getter
public enum OpinionFieldTypeEnum {

    TEXT("TEXT", "单行文本"),
    TEXTAREA("TEXTAREA", "多行文本"),
    NUMBER("NUMBER", "数字"),
    RADIO("RADIO", "单选"),
    CHECKBOX("CHECKBOX", "多选"),
    SELECT("SELECT", "下拉"),
    DATETIME("DATETIME", "日期时间"),
    NOTE("NOTE", "说明文字"),
    USER("USER", "人员"),
    ;

    private final String code;
    private final String label;

    OpinionFieldTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
