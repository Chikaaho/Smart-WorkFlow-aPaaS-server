package com.sw.ck.bpm.process.entity;

import lombok.Getter;

/**
 * 流程实例状态枚举。
 * <p>
 * {@link #getCode()} 落库为 VARCHAR，对应 {@link BpmInstance#status} 字段。
 * 遵循项目枚举惯例：不使用 IEnum / @EnumValue，手动经 getCode() 存取。
 * </p>
 */
@Getter
public enum InstanceStatusEnum {

    RUNNING("RUNNING", "运行中"),
    APPROVED("APPROVED", "已通过"),
    REJECTED("REJECTED", "已驳回"),
    FAILED("FAILED", "执行失败"),
    ;

    private final String code;
    private final String label;

    InstanceStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static InstanceStatusEnum fromCode(String code) {
        for (InstanceStatusEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown InstanceStatusEnum code: " + code);
    }
}
