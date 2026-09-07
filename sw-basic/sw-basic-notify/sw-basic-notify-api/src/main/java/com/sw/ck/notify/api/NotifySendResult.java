package com.sw.ck.notify.api;

import lombok.Builder;
import lombok.Value;

/** 渠道发送结果，供业务审计成功/失败/超时和外部消息标识。 */
@Value
@Builder
public class NotifySendResult {
    NotifyChannel channel;
    String status;
    String externalMessageId;
    String failureReason;
}
