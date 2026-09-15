package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** V75：节点函数调用审计（I3 §4.9——运行/失败完整审计）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_node_function_audit")
public class BpmNodeFunctionAudit extends BaseEntity {
    @TableField("func_key") private String funcKey;
    @TableField("func_version") private Integer funcVersion;
    @TableField("func_type") private String funcType;
    @TableField("process_instance_id") private String processInstanceId;
    @TableField("node_key") private String nodeKey;
    @TableField("idempotent_key") private String idempotentKey;
    @TableField("actor_id") private Long actorId;
    @TableField("outcome") private String outcome;
    @TableField("error_code") private Integer errorCode;
    @TableField("summary") private String summary;
    @TableField("duration_ms") private Long durationMs;
}
