package com.sw.ck.form.txn.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务动作声明配置（随动作发布冻结）。
 * <p>所有字段绑定在发布时校验：字段必须存在于绑定表单模型且为 NUMBER 类型。</p>
 * <p>配置模型是封闭集合：模型不支持的键（例如人工等待、远程副作用、自定义事务传播）被显式收集，
 * 由保存与发布入口明确拒绝（错误定位到具体键），不做静默忽略——避免设计者以为已声明了平台不支持的能力。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class TxnActionConfig {

    /** 余额字段（逻辑字段名；RESERVE/CONFIRM/RELEASE/ADJUST 必填） */
    private String balanceField;

    /** 预占字段（逻辑字段名；RESERVE/CONFIRM/RELEASE 必填） */
    private String reservedField;

    /** 声明业务键字段（物料/库位等，可空；写入预占与台账快照） */
    private List<String> keyFields;

    /** 数量精度（小数位，0-6；默认 3，超出即拒绝不静默舍入） */
    private Integer quantityScale;

    /** 预占有效期秒数（RESERVE 必填，>0） */
    private Long expiresInSeconds;

    /** 是否强制可用量非负（可用=余额-有效预占；默认 true） */
    private Boolean nonNegativeAvailable;

    /** 模型未声明的键（仅内存收集用于明确拒绝；不序列化、不持久化）。 */
    @JsonIgnore
    private final Map<String, Object> unsupportedKeys = new LinkedHashMap<>();

    @JsonAnySetter
    void collectUnsupported(String key, Object value) {
        unsupportedKeys.put(key, value);
    }
}
