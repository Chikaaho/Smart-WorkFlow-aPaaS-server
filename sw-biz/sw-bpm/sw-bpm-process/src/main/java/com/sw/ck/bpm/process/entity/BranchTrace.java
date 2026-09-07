package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 条件分支实际命中轨迹。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_branch_trace")
public class BranchTrace extends BaseEntity {
    @TableField("process_instance_id") private String processInstanceId;
    @TableField("node_key") private String nodeKey;
    @TableField("branch_id") private String branchId;
    @TableField("condition_version") private String conditionVersion;
    @TableField("input_summary") private String inputSummary;
}
