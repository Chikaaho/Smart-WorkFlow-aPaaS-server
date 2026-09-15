package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 流程交接批次（I4 §3.6）：离岗/调岗交接主记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_handover")
public class BpmHandover extends BaseEntity {

    @TableField("from_user_id")
    private Long fromUserId;

    @TableField("to_user_id")
    private Long toUserId;

    /** 流程范围（空=全部）：逗号分隔 processDefKey。 */
    @TableField("scope_def_keys")
    private String scopeDefKeys;

    /** 是否随迁有效期内的未来代理规则（显式勾选才为 true）。 */
    @TableField("include_proxy_rules")
    private Boolean includeProxyRules;

    /** COMPLETED / PARTIAL / FAILED。 */
    @TableField("status")
    private String status;

    @TableField("total_items")
    private Integer totalItems;

    @TableField("migrated_items")
    private Integer migratedItems;

    @TableField("failed_items")
    private Integer failedItems;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("executed_at")
    private LocalDateTime executedAt;
}
