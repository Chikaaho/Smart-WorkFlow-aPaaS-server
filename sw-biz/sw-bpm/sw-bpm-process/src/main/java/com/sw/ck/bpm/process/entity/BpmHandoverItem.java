package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程交接明细（I4 §3.6）：逐项记录迁移前后责任人、结果与失败原因。
 * 历史办理人与意见不可改写——本表只新增不改写；失败项安全重试不重复迁移成功项。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_handover_item")
public class BpmHandoverItem extends BaseEntity {

    @TableField("handover_id")
    private Long handoverId;

    @TableField("task_id")
    private String taskId;

    @TableField("process_instance_id")
    private String processInstanceId;

    /** TASK（未完成任务迁移）/ PROXY_RULE（代理规则随迁）。 */
    @TableField("item_type")
    private String itemType;

    @TableField("before_assignee")
    private Long beforeAssignee;

    @TableField("after_assignee")
    private Long afterAssignee;

    /** MIGRATED / FAILED / SKIPPED_ALREADY_MIGRATED。 */
    @TableField("result")
    private String result;

    @TableField("fail_reason")
    private String failReason;

    @TableField("rule_id")
    private Long ruleId;
}
