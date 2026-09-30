package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 后台批量命令批次（{@code sw_bpm_command_batch}，P62 后台批量形态）。
 * <p>
 * 受理即冻结批次键、绑定动作与发布版本；批次重放按 (tenant_id, batch_key)
 * 返回原批次，不重建、不重复执行。项级身份与结果见 {@link BpmCommandBatchItem}。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_command_batch")
public class BpmCommandBatch extends BaseEntity {

    /** 批次键（同租户唯一）。 */
    private String batchKey;

    /** 绑定的受控动作（同一批次同一动作）。 */
    private String actionId;

    /** 受理时冻结的动作发布版本。 */
    private Integer actionVersion;

    /** 批次状态：PENDING/PROCESSING/COMPLETED/PARTIALLY_FAILED。 */
    private String status;

    /** 受理项总数（1—500）。 */
    private Integer totalCount;

    /** 已成功项数（按项表实际状态聚合）。 */
    private Integer succeededCount;

    /** 已失败/被拒项数。 */
    private Integer failedCount;

    /** 受理命令行（sw_bpm_command）标识。 */
    private Long commandId;

    /** 发起人。 */
    private Long initiatorId;
}
