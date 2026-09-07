package com.sw.ck.notify.api;

/** 统一通知渠道标识。第三方渠道只预留契约，不伪造生产发送成功。 */
public enum NotifyChannel {
    IN_APP,
    SMS,
    FEISHU,
    DINGTALK,
    WECHAT_WORK,
    WECHAT_OFFICIAL,
    WECHAT_MINI_PROGRAM
}
