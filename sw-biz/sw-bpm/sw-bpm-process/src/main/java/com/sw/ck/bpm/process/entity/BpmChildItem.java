package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P64 阶段Ⅱ（A05/A06）子流程批次项：一个预期子流程一行。
 * <p>
 * status 流转：DISPATCHED（已派发待子完成）→ WRITTEN（有效完成且所需回写已提交，计成功数）/
 * CONFLICT（回写版本冲突挂起，有权恢复不重复已生效结果）/ FAILED（子终态失败或必需回写缺失/
 * 目标不可用）/ LATE（批次已结算后的迟到完成，独立留痕不覆盖已用快照）/
 * REFUSED（父取消/退回后失去写回推进权，留痕）。
 * {@code source_row_id/source_row_version} 为派发时冻结的稳定来源行身份与版本；
 * {@code writeback_json/writeback_source} 记录回写值与来源（子实例/节点/轮次/任务可追溯）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_child_item")
public class BpmChildItem extends BaseEntity {

    @TableField("batch_id")
    private Long batchId;

    /** 冻结项稳定身份（分组键/行 ID/SINGLE）。 */
    @TableField("item_key")
    private String itemKey;

    /** 关联动作意图（sw_bpm_action_ref.id，发起链回查）。 */
    @TableField("action_ref_id")
    private Long actionRefId;

    /** 稳定来源行 ID（行级派发时冻结；分组/单发为空）。 */
    @TableField("source_row_id")
    private String sourceRowId;

    /** 派发时来源行版本（回写冲突检测基准）。 */
    @TableField("source_row_version")
    private Long sourceRowVersion;

    /**
     * 本项冻结的授权来源行集合 [{rowId, version}]（JSON；分组项含多行）。
     * 行级回写只接受集合内行——子流程只取得本实例授权来源行。
     */
    @TableField("source_rows_json")
    private String sourceRowsJson;

    /** 冻结项摘要（来源追踪 JSON）。 */
    @TableField("source_summary")
    private String sourceSummary;

    @TableField("target_def_key")
    private String targetDefKey;

    @TableField("target_form_key")
    private String targetFormKey;

    /** 目标记录 ID（= 子实例 businessKey，完成回查键）。 */
    @TableField("target_record_id")
    private String targetRecordId;

    /** 子实例 ID（展示层按持久事实解析）。 */
    @TableField("target_instance_id")
    private String targetInstanceId;

    /** DISPATCHED / WRITTEN / CONFLICT / FAILED / LATE / REFUSED。 */
    @TableField("status")
    private String status;

    /** 回写值 JSON（版本化结果）。 */
    @TableField("writeback_json")
    private String writebackJson;

    @TableField("writeback_time")
    private java.time.LocalDateTime writebackTime;

    /** 回写来源（子实例/节点/轮次/任务，可追溯）。 */
    @TableField("writeback_source")
    private String writebackSource;

    @TableField("error_text")
    private String errorText;

    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_WRITTEN = "WRITTEN";
    public static final String STATUS_CONFLICT = "CONFLICT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_LATE = "LATE";
    public static final String STATUS_REFUSED = "REFUSED";
}
