package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用可见范围维度实体 — 对应 {@code sw_form_app_scope} 表（0.1.7 #1）。
 * <p>
 * {@code visible_scope_type=SPECIFIED} 时生效；维度集合为空表示无人可见
 * （区别于表单 visibilityScope 的"空=全租户"语义，不得混用）。
 * 存量 {@code sw_form_app_user} 已由 V0.1.7 迁移为 USER 维度，旧表不再写入。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_app_scope")
public class FormAppScopeEntity extends FormBaseEntity {

    /** 应用 ID（sw_form_app.id） */
    private String appId;

    /** 维度类型：USER / DEPT / ROLE / POST / TENANT（见 {@link FormAppScopeTypes}） */
    private String scopeType;

    /** 维度值：用户/部门/岗位/租户 ID（字符串化）或角色 code */
    private String scopeId;
}
