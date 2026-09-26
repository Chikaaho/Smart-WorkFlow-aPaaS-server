package com.sw.ck.notify.api;

import java.util.Optional;

/**
 * 通知门面接口。
 * <p>
 * 定义于 {@code -api} 模块，实现于 {@code -biz} 模块。
 * 调用方经 Spring 容器注入本接口，不依赖实现细节。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：投递失败以结果状态表达，
 * 不以上空表达失败，也不以异常替代可预期的失败结果。
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
     * @return present = 落库通知的结果镜像（渠道与投递状态，站内信入口恒 SUCCESS）；
     *         当前契约恒 present，持久化失败抛异常
     */
    Optional<NotifySendResult> send(SendNotifyCommand cmd);

    /**
     * 统一渠道入口；IN_APP 必须先完成站内信持久化才能返回成功。
     *
     * @return present = 投递结果（SUCCESS/FAILED/TIMEOUT 等）；当前契约恒 present，
     *         渠道失败以结果状态表达，真实基础设施异常按既有映射转为失败结果
     */
    Optional<NotifySendResult> send(NotifySendRequest request);

    /**
     * 仅执行一次渠道投递，不落新消息行（v0.0.2 失败重发专用）。
     * <p>
     * 调用方（发送记录服务）负责受理控制（并发单受理）、尝试流水与最新结果回写。
     * 与 {@link #send(NotifySendRequest)} 的渠道语义一致：IN_APP 恒成功；
     * 渠道无适配器/适配器异常按 FAILED 返回，不抛出。
     * </p>
     *
     * @return present = 投递结果；当前契约恒 present
     */
    Optional<NotifySendResult> attemptDelivery(NotifySendRequest request);

    /**
     * 记录通知投递意图（<b>只持久化，不执行任何渠道 I/O</b>）。
     *
     * <p>供 must-deliver 业务动作在<b>自身事务内</b>调用：意图行与业务数据同事务提交或回滚，
     * 因此进程在“业务已提交、异步监听尚未执行”之间退出也不会丢失通知。提交后由
     * {@link #send(NotifySendRequest)}（提交后加速）或通知恢复调度
     * {@code NotifyDeliveryRecoveryServiceImpl} 完成投递。</p>
     *
     * <ul>
     *   <li>IN_APP：站内信行本身即投递结果，落库状态 SUCCESS；</li>
     *   <li>其他渠道：落库状态 {@code PENDING} + {@code failure_class=RETRYABLE} +
     *       {@code next_retry_time=now}，可被恢复调度立即领取；</li>
     *   <li>幂等：命中同业务稳定身份（租户+事件+业务对象+发生次序+接收人+渠道）或显式幂等键时
     *       返回既有行镜像，不重复落行。</li>
     * </ul>
     *
     * @return present = 意图行镜像；当前契约恒 present，持久化失败抛异常
     */
    Optional<NotifySendResult> recordIntent(NotifySendRequest request);
}
