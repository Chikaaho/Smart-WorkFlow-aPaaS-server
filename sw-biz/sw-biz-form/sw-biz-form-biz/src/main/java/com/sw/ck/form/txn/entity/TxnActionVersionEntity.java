package com.sw.ck.form.txn.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动作发布版本快照（不可变）。
 * <p>已受理调用/预占凭据固定原版本；新发布不改变原对象语义。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_txn_action_version", autoResultMap = true)
public class TxnActionVersionEntity extends FormBaseEntity {

    /** 关联 sw_form_txn_action.id */
    private String actionId;

    /** 动作版本号（发布递增） */
    private Integer versionNo;

    /** 发布时绑定的表单版本号 */
    private Integer formVersion;

    /** 冻结的配置快照（JSON） */
    @TableField(value = "config_json", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String configJson;

    /** 发布人 */
    private Long publishedBy;

    /** 发布时间 */
    private java.time.LocalDateTime publishedAt;
}
