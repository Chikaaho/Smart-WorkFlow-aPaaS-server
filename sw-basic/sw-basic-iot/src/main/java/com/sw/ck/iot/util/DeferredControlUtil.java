package com.sw.ck.iot.util;

import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 延迟生效类控制工具。
 * <p>
 * 用途：业务只要求"命令已可靠接受"，允许设备当前离线，设备上线后再处理。
 * </p>
 * <ul>
 * <li>调用成功只表示命令已持久化进入本地待发送队列，不表示腾讯已发送，更不表示设备已执行。</li>
 * <li>返回本地命令标识和 QUEUED 状态。</li>
 * <li>如果设备当前在线，可以立即尝试发送；如果离线，保持待发送状态。</li>
 * <li>收到腾讯设备上线 Hook 后，以 productId + deviceName 查询该设备待发送命令并立即补发。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class DeferredControlUtil {

    private static final Logger log = LoggerFactory.getLogger(DeferredControlUtil.class);

    private final IotDeviceService iotDeviceService;
    private final CommandQueueService commandQueueService;
    private final DeviceControlProvider deviceControlProvider;

    public DeferredControlUtil(IotDeviceService iotDeviceService,
                               CommandQueueService commandQueueService,
                               DeviceControlProvider deviceControlProvider) {
        this.iotDeviceService = iotDeviceService;
        this.commandQueueService = commandQueueService;
        this.deviceControlProvider = deviceControlProvider;
    }

    /**
     * 属性控制（延迟生效语义）。
     *
     * @param productId   腾讯云产品 ID
     * @param deviceName  腾讯云设备名称
     * @param propertyJson 属性 JSON
     * @param approvalBizId 关联审批业务 ID（可 null）
     * @return 已入队的命令
     */
    public IotDeviceCommand controlProperty(String productId, String deviceName,
                                            String propertyJson, String approvalBizId) {
        IotDevice device = iotDeviceService.getByProductAndDeviceName(productId, deviceName);
        if (device == null) {
            throw new com.sw.ck.common.exception.BaseException(404,
                    "设备不存在: productId=" + productId + ", deviceName=" + deviceName);
        }

        // 生成幂等键
        String idempotentKey = UUID.randomUUID().toString();

        IotDeviceCommand command = new IotDeviceCommand();
        command.setProductId(productId);
        command.setDeviceName(deviceName);
        command.setDeviceKey(device.getDeviceKey());
        command.setCommandType("PROPERTY");
        command.setCommandKey("control_property");
        command.setSemanticMode("DEFERRED");
        command.setPayload(propertyJson);
        command.setIdempotentKey(idempotentKey);
        command.setExpiryTime(LocalDateTime.now().plusHours(24));
        command.setApprovalBizId(approvalBizId);

        commandQueueService.enqueue(command);
        log.info("属性控制命令已入队: productId={}, deviceName={}, idempotentKey={}",
                productId, deviceName, idempotentKey);

        // 如果设备在线，立即尝试发送
        String status = deviceControlProvider.queryDeviceStatus(productId, deviceName);
        if ("online".equals(status)) {
            sendCommand(command);
        }

        return command;
    }

    /**
     * 行为控制（延迟生效语义）。
     *
     * @param productId   腾讯云产品 ID
     * @param deviceName  腾讯云设备名称
     * @param actionId    行为 ID
     * @param inputJson   输入参数 JSON
     * @param approvalBizId 关联审批业务 ID（可 null）
     * @return 已入队的命令
     */
    public IotDeviceCommand controlAction(String productId, String deviceName,
                                          String actionId, String inputJson,
                                          String approvalBizId) {
        IotDevice device = iotDeviceService.getByProductAndDeviceName(productId, deviceName);
        if (device == null) {
            throw new com.sw.ck.common.exception.BaseException(404,
                    "设备不存在: productId=" + productId + ", deviceName=" + deviceName);
        }

        // 生成幂等键
        String idempotentKey = UUID.randomUUID().toString();

        IotDeviceCommand command = new IotDeviceCommand();
        command.setProductId(productId);
        command.setDeviceName(deviceName);
        command.setDeviceKey(device.getDeviceKey());
        command.setCommandType("ACTION");
        command.setCommandKey(actionId);
        command.setSemanticMode("DEFERRED");
        command.setPayload(inputJson);
        command.setIdempotentKey(idempotentKey);
        command.setExpiryTime(LocalDateTime.now().plusHours(24));
        command.setApprovalBizId(approvalBizId);

        commandQueueService.enqueue(command);
        log.info("行为控制命令已入队: productId={}, deviceName={}, actionId={}, idempotentKey={}",
                productId, deviceName, actionId, idempotentKey);

        // 如果设备在线，立即尝试发送
        String status = deviceControlProvider.queryDeviceStatus(productId, deviceName);
        if ("online".equals(status)) {
            sendCommand(command);
        }

        return command;
    }

    /**
     * 补发设备待发送命令（设备上线时调用）。
     *
     * @param productId  腾讯云产品 ID
     * @param deviceName 腾讯云设备名称
     */
    @Async
    public void flushDeviceCommands(String productId, String deviceName) {
        List<IotDeviceCommand> pendingCommands = commandQueueService.getPendingCommands(productId, deviceName);
        if (pendingCommands.isEmpty()) {
            log.debug("设备无待发送命令: productId={}, deviceName={}", productId, deviceName);
            return;
        }

        log.info("开始补发设备待发送命令: productId={}, deviceName={}, count={}",
                productId, deviceName, pendingCommands.size());

        for (IotDeviceCommand command : pendingCommands) {
            // 检查命令是否已过期
            if (command.getExpiryTime() != null && command.getExpiryTime().isBefore(LocalDateTime.now())) {
                commandQueueService.markExpired(command.getId());
                log.warn("命令已过期，跳过补发: id={}", command.getId());
                continue;
            }

            sendCommand(command);
        }
    }

    /**
     * 发送单条命令。
     */
    private void sendCommand(IotDeviceCommand command) {
        try {
            commandQueueService.markSending(command.getId());

            DeviceControlProvider.DeviceControlResult result;
            if ("PROPERTY".equals(command.getCommandType())) {
                result = deviceControlProvider.controlDeviceData(
                        command.getProductId(), command.getDeviceName(), command.getPayload());
            } else {
                result = deviceControlProvider.callDeviceActionSync(
                        command.getProductId(), command.getDeviceName(),
                        command.getCommandKey(), command.getPayload());
            }

            if (result.success()) {
                commandQueueService.markSent(command.getId(), result.requestId());
                log.info("命令已发送: id={}, requestId={}", command.getId(), result.requestId());
            } else {
                commandQueueService.markFailed(command.getId(), result.errorMessage());
                log.warn("命令发送失败: id={}, error={}", command.getId(), result.errorMessage());
            }
        } catch (Exception e) {
            commandQueueService.markFailed(command.getId(), e.getMessage());
            log.error("命令发送异常: id={}", command.getId(), e);
        }
    }
}
