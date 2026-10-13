package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P64 阶段Ⅱ（A05）子流程派发批次：一次 CHILD 动作派发一批（主方向 §3.3 派发冻结）。
 * <p>
 * status：WAITING（等待结算）/ SETTLED（已结算推进一次）/ BLOCKED（失败/冲突阻断，可诊断）/
 * CANCELLED（父取消/退回终止写回推进权）。NONE 策略批次派发即 SETTLED。
 * batch_key 唯一（{@code CHILD:{execKey}:{actionId}}）；{@code config_json} 为派发时冻结的
 * 动作配置（等待策略/回写映射随批次快照，不随后续发布变化）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_child_batch")
public class BpmChildBatch extends BaseEntity {

    /** 批次稳定身份（{@code CHILD:{execKey}:{actionId}}，租户内唯一）。 */
    @TableField("batch_key")
    private String batchKey;

    @TableField("parent_instance_id")
    private String parentInstanceId;

    @TableField("parent_def_key")
    private String parentDefKey;

    /** 业务根链实例（根=自身；子=继承父批次根；累计实例护栏计数锚）。 */
    @TableField("root_instance_id")
    private String rootInstanceId;

    /** 父链深度（根=0，子=父+1；默认上限 3、硬上限 8）。 */
    @TableField("parent_depth")
    private Integer parentDepth;

    @TableField("trigger_id")
    private String triggerId;

    @TableField("action_id")
    private String actionId;

    @TableField("round_no")
    private Long roundNo;

    /** ALL / ANY / COUNT / NONE。 */
    @TableField("wait_policy")
    private String waitPolicy;

    /** COUNT 策略的 K（其余策略空）。 */
    @TableField("wait_count")
    private Integer waitCount;

    /** 派发时冻结的预期子流程数（转办/重试不增加）。 */
    @TableField("expected_count")
    private Integer expectedCount;

    /** 派发时冻结的父主表单记录身份（稳定行契约）。 */
    @TableField("source_record_id")
    private String sourceRecordId;

    /** 派发时冻结的父主表单记录版本。 */
    @TableField("source_record_version")
    private Long sourceRecordVersion;

    /** WAITING / SETTLED / BLOCKED / CANCELLED。 */
    @TableField("status")
    private String status;

    /** 已有效完成（WRITTEN）计数。 */
    @TableField("settled_count")
    private Integer settledCount;

    /** 阻断原因（BLOCKED）或取消原因（CANCELLED）。 */
    @TableField("block_reason")
    private String blockReason;

    @TableField("settled_at")
    private java.time.LocalDateTime settledAt;

    /** 派发时冻结的动作配置 JSON（ActionConfig）。 */
    @TableField("config_json")
    private String configJson;

    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
