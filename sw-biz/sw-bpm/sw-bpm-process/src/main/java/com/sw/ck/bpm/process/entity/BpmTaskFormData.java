package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P64 任务级节点业务表单数据（ADR-P64-001 §1）。
 * <p>
 * 一行 = 一个任务的数据身份（唯一键 tenant_id+task_id）；status: DRAFT / SUBMITTED；
 * 仅合法最终提交（APPROVE/DISAPPROVE 同事务）进入 SUBMITTED，REJECT/RETURN 不产生有效提交。
 * 数据本体存 {@code data_text}（JSON），form_key+form_version 在行上冻结。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_task_form_data")
public class BpmTaskFormData extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("process_def_key")
    private String processDefKey;

    @TableField("node_key")
    private String nodeKey;

    @TableField("task_id")
    private String taskId;

    /** 轮次：实例 RETURN 动作数 + 1（与 TaskActionService#nextReturnRound 同源）。 */
    @TableField("round_no")
    private Long roundNo;

    @TableField("form_key")
    private String formKey;

    @TableField("form_version")
    private Long formVersion;

    /** DRAFT / SUBMITTED。 */
    @TableField("status")
    private String status;

    /** 表单数据 JSON。 */
    @TableField("data_text")
    private String dataText;

    @TableField("submitted_by")
    private Long submittedBy;

    @TableField("submit_time")
    private java.time.LocalDateTime submitTime;
}
