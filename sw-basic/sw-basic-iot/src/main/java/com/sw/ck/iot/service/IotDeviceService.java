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
    /**
     * 按审批业务 ID 回查关联设备命令（P62 S4 关联回查；实现侧挂起租户过滤，
     * 以显式 tenantId 为边界）。
     *
     * @param tenantId      租户 ID
     * @param approvalBizId 审批业务 ID（流程实例 ID）
     * @return 关联命令列表（无关联时为空列表）
     */
    java.util.List<com.sw.ck.iot.entity.IotDeviceCommand> findByApprovalBizId(Long tenantId, String approvalBizId);

    /**
     * 按管理端设备记录 ID 解析设备执行目标（P63 预约冻结用；显式租户，跨租户为 null）。
     */
    com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget resolveDeviceTarget(Long tenantId, Long deviceId);

    /**
     * 按设备业务标识解析执行目标（P63 到点认领重核；显式租户，无效/跨租户为 null）。
     */
    com.sw.ck.iot.api.IotDeviceFacade.DeviceTarget resolveDeviceTargetByKey(Long tenantId, String deviceKey);

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

    /**
     * 校验设备产品已发布物模型功能权威（P63 §4.3 冻结前 fail-closed + 到点重核）。
     * <p>
     * 按 deviceKey+租户权威解析设备（发布态+流程接入），再沿 product_ref_id →
     * 已发布物模型（published_model_id）真实结构校验 commandKey 是否在
     * commandType 对应功能数组中声明；缺模型、未发布、功能未声明或类型未知一律
     * 抛业务异常（fail closed），不因记录非空放行。
     * </p>
     *
     * @param tenantId    租户
     * @param deviceKey   设备稳定标识
     * @param commandType 功能类型（PROPERTY→properties，ACTION→actions）
     * @param commandKey  功能 ID/键
     */
    void validatePublishedFunction(Long tenantId, String deviceKey, String commandType, String commandKey);
}
