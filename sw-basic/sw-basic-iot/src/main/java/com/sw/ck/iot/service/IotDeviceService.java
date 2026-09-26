package com.sw.ck.iot.service;

import com.sw.ck.common.service.BaseService;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;

import java.util.List;

/**
 * IoT 设备控制服务：注册 / 状态查询 / 命令下发 / 结果回写。
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，二者均不可为空。
 * </p>
 */
public interface IotDeviceService extends BaseService<IotDevice> {

    /**
     * 注册设备（productId + deviceName 已存在时抛业务异常）。
     *
     * @param device 设备（productId/deviceName/name 必填）
     * @return 持久化后的实体
     */
    IotDevice register(IotDevice device);

    /**
     * 按腾讯云产品 ID 和设备名称查询设备。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     * @return 设备（可能为 null）
     */
    IotDevice getByProductAndDeviceName(String productId, String deviceName);

    /**
     * 按 deviceKey 查询设备（保留兼容）。
     *
     * @param deviceKey 设备业务标识
     * @return 设备（可能为 null）
     */
    IotDevice getByDeviceKey(String deviceKey);

    /**
     * 下发控制命令（延迟生效语义）。
     * <p>
     * 命令落库为 QUEUED；设备在线时可立即尝试发送。
     *
     * @param productId     腾讯云产品 ID
     * @param deviceName    腾讯云设备名称
     * @param commandKey    命令标识
     * @param commandType   命令类型（PROPERTY / ACTION）
     * @param payload       命令负载（JSON 字符串）
     * @param approvalBizId 关联审批业务 ID（可 null）
     * @return 已入队的命令记录
     */
    IotDeviceCommand dispatchCommand(String productId, String deviceName,
                                     String commandKey, String commandType,
                                     String payload, String approvalBizId);

    /**
     * 入队设备命令（调用方指定稳定幂等键）。
     *
     * <p>仅落库入队，不执行外部 I/O；同一幂等键重复调用返回既有命令，不新建记录。</p>
     */
    IotDeviceCommand dispatchCommandIdempotent(String productId, String deviceName,
            String commandKey, String commandType, String payload, String approvalBizId,
            String idempotentKey);

    /**
     * 设备回写执行结果（真实设备回调链路）。
     *
     * @param commandId 命令 ID
     * @param status    结果状态（SUCCESS / FAILED）
     * @param result    结果 JSON
     * @return 更新后的命令记录
     */
    IotDeviceCommand reportResult(Long commandId, String status, String result);

    /**
     * 查询设备的命令列表（按创建时间倒序）。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     * @return 命令列表
     */
    List<IotDeviceCommand> listCommands(String productId, String deviceName);

    /**
     * 按 ID 查询命令。
     *
     * @param commandId 命令 ID
     * @return 命令记录（可能为 null）
     */
    IotDeviceCommand getCommand(Long commandId);
}
