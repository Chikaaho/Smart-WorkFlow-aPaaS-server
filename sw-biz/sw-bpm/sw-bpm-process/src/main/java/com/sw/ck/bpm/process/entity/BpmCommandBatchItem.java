package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量命令逐项持久结果（{@code sw_bpm_command_batch_item}）。
 * <p>
 * 逐项独立事务落结果；批次内稳定项键 (batch_id, item_key) 唯一，
 * 调用幂等键为 {@code BATCH:{batchKey}:{itemKey}}——重试/批次重放经动作内核
 * 幂等返回原结果，不产生第二次业务效果；同键异载荷被动作内核明确拒绝。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_command_batch_item")
public class BpmCommandBatchItem extends BaseEntity {

    /** 所属批次。 */
    private Long batchId;

    /** 批次内稳定项键。 */
    private String itemKey;

    /** 目标业务记录标识。 */
    private String recordId;

    /** 数量（字符串承载，精度由动作配置裁决）。 */
    private String quantity;

    /** 项状态：PENDING/SUCCEEDED/REJECTED。 */
    private String status;

    /** 动作调用记录标识（sw_form_txn_invocation）。 */
    private String invocationId;

    /** 业务拒绝/冲突错误码。 */
    private Integer errorCode;

    /** 业务拒绝/冲突信息。 */
    private String errorMsg;

    /** 已尝试次数（调度重试累加）。 */
    private Integer attemptCount;
}
