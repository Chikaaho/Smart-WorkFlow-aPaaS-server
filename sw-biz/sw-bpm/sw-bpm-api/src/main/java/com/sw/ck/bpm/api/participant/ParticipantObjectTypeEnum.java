package com.sw.ck.bpm.api.participant;

import lombok.Getter;

/**
 * 表单字段参与人对象类型契约（FORM_FIELD.objectType）：USER 所选人员审批 /
 * DEPT 服务端按权威组织关系解析各部门唯一负责人。
 */
@Getter
public enum ParticipantObjectTypeEnum {

    USER("USER", "人员"),
    DEPT("DEPT", "部门"),
    ;

    private final String code;
    private final String label;

    ParticipantObjectTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
