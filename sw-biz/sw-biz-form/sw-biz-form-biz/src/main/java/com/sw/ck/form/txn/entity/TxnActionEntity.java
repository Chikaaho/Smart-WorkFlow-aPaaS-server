package com.sw.ck.form.txn.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.type.JdbcType;
import com.sw.ck.form.entity.FormBaseEntity;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 低代码事务动作定义（草稿/发布态）。
 * <p>P62 首事务阶段：动作由设计者声明并发布（版本冻结），业务调用经执行引擎完成受控本地事务。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_txn_action", autoResultMap = true)
public class TxnActionEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id（动作绑定表单模型） */
    private String formId;

    /** 动作业务标识（租户+表单内唯一） */
    private String actionKey;

    /** 动作名称 */
    private String name;

    /** 动作类型：RESERVE/CONFIRM/RELEASE/ADJUST */
    private String actionType;

    /** 状态：DRAFT/PUBLISHED/DISABLED */
    private String status;

    /** 当前已发布版本号（未发布为空） */
    private Integer currentVersion;

    /** 动作声明配置（字段绑定/约束/预占 TTL，JSON） */
    @TableField(value = "config_json", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String configJson;

    /** 描述 */
    private String description;
}
