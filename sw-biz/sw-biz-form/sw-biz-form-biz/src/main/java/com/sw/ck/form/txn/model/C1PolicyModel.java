package com.sw.ck.form.txn.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * C1 关键数据保护策略（表单模型级声明）。
 * <p>同 {@link TxnActionConfig}：未声明的键被显式收集并由保存入口明确拒绝，不静默忽略。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
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

    /** 模型未声明的键（仅内存收集用于明确拒绝；不序列化、不持久化）。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private final java.util.Map<String, Object> unsupportedKeys = new java.util.LinkedHashMap<>();

    @com.fasterxml.jackson.annotation.JsonAnySetter
    void collectUnsupported(String key, Object value) {
        unsupportedKeys.put(key, value);
    }
}
