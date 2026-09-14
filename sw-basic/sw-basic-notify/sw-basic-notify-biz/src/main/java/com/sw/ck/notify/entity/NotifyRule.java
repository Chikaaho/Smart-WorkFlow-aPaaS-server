package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知规则（I6）：事件开关、接收人规则、渠道顺序与失败策略。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_rule")
public class NotifyRule extends BaseEntity {

    /** 规则编码（租户内唯一） */
    @TableField("rule_code")
    private String ruleCode;

    @TableField("name")
    private String name;

    /** 事件类型（BpmNotifyTrigger 常量或 SYSTEM） */
    @TableField("event_type")
    private String eventType;

    /** 渠道顺序或组合，如 IN_APP / IN_APP,EMAIL */
    @TableField("channel_priority")
    private String channelPriority;

    /** 接收人规则（受控形式：<user>/<role>:<code>/<dept>:<id>/INITIATOR/ASSIGNEE/…） */
    @TableField("recipient_rule")
    private String recipientRule;

    /** 1=必须送达（至少保留站内信，普通用户不可关闭）；0=可由订阅关闭 */
    @TableField("required_flag")
    private Integer requiredFlag;

    /** 失败策略：RETRY / MANUAL */
    @TableField("failure_policy")
    private String failurePolicy;

    @TableField("enabled")
    private Integer enabled;

    @TableField("remark")
    private String remark;
}
