package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 实例级运营干预审计（I4 §3.3）：挂起/恢复/终止/迁移办理人逐条记录。
 * <p>
 * 与定义级挂起/激活分离；每次干预记录操作者、原因、对象、前后状态与受影响任务数。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_instance_intervention")
public class BpmInstanceIntervention extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    /** SUSPEND / RESUME / TERMINATE / TRANSFER */
    @TableField("action")
    private String action;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("reason")
    private String reason;

    /** 干预前运行态（RUNNING / SUSPENDED / 已终态码） */
    @TableField("before_state")
    private String beforeState;

    @TableField("after_state")
    private String afterState;

    /** 迁移办理人时的原办理人（其他动作为空）。 */
    @TableField("from_assignee")
    private Long fromAssignee;

    @TableField("to_assignee")
    private Long toAssignee;

    /** 受影响任务数（迁移时为该实例活动任务数）。 */
    @TableField("affected_tasks")
    private Integer affectedTasks;

    @TableField("intervened_at")
    private LocalDateTime intervenedAt;
}
