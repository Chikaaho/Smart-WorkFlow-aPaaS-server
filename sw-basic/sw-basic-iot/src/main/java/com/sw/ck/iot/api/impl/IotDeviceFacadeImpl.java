package com.sw.ck.iot.api.impl;

import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.service.IotDeviceService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * IoT 设备门面实现。
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，委托 IotDeviceService 处理业务逻辑。
 * 返回值语义见 {@link IotDeviceFacade}：入队成功为 present，设备/连接/下行主题不合格为 empty，
 * 设备不存在等真实错误继续抛出。
 * </p>
 */
@Component
public class IotDeviceFacadeImpl implements IotDeviceFacade {

    private final IotDeviceService iotDeviceService;

    @Override
    public Optional<Long> dispatchCommand(String productId, String deviceName,
                                          String commandKey, String commandType,
                                          String payload, String approvalBizId) {
        IotDeviceCommand command = iotDeviceService.dispatchCommand(
                productId, deviceName, commandKey, commandType, payload, approvalBizId);
        return Optional.ofNullable(command).map(IotDeviceCommand::getId);
    }

    @Override
    public Optional<Long> dispatchCommandIdempotent(String productId, String deviceName,
                                                    String commandKey, String commandType,
                                                    String payload, String approvalBizId,
                                                    String idempotentKey) {
        IotDeviceCommand command = iotDeviceService.dispatchCommandIdempotent(
                productId, deviceName, commandKey, commandType, payload, approvalBizId, idempotentKey);
        return Optional.ofNullable(command).map(IotDeviceCommand::getId);
    }

    @Override
    public java.util.Optional<java.util.List<com.sw.ck.iot.api.DeviceCommandSummary>> findByApprovalBizId(
            Long tenantId, String approvalBizId) {
        if (approvalBizId == null || approvalBizId.isBlank()) {
            return java.util.Optional.of(java.util.List.of());
        }
        // 跨模块回查按显式租户边界执行（调用方契约传入租户，不依赖线程登录态）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return java.util.Optional.of(iotDeviceService.findByApprovalBizId(tenantId, approvalBizId).stream()
                    .map(command -> new com.sw.ck.iot.api.DeviceCommandSummary(
                            command.getId(), command.getProductId(), command.getDeviceName(),
                            command.getCommandKey(), command.getStatus(), command.getResult(),
                            command.getUpdateTime()))
                    .toList());
        }
    }

    private final IotDeviceMqttDispatchService mqttDispatchService;

    public IotDeviceFacadeImpl(IotDeviceService iotDeviceService,
                               @Lazy IotDeviceMqttDispatchService mqttDispatchService) {
        this.iotDeviceService = iotDeviceService;
        this.mqttDispatchService = mqttDispatchService;
    }

    @Override
    public Optional<Long> dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                                              String payload, String approvalBizId) {
        return dispatchByDeviceKey(tenantId, deviceKey, commandKey, payload, approvalBizId, null);
    }

    @Override
    public Optional<Long> dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                                              String payload, String approvalBizId, String sourceTag) {
        return Optional.ofNullable(
                mqttDispatchService.dispatch(tenantId, deviceKey, commandKey, payload, approvalBizId, sourceTag));
    }
}
