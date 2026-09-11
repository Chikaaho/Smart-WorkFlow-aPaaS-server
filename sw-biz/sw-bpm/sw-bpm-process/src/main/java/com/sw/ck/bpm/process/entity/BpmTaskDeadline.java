package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 人工节点办理时限与受控自动动作调度账本（I3 §4.8）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_task_deadline")
public class BpmTaskDeadline extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("node_key")
    private String nodeKey;

    @TableField("task_id")
    private String taskId;

    @TableField("due_at")
    private java.time.LocalDateTime dueAt;

    /** 到期提醒是否已触发。 */
    @TableField("remind_fired")
    private Integer remindFired;

    /** 升级/催办是否已触发。 */
    @TableField("escalation_fired")
    private Integer escalationFired;

    /** 受控自动动作：APPROVE / DISAPPROVE / TRANSFER。 */
    @TableField("auto_action")
    private String autoAction;

    /** 自动动作配置 JSON（目标人、说明等）。 */
    @TableField("auto_action_config")
    private String autoActionConfig;

    /** 状态：PENDING / CLAIMED / DONE / CANCELLED。 */
    @TableField("run_state")
    private String runState;

    /** 处理结果：REMIND / ESCALATED / AUTO_HANDLED。 */
    @TableField("result_status")
    private String resultStatus;

    @TableField("last_reason")
    private String lastReason;

    @TableField("handled_at")
    private java.time.LocalDateTime handledAt;
}
