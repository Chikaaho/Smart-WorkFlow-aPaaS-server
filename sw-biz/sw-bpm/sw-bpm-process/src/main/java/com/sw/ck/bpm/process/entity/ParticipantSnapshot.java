package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 节点进入时冻结的参与人快照。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_participant_snapshot")
public class ParticipantSnapshot extends BaseEntity {
    @TableField("process_instance_id") private String processInstanceId;
    @TableField("node_key") private String nodeKey;
    @TableField("task_id") private String taskId;
    @TableField("participant_id") private String participantId;
    /** 节点进入时冻结的展示名（优先 real_name，其次 username）；null 表示未冻结。 */
    @TableField("participant_name") private String participantName;
    @TableField("participant_status") private String participantStatus;
    @TableField("invalid_reason") private String invalidReason;
}
