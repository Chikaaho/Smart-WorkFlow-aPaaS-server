package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P64 Trigger 判断执行记录（ADR-P64-001 §3）：一次评估一行。
 * <p>
 * status: MATCHED / UNMATCHED / FAILED；disposition: EMPTY_COLLECTION / OVER_LIMIT / HALT / IGNORE。
 * {@code snapshot_text} 为本次判断共用的一致数据快照（≤1MiB），判断与动作可按行回查。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_trigger_exec")
public class BpmTriggerExec extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("process_def_key")
    private String processDefKey;

    @TableField("def_version")
    private Integer defVersion;

    @TableField("trigger_id")
    private String triggerId;

    /** TASK_SUBMITTED / NODE_ROUND_COMPLETED / PROCESS_COMPLETED。 */
    @TableField("event_type")
    private String eventType;

    @TableField("node_key")
    private String nodeKey;

    @TableField("round_no")
    private Long roundNo;

    /** 稳定执行身份：TRG:{instance}:{triggerId}:{event}:{round}:{scopeTask?}（唯一键幂等）。 */
    @TableField("exec_key")
    private String execKey;

    @TableField("status")
    private String status;

    @TableField("result_value")
    private String resultValue;

    /** NUMBER / STRING / BOOLEAN / NULL。 */
    @TableField("result_type")
    private String resultType;

    @TableField("matched_branch_id")
    private String matchedBranchId;

    @TableField("disposition")
    private String disposition;

    @TableField("error_text")
    private String errorText;

    @TableField("snapshot_text")
    private String snapshotText;

    @TableField("duration_ms")
    private Long durationMs;
}
