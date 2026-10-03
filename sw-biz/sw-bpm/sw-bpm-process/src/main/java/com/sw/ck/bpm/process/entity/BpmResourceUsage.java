package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P62 资源占用计数（{@code sw_bpm_resource_usage}）。
 * <p>
 * 准入加速器：以原子条件更新（{@code outstanding + units <= 容量}）保证并发受理
 * 不突破额度。权威事实是命令/批次项/引擎队列持久行（可重建勾稽），本计数与事实
 * 漂移时由资源对账修复——重启后可从持久事实恢复占用，不双计、不丢账。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_resource_usage")
public class BpmResourceUsage extends BaseEntity {

    /** GLOBAL / TENANT。 */
    @TableField("scope")
    private String scope;

    /** GLOBAL=0；TENANT=租户 id。 */
    @TableField("scope_key")
    private Long scopeKey;

    /** TOTAL / PROD_RESERVED / OA_RESERVED / SHARED。 */
    @TableField("segment")
    private String segment;

    /** 当前占用工作单位数。 */
    @TableField("outstanding")
    private Long outstanding;
}
