package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 租户状态变更的可追溯记录；tenant_id 指向被变更租户，operator_id 指向实际操作者。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant_status_audit")
public class SysTenantStatusAudit extends BaseEntity {

    @TableField("action")
    private String action;

    @TableField("old_status")
    private Integer oldStatus;

    @TableField("new_status")
    private Integer newStatus;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("reason")
    private String reason;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    @TableField("result")
    private String result;
}
