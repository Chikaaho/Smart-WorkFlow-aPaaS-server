package com.sw.ck.form.txn.model;

import lombok.Data;

import java.util.List;

/**
 * C1 关键数据保护策略（表单模型级声明）。
 */
@Data
public class C1PolicyModel {

    /** 是否启用保护 */
    private Boolean enabled;

    /** 受保护字段（普通写入口拒绝写入这些字段） */
    private List<String> protectedFields;

    /** 余额字段（启用非负校验时的约束对象，可空） */
    private String balanceField;

    /** 预占字段（启用非负校验时的约束对象，可空） */
    private String reservedField;

    /** 启用时是否校验既有数据满足 可用=余额-预占 >= 0（默认 true，字段齐备时生效） */
    private Boolean nonNegativeAvailable;
}
