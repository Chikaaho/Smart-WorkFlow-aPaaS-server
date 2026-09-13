package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 外部身份 → 本地账号绑定（I5）。
 * <p>
 * 绑定键 = (provider, tenant_id, external_id)；同一外部身份在同一租户内唯一，
 * 同一本地账号在同一租户内对同一 Provider 唯一；跨租户/冲突绑定由唯一索引与服务
 * 校验双重拒绝。external_digest 为外部稳定标识的 SHA-256 摘要（审计用，不回传原文）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_sso_user_binding")
public class SsoUserBinding extends BaseEntity {

    /** Provider 标识 */
    @TableField("provider")
    private String provider;

    /** 外部稳定主体标识（Provider 官方 userid/open_id 等） */
    @TableField("external_id")
    private String externalId;

    /** 外部标识 SHA-256 摘要（审计查询用） */
    @TableField("external_digest")
    private String externalDigest;

    /** 本地用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 绑定状态：ACTIVE / UNBOUND */
    @TableField("bind_status")
    private String bindStatus;
}
