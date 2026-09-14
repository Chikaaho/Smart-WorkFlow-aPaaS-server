package com.sw.ck.notify.service;

import com.sw.ck.notify.dto.NotifySubscriptionSaveReq;
import com.sw.ck.notify.dto.NotifySubscriptionView;

import java.util.List;

/** 用户订阅偏好服务（I6）。 */
public interface NotifySubscriptionService {

    List<NotifySubscriptionView> preferences(Long userId);

    void save(Long userId, NotifySubscriptionSaveReq req);

    /** 是否允许该用户对该事件/渠道关闭（required_flag=1 的规则不允许关闭）。 */
    boolean canOptOut(String eventType, String channel);
}
