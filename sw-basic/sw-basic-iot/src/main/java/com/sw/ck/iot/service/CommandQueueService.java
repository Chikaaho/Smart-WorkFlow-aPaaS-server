package com.sw.ck.iot.service;

import com.sw.ck.iot.entity.IotDeviceCommand;

import java.util.List;

/**
 * 命令队列服务接口。
 * <p>
 * 管理 IoT 设备命令队列，支持并发控制、幂等性和过期处理。
 * </p>
 */
public interface CommandQueueService {

    /**
     * 入队命令（延迟生效语义）。
     *
     * @param command 命令实体
     * @return 入队后的命令
     */
    IotDeviceCommand enqueue(IotDeviceCommand command);

    /**
     * 标记命令为发送中。
     *
     * @param commandId 命令 ID
     * @return 更新后的命令
     */
    IotDeviceCommand markSending(Long commandId);

    /**
     * 标记命令为腾讯已发送。
     *
     * @param commandId  命令 ID
     * @param requestId  腾讯云 RequestId
     * @return 更新后的命令
     */
    IotDeviceCommand markSent(Long commandId, String requestId);

    /**
     * 标记命令为设备已送达（属性控制）。
     *
     * @param commandId 命令 ID
     * @return 更新后的命令
     */
    IotDeviceCommand markDelivered(Long commandId);

    /**
     * 标记命令为设备已回复（行为控制）。
     *
     * @param commandId   命令 ID
     * @param clientToken 腾讯云 ClientToken
     * @param outputParams 设备输出参数
     * @return 更新后的命令
     */
    IotDeviceCommand markAcked(Long commandId, String clientToken, String outputParams);

    /**
     * 标记命令为成功。
     *
     * @param commandId 命令 ID
     * @param result    执行结果
     * @return 更新后的命令
     */
    IotDeviceCommand markSuccess(Long commandId, String result);

    /**
     * 标记命令为失败（可重试）。
     *
     * @param commandId 命令 ID
     * @param error     错误原因
     * @return 更新后的命令
     */
    IotDeviceCommand markFailed(Long commandId, String error);

    /**
     * 标记命令为结果未知（不可自动重试）。
     *
     * @param commandId 命令 ID
     * @param error     错误原因
     * @return 更新后的命令
     */
    IotDeviceCommand markUnknown(Long commandId, String error);

    /**
     * 标记命令为已过期。
     *
     * @param commandId 命令 ID
     * @return 更新后的命令
     */
    IotDeviceCommand markExpired(Long commandId);

    /**
     * 查询设备待发送命令（按创建时间排序）。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     * @return 待发送命令列表
     */
    List<IotDeviceCommand> getPendingCommands(String productId, String deviceName);

    /**
     * 查询过期命令。
     *
     * @return 过期命令列表
     */
    List<IotDeviceCommand> getExpiredCommands();

    /**
     * 查询滞留命令（超过指定时间未处理）。
     *
     * @param stuckMinutes 滞留时间阈值（分钟）
     * @return 滞留命令列表
     */
    List<IotDeviceCommand> getStuckCommands(int stuckMinutes);

    /**
     * 原子领取滞留 QUEUED 命令用于发送（Phase 4 补偿调度）。
     *
     * <p>条件更新 {@code QUEUED → SENDING} 且仅当命令在 {@code staleBefore} 之前更新过：
     * 多实例竞争时只有一个领取者能把行数改为 1，旧持有者不会覆盖新结果。</p>
     *
     * @return true = 本次领取成功（调用方负责发送并回写结果）
     */
    boolean claimQueuedForSend(Long commandId, java.time.LocalDateTime staleBefore);

    /**
     * 原子领取可重试的 FAILED 命令（重试预算内、未过期）。
     *
     * @param maxRetryCount 最大重试次数（含）
     * @return true = 本次领取成功（retry_count 已 +1，调用方负责发送并回写结果）
     */
    boolean claimFailedForRetry(Long commandId, int maxRetryCount);

    /**
     * 查询可重试的 FAILED 命令（跨租户；重试预算内且未过期，按创建时间升序有界返回）。
     */
    List<IotDeviceCommand> findRetryableFailed(int maxRetryCount, int limit);

    /**
     * 查询滞留 QUEUED 命令（跨租户；更新时间早于阈值，有界返回）。
     */
    List<IotDeviceCommand> findStuckQueued(int stuckMinutes, int limit);

    /**
     * 查询滞留 SENDING 命令（跨租户；更新时间早于阈值 = 发送租约已过期，有界返回）。
     *
     * <p>发送者认领（QUEUED/FAILED → SENDING）后崩溃会把命令永久留在 SENDING：
     * 它既不在滞留 QUEUED 扫描里，也不在可重试 FAILED 扫描里。此查询让补偿调度能发现它。</p>
     */
    List<IotDeviceCommand> findStaleSending(int staleMinutes, int limit);

    /**
     * 回收滞留 SENDING 命令为可重试失败（条件更新：仍处于 SENDING 且更新时间早于阈值）。
     *
     * @return true = 本次回收成功（调用方随后走统一重试路径）
     */
    boolean reclaimStaleSending(Long commandId, java.time.LocalDateTime staleBefore);

    /**
     * 检查幂等键是否已存在。
     *
     * @param idempotentKey 幂等键
     * @return 是否存在
     */
    boolean isIdempotentKeyExists(String idempotentKey);
}
