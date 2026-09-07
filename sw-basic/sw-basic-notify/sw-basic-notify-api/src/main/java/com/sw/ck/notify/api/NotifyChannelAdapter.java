package com.sw.ck.notify.api;

/** 第三方渠道扩展 SPI；用稳定渠道标识分派，禁止按类名反射。 */
public interface NotifyChannelAdapter {

    NotifyChannel channel();

    NotifySendResult send(NotifySendRequest request);
}
