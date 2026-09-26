package com.sw.ck.notify.api;

import java.util.Optional;

/**
 * 第三方渠道扩展 SPI；用稳定渠道标识分派，禁止按类名反射。
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：适配器身份与投递结果都必须显式取值。
 * </p>
 */
public interface NotifyChannelAdapter {

    /**
     * 本适配器承载的渠道标识。
     *
     * @return present = 渠道标识；当前契约恒 present（适配器身份是注册期契约，不得缺省）
     */
    Optional<NotifyChannel> channel();

    /**
     * 执行一次渠道投递。
     * <p>
     * 渠道失败（无凭据、外部拒绝、超时）必须以 {@code status=FAILED/TIMEOUT} 的结果表达，
     * 不抛出；真实基础设施异常由实现决定是否转为失败结果。
     * </p>
     *
     * @param request 投递请求
     * @return present = 投递结果（含渠道与状态）；当前契约恒 present
     */
    Optional<NotifySendResult> send(NotifySendRequest request);
}
