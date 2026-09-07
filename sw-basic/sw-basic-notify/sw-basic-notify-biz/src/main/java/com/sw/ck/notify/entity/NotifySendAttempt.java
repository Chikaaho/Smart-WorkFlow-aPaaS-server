package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知发送尝试流水（v0.0.2 OA）。
 * <p>
 * 每次渠道投递（首次发送与失败重发）各写一行，保留原始失败、各次尝试与最新结果；
 * 消息行 {@code delivery_status} 恒为最新结果。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_send_attempt")
public class NotifySendAttempt extends BaseEntity {

    /** 关联消息 ID（sw_notify_message.id） */
    @TableField("message_id")
    private Long messageId;

    /** 尝试序号（首次发送=1，重发递增） */
    @TableField("attempt_no")
    private Integer attemptNo;

    /** 渠道 */
    @TableField("channel")
    private String channel;

    /** 本次尝试结果：SUCCESS / FAILED */
    @TableField("status")
    private String status;

    /** 失败原因 */
    @TableField("failure_reason")
    private String failureReason;

    @TableField("external_message_id")
    private String externalMessageId;
}
