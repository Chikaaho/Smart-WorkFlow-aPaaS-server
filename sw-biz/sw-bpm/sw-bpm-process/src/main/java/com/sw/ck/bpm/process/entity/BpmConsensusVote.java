package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 会签动作计数（I3 §4.5）：DB 唯一键保证多实例部署下同任务同人只产生一次合法计数。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_consensus_vote")
public class BpmConsensusVote extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("node_key")
    private String nodeKey;

    @TableField("task_id")
    private String taskId;

    @TableField("actor_id")
    private Long actorId;

    /** 表决结果：APPROVE / DISAPPROVE。 */
    @TableField("outcome")
    private String outcome;
}
