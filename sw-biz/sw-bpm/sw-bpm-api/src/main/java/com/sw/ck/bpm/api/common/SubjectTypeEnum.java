package com.sw.ck.bpm.api.common;

import lombok.Getter;

/**
 * 外部数据源授权主体类型契约（grant.subject_type / FORM_FIELD.objectType 等共用词汇）。
 * <p>engine 侧 {@code ExternalDatasourceGrant.SUBJECT_*} 常量委托本枚举，新代码直接引用枚举。</p>
 */
@Getter
public enum SubjectTypeEnum {

    USER("USER", "用户"),
    ROLE("ROLE", "角色"),
    DEPT("DEPT", "部门"),
    ;

    private final String code;
    private final String label;

    SubjectTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
