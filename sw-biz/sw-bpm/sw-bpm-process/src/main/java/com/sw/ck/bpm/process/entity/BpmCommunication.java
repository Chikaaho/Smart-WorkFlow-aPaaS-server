package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 沟通征询（I3 §4.7）：不转移任务责任、不授予审批结算权；回复独立于审批意见保存。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_communication")
public class BpmCommunication extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("node_key")
    private String nodeKey;

    @TableField("task_id")
    private String taskId;

    /** 办理轮次。 */
    @TableField("round_no")
    private Integer roundNo;

    /** 发起人（当前办理责任人）。 */
    @TableField("initiator_id")
    private Long initiatorId;

    /** 接收人。 */
    @TableField("receiver_id")
    private Long receiverId;

    /** 征询内容。 */
    @TableField("message")
    private String message;

    /** 回复内容（独立保存）。 */
    @TableField("reply_message")
    private String replyMessage;

    /** 状态：PENDING / REPLIED / CANCELLED。 */
    @TableField("status")
    private String status;

    @TableField("reply_time")
    private java.time.LocalDateTime replyTime;
}
