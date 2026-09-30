package com.sw.ck.form.txn.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 事务动作台账：每个业务效果一行（RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST），供勾稽复算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_txn_ledger", autoResultMap = true)
public class TxnLedgerEntity extends FormBaseEntity {

    /** 关联 sw_form_txn_action.id */
    private String actionId;

    /** 动作版本 */
    private Integer actionVersion;

    /** 产生该效果的调用 id */
    private String invocationId;

    /** 关联预占凭据（ADJUST 可为空） */
    private String reservationId;

    /** 效果类型：RESERVE/CONFIRM/RELEASE/EXPIRE/ADJUST */
    private String entryType;

    /** 目标表单 id */
    private String formId;

    /** 目标记录 id */
    private String recordId;

    /** 数量（正负按效果语义） */
    private BigDecimal quantity;

    /** 效果后余额 */
    private BigDecimal balanceAfter;

    /** 效果后有效预占 */
    private BigDecimal reservedAfter;

    /** 声明业务键快照（JSON） */
    @TableField(value = "biz_keys_json", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String bizKeysJson;
}
