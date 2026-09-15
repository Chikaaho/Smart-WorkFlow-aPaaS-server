package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户订阅偏好（I6）。
 * <p>普通用户只能在管理员允许范围内调整可选外部渠道或非强制事件；
 * 必须送达的待办与安全/状态通知（required_flag=1 的规则）不受订阅关闭。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_subscription")
public class NotifySubscription extends BaseEntity {

    @TableField("user_id")
    private Long userId;

    @TableField("event_type")
    private String eventType;

    @TableField("channel")
    private String channel;

    @TableField("enabled")
    private Boolean enabled;
}
