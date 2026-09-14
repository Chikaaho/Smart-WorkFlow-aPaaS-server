package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户级渠道启停登记（I6）。
 * <p>秘密不落库：本表只存启停与非秘密展示配置；生产凭据仅入安全配置（yml/env），
 * 租户级启用必须先通过配置完整性验证（{@code NotifyChannelProperties}）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_channel_config")
public class NotifyChannelConfig extends BaseEntity {

    @TableField("channel")
    private String channel;

    @TableField("enabled")
    private Integer enabled;

    /** 展示用发件人标识（如邮件 From、短信签名；非秘密） */
    @TableField("sender_display")
    private String senderDisplay;

    /** 非秘密配置摘要（如 host/域名；不含任何凭据） */
    @TableField("config_summary")
    private String configSummary;
}
