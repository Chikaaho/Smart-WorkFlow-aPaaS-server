package com.sw.ck.notify.dto;

import lombok.Data;

import java.util.List;

/** 用户订阅偏好保存请求（I6）。 */
@Data
public class NotifySubscriptionSaveReq {
    private List<Item> items;

    @Data
    public static class Item {
        private String eventType;
        private String channel;
        private Boolean enabled;
    }
}
