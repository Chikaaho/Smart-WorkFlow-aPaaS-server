package com.sw.ck.iot.api.impl;

import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.service.IotDeviceService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * IoT 设备门面实现。
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，委托 IotDeviceService 处理业务逻辑。
 * </p>
 */
@Component
public class IotDeviceFacadeImpl implements IotDeviceFacade {

    private final IotDeviceService iotDeviceService;


    @Override
    public Long dispatchCommand(String productId, String deviceName,
                                String commandKey, String commandType,
                                String payload, String approvalBizId) {
        IotDeviceCommand command = iotDeviceService.dispatchCommand(
                productId, deviceName, commandKey, commandType, payload, approvalBizId);
        return command.getId();
    }

    private final IotDeviceMqttDispatchService mqttDispatchService;

    public IotDeviceFacadeImpl(IotDeviceService iotDeviceService,
                               @Lazy IotDeviceMqttDispatchService mqttDispatchService) {
        this.iotDeviceService = iotDeviceService;
        this.mqttDispatchService = mqttDispatchService;
    }

    @Override
    public Long dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                                    String payload, String approvalBizId) {
        return dispatchByDeviceKey(tenantId, deviceKey, commandKey, payload, approvalBizId, null);
    }

    @Override
    public Long dispatchByDeviceKey(Long tenantId, String deviceKey, String commandKey,
                                    String payload, String approvalBizId, String sourceTag) {
        return mqttDispatchService.dispatch(tenantId, deviceKey, commandKey, payload, approvalBizId, sourceTag);
    }
}
