package com.sw.ck.notify.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 发送记录列表摘要（I6 最小暴露）：不返回完整正文、联系方式或 Provider 原始响应。 */
@Data
public class NotifyRecordSummaryDTO {
    private Long id;
    private String title;
    private String bizType;
    private String bizId;
    /** 脱敏接收人（同租户用户 ID + 掩码标识） */
    private Long recipientId;
    private String recipientMask;
    private String channel;
    private String deliveryStatus;
    private Integer attemptCount;
    private Long templateId;
    private Integer templateVersion;
    private LocalDateTime createTime;
}
