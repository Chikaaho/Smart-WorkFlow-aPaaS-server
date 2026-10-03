package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P62 资源拒绝审计（{@code sw_bpm_resource_reject_log}）。
 * <p>
 * 准入拒绝不产生可执行命令、业务写入或成功幂等占位，但保留独立可查证据：
 * 拒绝范围（总量/租户/容量段/速率/停受理/启用检查）、适用额度与原因，经运维
 * 明细端点按租户/时间段查询。与成功结果不同，拒绝写入走独立短事务
 * （REQUIRES_NEW），不承载也不影响业务结果。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_resource_reject_log")
public class BpmResourceRejectLog extends BaseEntity {

    /** 被拒绝受理的租户。 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 拒绝时生效的策略版本（可能为空=未启用策略场景）。 */
    @TableField("policy_version")
    private Integer policyVersion;

    /** 请求的资源类别（PROD/OA/BULK）。 */
    @TableField("resource_class")
    private String resourceClass;

    /** 拒绝范围（QUOTA_TOTAL/QUOTA_TENANT/QUOTA_SEGMENT/RATE/STOPPED/ENABLEMENT）。 */
    @TableField("reject_scope")
    private String rejectScope;

    /** 请求占用的单位数。 */
    @TableField("requested_units")
    private Integer requestedUnits;

    /** 稳定错误 key（与响应体 errorKey 一致）。 */
    @TableField("reason_code")
    private String reasonCode;

    /** 原因明细（含适用额度与可重试提示；不泄露秘密）。 */
    @TableField("detail")
    private String detail;

    /** 关联业务幂等键（commandKey；便于与受理/回查关联）。 */
    @TableField("command_key")
    private String commandKey;
}
