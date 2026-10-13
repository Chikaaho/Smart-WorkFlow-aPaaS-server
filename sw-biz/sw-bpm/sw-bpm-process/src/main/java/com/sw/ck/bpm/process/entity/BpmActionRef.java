package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P64 动作意图与目标实例关联（ADR-P64-001 §4）：一次派发项一行。
 * <p>
 * status: INTENT_SUBMITTED（同事务受理事实）/ STARTED（消费事务回填目标）/ FAILED；
 * {@code command_key} 与 sw_bpm_command 幂等键一致；target_* 构成 业务单据→触发→动作→关联实例 回查链。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_action_ref")
public class BpmActionRef extends BaseEntity {

    @TableField("exec_id")
    private Long execId;

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("trigger_id")
    private String triggerId;

    @TableField("action_id")
    private String actionId;

    /** START_SINGLE / START_EACH / START_GROUPED。 */
    @TableField("action_type")
    private String actionType;

    /** 集合项稳定身份：USER/DEPT 为对象 ID；ROWS 为行 ID；分组为分组键；单发为 SINGLE。 */
    @TableField("item_key")
    private String itemKey;

    /** 冻结项摘要（对象/行来源追踪信息 JSON）。 */
    @TableField("item_summary")
    private String itemSummary;

    /** 冻结映射载荷 JSON（派发时定值，消费侧不重算）。 */
    @TableField("payload_json")
    private String payloadJson;

    @TableField("command_key")
    private String commandKey;

    @TableField("command_id")
    private Long commandId;

    @TableField("target_def_key")
    private String targetDefKey;

    @TableField("target_form_key")
    private String targetFormKey;

    @TableField("target_record_id")
    private String targetRecordId;

    @TableField("target_instance_id")
    private String targetInstanceId;

    /** INTENT_SUBMITTED / STARTED / FAILED。 */
    @TableField("status")
    private String status;

    @TableField("error_text")
    private String errorText;
}
