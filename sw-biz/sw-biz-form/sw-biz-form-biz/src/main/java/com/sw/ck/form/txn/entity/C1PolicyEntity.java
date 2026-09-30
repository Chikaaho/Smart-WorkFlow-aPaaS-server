package com.sw.ck.form.txn.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * C1 关键数据保护策略（表单模型级声明）。
 * <p>启用后：普通表单写入口（提交/更新/删除/导入/API）拒绝受保护字段；写入只经受控事务动作。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_c1_policy", autoResultMap = true)
public class C1PolicyEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id */
    private String formId;

    /** 1=启用 C1 保护 */
    private Integer enabled;

    /** 策略 JSON（受保护字段/余额与预占字段/非负约束） */
    @TableField(value = "policy_json", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String policyJson;

    /** 生效时刻 */
    private LocalDateTime appliedAt;
}
