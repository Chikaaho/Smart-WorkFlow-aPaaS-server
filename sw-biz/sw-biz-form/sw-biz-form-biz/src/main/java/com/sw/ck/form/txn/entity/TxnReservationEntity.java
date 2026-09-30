package com.sw.ck.form.txn.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预占凭据：生命周期 ACTIVE→CONFIRMED / RELEASED / EXPIRED。
 * <p>确认与过期释放竞争以「状态=ACTIVE」条件更新裁决，仅一次合法结算。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_txn_reservation", autoResultMap = true)
public class TxnReservationEntity extends FormBaseEntity {

    /** 关联 sw_form_txn_action.id（RESERVE 动作；生命周期沿用该版本语义） */
    private String actionId;

    /** 预占受理时的动作版本 */
    private Integer actionVersion;

    /** 目标表单 id */
    private String formId;

    /** 目标动态宽表记录 id */
    private String recordId;

    /** 声明业务键快照（JSON） */
    @TableField(value = "biz_keys_json", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String bizKeysJson;

    /** 预占数量 */
    private BigDecimal quantity;

    /** 状态：ACTIVE/CONFIRMED/RELEASED/EXPIRED */
    private String status;

    /** 过期时刻（过期后不可确认） */
    private LocalDateTime expiresAt;

    /** 受理调用 id */
    private String reserveInvocationId;

    /** 结算调用 id（确认/释放/过期） */
    private String settleInvocationId;

    /** 结算时刻 */
    private LocalDateTime settledAt;
}
