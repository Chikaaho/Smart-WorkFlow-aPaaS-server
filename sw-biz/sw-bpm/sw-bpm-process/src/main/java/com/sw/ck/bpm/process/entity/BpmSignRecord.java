package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 加签/补签记录（I3 §4.5）：两类动作独立、状态与取消原因全链可追溯。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_sign_record")
public class BpmSignRecord extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("node_key")
    private String nodeKey;

    @TableField("task_id")
    private String taskId;

    /** 类型：ADD_SIGN（加签）/ SUPPLEMENT_SIGN（补签）。 */
    @TableField("sign_type")
    private String signType;

    /** 顺序：SERIAL / PARALLEL。 */
    @TableField("mode_type")
    private String modeType;

    /** 操作人（追加/补充确认的发起主体）。 */
    @TableField("operator_id")
    private Long operatorId;

    /** 被追加/补充确认人。 */
    @TableField("participant_id")
    private Long participantId;

    /** 串行序号（SERIAL 的生效次序）。 */
    @TableField("seq_no")
    private Integer seqNo;

    /** 状态：PENDING / DONE / CANCELLED。 */
    @TableField("sign_status")
    private String signStatus;

    /** 补充确认/加签意见结果：APPROVE / DISAPPROVE。 */
    @TableField("result_status")
    private String resultStatus;

    /** 取消原因。 */
    @TableField("cancel_reason")
    private String cancelReason;

    /** 补签关联的原任务。 */
    @TableField("original_task_id")
    private String originalTaskId;

    /** 补签关联的原节点终态（不改写：REJECTED/TERMINATED/...）。 */
    @TableField("original_status")
    private String originalStatus;

    /** 明细 JSON（意见快照、是否只作审计补充等）。 */
    @TableField("detail")
    private String detail;
}
