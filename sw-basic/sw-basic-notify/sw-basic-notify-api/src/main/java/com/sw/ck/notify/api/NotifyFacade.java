package com.sw.ck.notify.api;

/**
 * 通知门面接口。
 * <p>
 * 定义于 {@code -api} 模块，实现于 {@code -biz} 模块。
 * 调用方经 Spring 容器注入本接口，不依赖实现细节。
 * </p>
 */
public interface NotifyFacade {

    /**
     * 发送通知。
     * <p>
     * 将业务通知持久化为 {@code sw_notify_message} 记录。
     * {@code tenant_id / create_time / create_by / deleted / version}
     * 由 MyBatis-Plus 拦截器自动注入。
     * </p>
     *
     * @param cmd 通知命令，不可为空
     */
    void send(SendNotifyCommand cmd);

    /** 统一渠道入口；IN_APP 必须先完成站内信持久化才能返回成功。 */
    NotifySendResult send(NotifySendRequest request);

    /**
     * 仅执行一次渠道投递，不落新消息行（v0.0.2 失败重发专用）。
     * <p>
     * 调用方（发送记录服务）负责受理控制（并发单受理）、尝试流水与最新结果回写。
     * 与 {@link #send(NotifySendRequest)} 的渠道语义一致：IN_APP 恒成功；
     * 渠道无适配器/适配器异常按 FAILED 返回，不抛出。
     * </p>
     */
    NotifySendResult attemptDelivery(NotifySendRequest request);
}
