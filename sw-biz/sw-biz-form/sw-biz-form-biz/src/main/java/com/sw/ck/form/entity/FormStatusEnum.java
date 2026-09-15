package com.sw.ck.form.entity;

/**
 * 表单发布状态枚举。
 */
public enum FormStatusEnum {

    /** 草稿态：只写元数据，不碰物理表 */
    DRAFT("DRAFT", "草稿"),

    /** 发布态：动态宽表已建，表名/字段名冻结 */
    PUBLISHED("PUBLISHED", "已发布"),

    /**
     * 停用态（I2）：禁止新的绑定、填报、正式提交和流程发起；
     * 既有运行实例、审批查看和历史记录继续按冻结快照可读。
     */
    DISABLED("DISABLED", "已停用");

    private final String code;
    private final String label;

    FormStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean isDraft() {
        return this == DRAFT;
    }

    public boolean isPublished() {
        return this == PUBLISHED;
    }

    public boolean isDisabled() {
        return this == DISABLED;
    }

    public static FormStatusEnum fromCode(String code) {
        for (FormStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown form status: " + code);
    }
}
