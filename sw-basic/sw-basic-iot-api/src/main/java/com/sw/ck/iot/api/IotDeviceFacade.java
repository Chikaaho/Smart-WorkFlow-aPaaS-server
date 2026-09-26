package com.sw.ck.iot.api;

import java.util.Optional;

/**
 * IoT 设备门面（跨模块契约，参照 NotifyFacade 模式）。
 * <p>
 * 供 sw-biz 层（如 BPM 审批通过后驱动设备）调用。消费者只依赖本接口所在的
 * {@code sw-basic-iot-api} 契约模块，不依赖 IoT 实现模块，也不再传递获得
 * MQTT/Paho、GraalJS、Tencent IoT SDK 等实现依赖。
 * </p>
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，二者均不可为空。
 * </p>
 * <h3>present / empty / exception 两层语义</h3>
 * <ul>
 *   <li><b>present</b>：命令已持久化入队，值为命令记录 ID。入队成功只表示命令进入本地
 *       待发送队列，不表示腾讯云或设备已收到；设备离线时等待上线补发。</li>
 *   <li><b>empty</b>：调用合法执行但目标/适用条件不存在——设备、连接或下行主题不合格。
 *       这不是异常，也不是"已在途"；调用方必须按其失败策略显式处理。</li>
 *   <li><b>exception</b>：参数非法、设备不存在、基础设施失败等真实错误继续抛出，不得吞为 empty。</li>
 * </ul>
 */
public interface IotDeviceFacade {

    /**
     * 下发设备控制命令（延迟生效语义）。
     *
     * @param productId     腾讯云产品 ID
     * @param deviceName    腾讯云设备名称
     * @param commandKey    命令标识
     * @param commandType   命令类型（PROPERTY / ACTION）
     * @param payload       命令负载（JSON 字符串）
     * @param approvalBizId 关联审批业务 ID（流程实例 ID，可 null）
     * @return 命令记录 ID；目标设备不存在时抛出异常而不返回 empty
     */
    Optional<Long> dispatchCommand(String productId, String deviceName,
                                   String commandKey, String commandType,
                                   String payload, String approvalBizId);

    /**
     * 下发设备控制命令（调用方指定<b>稳定幂等键</b>；Phase 4 可靠业务事件用）。
     *
     * <p>与 {@link #dispatchCommand} 语义一致（仅入队，不做外部 I/O），但幂等键来自业务稳定身份
     * 而非随机值：同一业务动作重复投递（提交后加速 + 恢复调度 + 人工重试）只产生一条命令记录，
     * 命中既有键时返回既有命令 ID。</p>
     *
     * @param idempotentKey 调用方给出的稳定幂等键（必填，形如 APPROVAL:{流程实例}:{设备}:{命令}）
     * @return 命令记录 ID（新建或既有）；目标设备不存在时抛出异常而不返回 empty
     */
    Optional<Long> dispatchCommandIdempotent(String productId, String deviceName,
                                            String commandKey, String commandType,
                                            String payload, String approvalBizId,
                                            String idempotentKey);

    /**
     * 按 deviceKey 下发命令（P21 A6：MQTT 设备统一命令路径）。
     * <p>
     * 运行时重校验：设备必须 PUBLISHED + 流程接入开启 + 连接启用；命令写 sw_iot_command
     * （sourceType=FLOW，flowInstanceId=approvalBizId），Broker 接收与设备执行分状态记录，
     * 不合并为成功。
     *
     * @param tenantId      租户
     * @param deviceKey     设备业务标识
     * @param commandKey    命令标识
     * @param payload       载荷 JSON
     * @param approvalBizId 流程实例 ID（全链关联标识）
     * @return 命令记录 ID；设备/连接/下行主题不合格时返回 {@code Optional.empty()}（不适用），
     *         由调用方按其失败策略处理
     */
    Optional<Long> dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                                       String payload, String approvalBizId);

    /**
     * 同上，并可携带 A6 来源标识（FIXED / FORM_FIELD / VARIABLE）写入命令 sourceRef，
     * 用于三来源命令的可辨识审计（H3）。
     */
    Optional<Long> dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                                       String payload, String approvalBizId, String sourceTag);
}
