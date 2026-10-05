package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态并行分支冻结快照（I4 §3.1）。
 * <p>
 * 进入节点时冻结来源部门、负责人与汇聚策略；运行中表单或组织变化不改写本表。
 * 无效对象（失效部门/负责人缺失/跨租户）同样落行，status=CANCELED 且 cancel_reason 记录原因。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_dynamic_branch")
public class DynamicBranchSnapshot extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("node_key")
    private String nodeKey;

    /** 进入节点的稳定顺序（0 起）。 */
    @TableField("branch_index")
    private Integer branchIndex;

    @TableField("source_type")
    private String sourceType;

    /** 来源描述：字段名/变量名/固定部门集合。 */
    @TableField("source_value")
    private String sourceValue;

    /** 本分支承担的部门 ID（去重合并后，逗号分隔）。 */
    @TableField("dept_ids")
    private String deptIds;

    @TableField("leader_id")
    private Long leaderId;

    /** 汇聚策略（进入节点时冻结）：ALL/ANY/RATIO/VETO。 */
    @TableField("converge_mode")
    private String convergeMode;

    /** START / APPROVE / DISAPPROVE / CANCELED。 */
    @TableField("status")
    private String status;

    @TableField("task_id")
    private String taskId;

    @TableField("cancel_reason")
    private String cancelReason;

    /** P63 语义版本：NULL=旧语义（按负责人合并）；2=对象身份分支 + 轮次 + 来源行追溯。 */
    @TableField("semantic_version")
    private Integer semanticVersion;

    /** P63 分支对象类型：USER / DEPT。 */
    @TableField("object_type")
    private String objectType;

    /** P63 分支对象 ID（稳定身份；同负责人不同部门保持独立行）。 */
    @TableField("object_id")
    private String objectId;

    /** P63 办理轮次（0 起，按多实例根 executionId 区分；旧语义行恒 NULL）。 */
    @TableField("round_no")
    private Long roundNo;

    /** P63 本轮多实例根 executionId（同 execution 恢复复用，新 execution 新轮次）。 */
    @TableField("execution_id")
    private String executionId;

    /** P63 来源位置 JSON：[{kind: FIELD|TABLE_ROW, field, tableField, rowId}]。 */
    @TableField("source_refs")
    private String sourceRefs;
}
