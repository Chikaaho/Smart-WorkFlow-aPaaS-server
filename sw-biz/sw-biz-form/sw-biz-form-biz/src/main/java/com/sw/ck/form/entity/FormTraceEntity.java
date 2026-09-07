package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 表单提交溯源实体 — 对应 {@code sw_form_trace} 表。
 * <p>
 * 记录每次表单提交的来源信息（IP、设备指纹、User-Agent），
 * 用于安全审计和风控。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_trace")
public class FormTraceEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id */
    private String formId;

    /** 动态宽表记录 UUID */
    private String recordId;

    /** 提交人用户 ID（BIGINT，指向 sys_user） */
    private Long submitUserId;

    /** 提交 IP（AES 加密存储） */
    private String submitIp;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 设备指纹（哈希值） */
    private String deviceFingerprint;

    /** User-Agent */
    private String userAgent;

    /** 提交幂等键（草稿/命令重试防重复落表单数据；可空）。 */
    private String submitIdempotencyKey;
}
