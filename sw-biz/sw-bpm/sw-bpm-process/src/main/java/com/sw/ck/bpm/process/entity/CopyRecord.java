package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 抄送节点投递审计记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_copy_record")
public class CopyRecord extends BaseEntity {
    @TableField("process_instance_id") private String processInstanceId;
    @TableField("node_key") private String nodeKey;
    @TableField("task_id") private String taskId;
    @TableField("recipient_id") private String recipientId;
    @TableField("delivery_status") private String deliveryStatus;
    @TableField("failure_reason") private String failureReason;
}
