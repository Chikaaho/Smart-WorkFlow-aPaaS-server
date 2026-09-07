package com.sw.ck.notify.api;

/**
 * 通知业务类型枚举。
 * <p>
 * 用于标识通知的业务来源，供消费方按类型分流处理（如站内信展示图标、消息推送路由等）。
 * </p>
 */
public enum NotifyBizType {

    /**
     * 流程待办：新任务到达、催办等。
     */
    WF_TODO,

    /**
     * 流程已审批：提交的流程被通过/驳回后通知发起人。
     */
    WF_APPROVED,

    WF_REJECTED,

    WF_RETURNED,

    /**
     * 系统消息：模板发送（P36 / M05-F02-01）等平台侧主动通知。
     */
    SYSTEM
}
