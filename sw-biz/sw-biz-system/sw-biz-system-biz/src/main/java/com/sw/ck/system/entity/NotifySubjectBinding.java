package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知 Provider 主体映射（I6 G5a-I / V91）。
 * <p>独立于 I5 SSO 绑定（sys_sso_user_binding 摘要不可反解，不复用冒充通知主体）。
 * {@code subject_cipher} 存 AES-GCM 密文；{@code subject_digest} 为明文的
 * SHA-256 摘要（审计与幂等判定，不落明文）。租户条件由 TenantLineHandler 注入。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_subject_binding")
public class NotifySubjectBinding extends BaseEntity {

    /** 本地用户 ID */
    @TableField("user_id")
    private Long userId;

    /** Provider 标识（FEISHU / DINGTALK / WECHAT_WORK） */
    @TableField("provider")
    private String provider;

    /** 主体标识密文（AES-GCM，不落明文） */
    @TableField("subject_cipher")
    private String subjectCipher;

    /** 主体标识 SHA-256 摘要（审计/展示用） */
    @TableField("subject_digest")
    private String subjectDigest;

    /** 绑定状态 ACTIVE / DISABLED */
    @TableField("bind_status")
    private String bindStatus;
}
