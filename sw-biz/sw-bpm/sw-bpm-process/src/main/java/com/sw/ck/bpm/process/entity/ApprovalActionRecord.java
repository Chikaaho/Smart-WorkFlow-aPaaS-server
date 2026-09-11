package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不可变审批动作与意见快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_approval_action")
public class ApprovalActionRecord extends BaseEntity {
    @TableField("process_instance_id") private String processInstanceId;
    @TableField("node_key") private String nodeKey;
    @TableField("task_id") private String taskId;
    @TableField("actor_id") private Long actorId;
    @TableField("action") private String action;
    @TableField("opinion_form_id") private String opinionFormId;
    @TableField("opinion_form_version") private String opinionFormVersion;
    @TableField("initialization_summary") private String initializationSummary;
    @TableField("opinion_data") private String opinionData;
    @TableField("settlement_status") private String settlementStatus;
    /** 产生本动作的受理命令（命令通道重放时据此恢复自身已提交结果；同步入口为 null）。 */
    @TableField("command_id") private Long commandId;

    // ==================== I3 扩展（V65/V66） ====================

    /** 动作目标人（转办转入人 / 加签目标 / 代理受托人）。 */
    @TableField("target_user_id") private Long targetUserId;

    /** 原责任人（转办转出人 / 委托委托人）。 */
    @TableField("proxy_for_user_id") private Long proxyForUserId;

    /** 办理轮次。 */
    @TableField("round_no") private Integer roundNo;

    /** 关联任务（加签/补签原任务、委托回归任务）。 */
    @TableField("related_task_id") private String relatedTaskId;

    /** 动作说明 JSON（取消原因、目标、明细）。 */
    @TableField("detail") private String detail;

    /** 意见表单不可变快照（提交动作时冻结；后续表单版本不改变历史解释）。 */
    @TableField("opinion_form_snapshot") private String opinionFormSnapshot;
}
