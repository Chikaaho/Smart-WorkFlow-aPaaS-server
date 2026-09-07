package com.sw.ck.notify.api;

import lombok.Builder;
import lombok.Value;

/** 统一渠道消息模型。 */
@Value
@Builder
public class NotifySendRequest {
    Long recipientId;
    String title;
    String content;
    NotifyBizType bizType;
    String bizId;
    Long tenantId;
    NotifyChannel channel;
    String idempotencyKey;
}
