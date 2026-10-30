package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 当前办理人的审批意见暂存；不完成任务、不产生已办或通知。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_task_opinion_draft")
public class BpmTaskOpinionDraft extends BaseEntity {
    @TableField("task_id")
    private String taskId;
    @TableField("process_instance_id")
    private String processInstanceId;
    @TableField("node_key")
    private String nodeKey;
    @TableField("round_no")
    private Integer roundNo;
    @TableField("assignee_id")
    private Long assigneeId;
    @TableField("opinion_form_id")
    private String opinionFormId;
    @TableField("opinion_form_version")
    private String opinionFormVersion;
    @TableField("comment")
    private String comment;
    @TableField("opinion_data")
    private String opinionData;
}
