package com.sw.ck.iot.util;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.IotDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 在线确认类控制工具。
 * <p>
 * 用途：业务要求设备此刻在线，并确认控制信号已送达或设备已经回复。
 * </p>
 * <ul>
 * <li>调用前必须查询腾讯设备状态；离线、未激活、设备不存在或状态查询失败时直接失败。</li>
 * <li>属性控制：只有腾讯返回设备在线且已向订阅控制 Topic 发送的结果时，才能标记为 SENT。</li>
 * <li>行为控制：只有收到设备行为回复并取得成功状态时，才能标记为 ACKED/SUCCESS。</li>
 * <li>超时、不可达、未订阅、未授权、限流和物模型参数非法必须返回清晰失败。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class OnlineConfirmControlUtil {

    private static final Logger log = LoggerFactory.getLogger(OnlineConfirmControlUtil.class);

    private final IotDeviceService iotDeviceService;
    private final DeviceControlProvider deviceControlProvider;

    public OnlineConfirmControlUtil(IotDeviceService iotDeviceService,
                                    DeviceControlProvider deviceControlProvider) {
        this.iotDeviceService = iotDeviceService;
        this.deviceControlProvider = deviceControlProvider;
    }

    /**
     * 属性控制（在线确认语义）。
     *
     * @param productId   腾讯云产品 ID
     * @param deviceName  腾讯云设备名称
     * @param propertyJson 属性 JSON
     * @return 控制结果
     * @throws BaseException 设备离线或控制失败时
     */
    public IotDeviceCommand controlPropertyOnline(String productId, String deviceName,
                                                  String propertyJson) {
        // 1. 查询设备在线状态
        String status = deviceControlProvider.queryDeviceStatus(productId, deviceName);
        if (!"online".equals(status)) {
            throw new BaseException(400, "设备离线，无法执行在线确认控制: status=" + status);
        }

        // 2. 调用腾讯云属性下发
        DeviceControlProvider.DeviceControlResult result = deviceControlProvider.controlDeviceData(
                productId, deviceName, propertyJson);

        if (!result.success()) {
            throw new BaseException(500, "腾讯云 API 调用失败: " + result.errorMessage());
        }

        // 3. 创建命令记录并标记为 SENT
        IotDevice device = iotDeviceService.getByProductAndDeviceName(productId, deviceName);
        IotDeviceCommand command = new IotDeviceCommand();
        command.setProductId(productId);
        command.setDeviceName(deviceName);
        command.setDeviceKey(device != null ? device.getDeviceKey() : null);
        command.setCommandType("PROPERTY");
        command.setCommandKey("control_property_online");
        command.setSemanticMode("ONLINE_CONFIRM");
        command.setPayload(propertyJson);
        command.setIdempotentKey(UUID.randomUUID().toString());
        command.setExpiryTime(LocalDateTime.now().plusMinutes(5));
        command.setRetryCount(0);
        command.setStatus("SENT");
        command.setTencentRequestId(result.requestId());

        return command;
    }

    /**
     * 行为控制（在线确认语义）。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     * @param actionId   行为 ID
     * @param inputJson  输入参数 JSON
     * @return 控制结果
     * @throws BaseException 设备离线或控制失败时
     */
    public IotDeviceCommand controlActionOnline(String productId, String deviceName,
                                                String actionId, String inputJson) {
        // 1. 查询设备在线状态
        String status = deviceControlProvider.queryDeviceStatus(productId, deviceName);
        if (!"online".equals(status)) {
            throw new BaseException(400, "设备离线，无法执行在线确认控制: status=" + status);
        }

        // 2. 调用腾讯云同步行为调用
        DeviceControlProvider.DeviceControlResult result = deviceControlProvider.callDeviceActionSync(
                productId, deviceName, actionId, inputJson);

        if (!result.success()) {
            throw new BaseException(500, "腾讯云 API 调用失败: " + result.errorMessage());
        }

        // 3. 创建命令记录并标记为 ACKED
        IotDevice device = iotDeviceService.getByProductAndDeviceName(productId, deviceName);
        IotDeviceCommand command = new IotDeviceCommand();
        command.setProductId(productId);
        command.setDeviceName(deviceName);
        command.setDeviceKey(device != null ? device.getDeviceKey() : null);
        command.setCommandType("ACTION");
        command.setCommandKey(actionId);
        command.setSemanticMode("ONLINE_CONFIRM");
        command.setPayload(inputJson);
        command.setIdempotentKey(UUID.randomUUID().toString());
        command.setExpiryTime(LocalDateTime.now().plusMinutes(5));
        command.setRetryCount(0);
        command.setStatus("ACKED");
        command.setTencentRequestId(result.requestId());
        command.setClientToken(result.clientToken());
        command.setDeviceOutput(result.deviceOutput());

        return command;
    }
}
