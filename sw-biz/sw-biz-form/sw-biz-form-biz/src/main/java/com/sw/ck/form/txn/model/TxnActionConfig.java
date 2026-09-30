package com.sw.ck.form.txn.model;

import lombok.Data;

import java.util.List;

/**
 * 事务动作声明配置（随动作发布冻结）。
 * <p>所有字段绑定在发布时校验：字段必须存在于绑定表单模型且为 NUMBER 类型。</p>
 */
@Data
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
}
