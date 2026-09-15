package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单生命周期审计实体 — 对应 {@code sw_form_lifecycle_audit} 表（I2，方向 §4.4）。
 * <p>
 * 记录停用/启用/删除及拒绝原因；前端隐藏入口不能替代服务端引用和状态检查，
 * 所有状态迁移在服务端落审计。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_lifecycle_audit")
public class FormLifecycleAuditEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id */
    private String formId;

    /** 动作：DISABLE / ENABLE / DELETE_DELETE_DENIED / DISABLE_DENIED 等 */
    private String action;

    /** 原因或拒绝原因 */
    private String reason;

    /** 操作人用户 ID */
    private Long operatorId;
}
