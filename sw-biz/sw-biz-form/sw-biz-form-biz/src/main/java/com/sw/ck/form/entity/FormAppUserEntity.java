package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用指定可见用户实体 — 对应 {@code sw_form_app_user} 表。
 * <p>
 * 仅当应用 {@code visible_scope_type=SPECIFIED} 时生效；集合为空表示无人可见
 * （区别于既有表单 visibilityScope 的"空=全租户"语义，不得混用）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_app_user")
public class FormAppUserEntity extends FormBaseEntity {

    /** 应用 ID（sw_form_app.id） */
    private String appId;

    /** 用户 ID（sys_user.id） */
    private Long userId;
}
