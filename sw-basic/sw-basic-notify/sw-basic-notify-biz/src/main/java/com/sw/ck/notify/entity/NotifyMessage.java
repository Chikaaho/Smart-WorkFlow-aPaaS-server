package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 站内信通知实体。
 * <p>
 * 一条记录 = 一条通知。{@code tenant_id / 审计列 / deleted / version}
 * 由 MyBatis-Plus 拦截器自动注入。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_message")
public class NotifyMessage extends BaseEntity {

    /** 接收人用户 ID（指向 sys_user.id） */
    @TableField("recipient_id")
    private Long recipientId;

    /** 通知标题 */
    @TableField("title")
    private String title;

    /** 通知内容 */
    @TableField("content")
    private String content;

    /** 业务类型（NotifyBizType 枚举 name） */
    @TableField("biz_type")
    private String bizType;

    /** 业务 ID（Flowable taskId / piId 等，VARCHAR） */
    @TableField("biz_id")
    private String bizId;

    /** 是否已读，默认 false */
    @TableField("is_read")
    private Boolean read;

    /** 渠道：IN_APP 或预留第三方标识。 */
    @TableField("channel")
    private String channel;

    /** 发送状态：SUCCESS / FAILED / TIMEOUT / PENDING。 */
    @TableField("delivery_status")
    private String deliveryStatus;

    @TableField("external_message_id")
    private String externalMessageId;

    @TableField("failure_reason")
    private String failureReason;

    @TableField("idempotency_key")
    private String idempotencyKey;
}
