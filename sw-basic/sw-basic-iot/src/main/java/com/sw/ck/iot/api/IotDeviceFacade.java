package com.sw.ck.iot.api;

/**
 * IoT 设备门面（跨模块契约，参照 NotifyFacade 模式）。
 * <p>
 * 供 sw-biz 层（如 BPM 审批通过后驱动设备）调用，
 * sw-biz 依赖本接口所在模块，不依赖 IoT 内部实现。
 * </p>
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，二者均不可为空。
 * </p>
 */
public interface IotDeviceFacade {

    /**
     * 下发设备控制命令（延迟生效语义）。
     * <p>
     * 命令入队，设备离线时等待上线补发；设备在线时可立即尝试发送。
     * 调用成功只表示命令已持久化进入本地待发送队列，不表示腾讯已发送。
     *
     * @param productId     腾讯云产品 ID
     * @param deviceName    腾讯云设备名称
     * @param commandKey    命令标识
     * @param commandType   命令类型（PROPERTY / ACTION）
     * @param payload       命令负载（JSON 字符串）
     * @param approvalBizId 关联审批业务 ID（流程实例 ID，可 null）
     * @return 命令记录 ID
     */
    Long dispatchCommand(String productId, String deviceName,
                         String commandKey, String commandType,
                         String payload, String approvalBizId);

    /**
     * 按 deviceKey 下发命令（P21 A6：MQTT 设备统一命令路径）。
     * <p>
     * 运行时重校验：设备必须 PUBLISHED + 流程接入开启 + 连接启用；
     * 命令写 sw_iot_command（sourceType=FLOW，flowInstanceId=approvalBizId），
     * Broker 接收与设备执行分状态记录，不合并为成功。
     *
     * @param tenantId      租户
     * @param deviceKey     设备业务标识
     * @param commandKey    命令标识
     * @param payload       载荷 JSON
     * @param approvalBizId 流程实例 ID（全链关联标识）
     * @return 命令记录 ID；设备不合格返回 null（失败策略由调用方处理）
     */
    Long dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                             String payload, String approvalBizId);

    /**
     * 同上，并可携带 A6 来源标识（FIXED / FORM_FIELD / VARIABLE）写入命令 sourceRef，
     * 用于三来源命令的可辨识审计（H3）。
     */
    Long dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                             String payload, String approvalBizId, String sourceTag);
}
