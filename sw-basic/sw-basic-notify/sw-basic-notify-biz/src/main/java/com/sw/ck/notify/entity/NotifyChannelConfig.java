package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户级渠道启停登记（I6）。
 * <p>凭据口径（0.2.4）：渠道级凭据以密文列 {@code config_cipher} 存储（与模型 Key 同构的
 * AES-256-GCM，base64(IV||ciphertext)），仅写入路径接收明文、接口与日志不回读明文；
 * 未配置密文时仍可回落 yml/env（{@code NotifyChannelProperties}）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_channel_config")
public class NotifyChannelConfig extends BaseEntity {

    @TableField("channel")
    private String channel;

    @TableField("enabled")
    private Boolean enabled;

    /** 展示用发件人标识（如邮件 From、短信签名；非秘密） */
    @TableField("sender_display")
    private String senderDisplay;

    /** 非秘密配置摘要（如 host/域名；不含任何凭据） */
    @TableField("config_summary")
    private String configSummary;

    /** 渠道级凭据密文（AES-256-GCM，base64(IV||ciphertext)；明文仅内存） */
    @TableField("config_cipher")
    private String configCipher;
}
