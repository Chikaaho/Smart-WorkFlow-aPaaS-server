package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * SSO 最小持久审计（I5）。
 * <p>
 * 只保存必要标识、结果、时间、操作者与脱敏原因；不保存 code/token/secret 或
 * 原始 Provider 响应。external_digest 为外部主体标识的 SHA-256 摘要。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_sso_audit_record")
public class SsoAuditRecord extends BaseEntity {

    /** Provider 标识 */
    @TableField("provider")
    private String provider;

    /** 事件类型：CONFIG_CHANGE / AUTH_START / LOGIN_SUCCESS / LOGIN_FAILED / BIND / UNBIND / REPLAY_REJECTED / CONFLICT_REJECTED */
    @TableField("event_type")
    private String eventType;

    /** 结果：SUCCESS / DENIED / FAILED */
    @TableField("result")
    private String result;

    /** 操作者（已认证用户或 null=匿名发起） */
    @TableField("actor_id")
    private Long actorId;

    /** 关联本地用户 */
    @TableField("local_user_id")
    private Long localUserId;

    /** 外部主体标识摘要 */
    @TableField("external_digest")
    private String externalDigest;

    /** 脱敏原因/上下文（不含秘密） */
    @TableField("detail")
    private String detail;
}
