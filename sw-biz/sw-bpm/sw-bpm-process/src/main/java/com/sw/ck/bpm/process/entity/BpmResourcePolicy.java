package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P62 资源策略（{@code sw_bpm_resource_policy}，版本化）。
 * <p>
 * 额度与并发预算的权威配置：新受理在准入时冻结 {@code policy_version} 与服务等级，
 * 减配/换版只影响新受理，不重解释在途对象。占用计数跨策略版本共享
 * （{@code sw_bpm_resource_usage} 不按版本分账）。新策略默认关闭
 * （{@code enabled=false}），启用须通过启用检查（保留份额自洽、消费者可用、
 * 预算与连接池相容），否则明确拒绝。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_resource_policy")
public class BpmResourcePolicy extends BaseEntity {

    /** 策略版本（受理时冻结到命令行）。 */
    @TableField("policy_version")
    private Integer policyVersion;

    /** DRAFT / ACTIVE / RETRIED（同租户至多一个 ACTIVE）。 */
    @TableField("status")
    private String status;

    /** 资源会计与准入裁决总开关（默认 FALSE=零行为变化）。 */
    @TableField("enabled")
    private Boolean enabled;

    /** 停新受理开关（TRUE 时新受理明确拒绝；已有工作按原合同结算）。 */
    @TableField("stop_acceptance")
    private Boolean stopAcceptance;

    /** 持久未完成工作量全局上限。 */
    @TableField("global_max_outstanding")
    private Integer globalMaxOutstanding;

    /** 每租户持久未完成工作量上限。 */
    @TableField("tenant_max_outstanding")
    private Integer tenantMaxOutstanding;

    /** 生产保留容量（不可被新增低等级工作占满）。 */
    @TableField("prod_reserved")
    private Integer prodReserved;

    /** 普通 OA 保留容量（不可被批量占满）。 */
    @TableField("oa_reserved")
    private Integer oaReserved;

    /** 共享容量（生产/OA/批量竞争段）。 */
    @TableField("shared_capacity")
    private Integer sharedCapacity;

    /** 每租户工作单位速率上限（单位/s）。 */
    @TableField("tenant_rate_per_sec")
    private Integer tenantRatePerSec;

    /** 每租户突发额（工作单位）。 */
    @TableField("tenant_burst")
    private Integer tenantBurst;

    /** 实时受控动作全局并发上限。 */
    @TableField("realtime_global_concurrency")
    private Integer realtimeGlobalConcurrency;

    /** 实时受控动作单租户并发上限。 */
    @TableField("realtime_tenant_concurrency")
    private Integer realtimeTenantConcurrency;

    /** 批量命令单次执行切片项数（让出调度线程，保留其他工作推进机会）。 */
    @TableField("batch_slice_items")
    private Integer batchSliceItems;

    /** 每轮调度批量命令领取上限。 */
    @TableField("batch_poll_claim_limit")
    private Integer batchPollClaimLimit;

    /** 备注（含启用人/启用依据等运维说明）。 */
    @TableField("remark")
    private String remark;
}
