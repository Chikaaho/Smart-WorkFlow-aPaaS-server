package com.sw.ck.notify.dto;

import lombok.Data;

/** 用户订阅偏好视图（I6）。 */
@Data
public class NotifySubscriptionView {
    private String eventType;
    private String channel;
    private Boolean enabled;
}
