package com.sw.ck.iot.util;

import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * <li>provider 未装配（IoT 未启用腾讯云 provider）时按既有异常体系 fail closed：不返回模拟成功，
 * 也不落任何已发送状态。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class DeferredControlUtil {

    /** 控制通道未装配时的失败码（与基础设施不可用的既有约定一致）。 */
    private static final int CHANNEL_UNAVAILABLE_CODE = 503;

    /** 命令失败原因：控制通道未装配（写入命令 last_error，供补偿与排障）。 */
    static final String CHANNEL_UNAVAILABLE_REASON =
            "发送通道未装配：无设备控制 provider（IoT 未启用或 provider-mode 非 tencent）";

    private static final Logger log = LoggerFactory.getLogger(DeferredControlUtil.class);

    private final IotDeviceService iotDeviceService;
    private final CommandQueueService commandQueueService;
    private final ObjectProvider<DeviceControlProvider> deviceControlProvider;

    public DeferredControlUtil(IotDeviceService iotDeviceService,
                               CommandQueueService commandQueueService,
                               ObjectProvider<DeviceControlProvider> deviceControlProvider) {
        this.iotDeviceService = iotDeviceService;
        this.commandQueueService = commandQueueService;
        this.deviceControlProvider = deviceControlProvider;
    }

    /**
     * 取当前装配的设备控制 provider。
     *
     * <p>provider 可缺省：IoT 未启用、或 provider-mode 未启用腾讯云时不装配任何实现。此时需要
     * provider 的操作在调用点显式失败，既不降级为模拟成功，也不落任何「已下发」状态。</p>
     */
    private DeviceControlProvider requireProvider() {
        DeviceControlProvider provider = deviceControlProvider.getIfAvailable();
        if (provider == null) {
            throw new com.sw.ck.common.exception.BaseException(CHANNEL_UNAVAILABLE_CODE,
                    "IoT 控制通道未装配：当前无设备控制 provider，控制命令未下发");
        }
        return provider;
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

        // 无控制通道时不得产生"已受理"的假象：入队前先显式失败
        DeviceControlProvider provider = requireProvider();

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
        String status = provider.queryDeviceStatus(productId, deviceName);
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

        // 无控制通道时不得产生"已受理"的假象：入队前先显式失败
        DeviceControlProvider provider = requireProvider();

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
        String status = provider.queryDeviceStatus(productId, deviceName);
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
     * 发送单条已入队命令（Phase 4：设备命令补偿调度复用同一发送路径）。
     * <p>PROPERTY 走属性控制、其余走行为调用；成功 markSent、失败 markFailed（可重试）。</p>
     * <p>控制通道未装配时不进入 SENDING，直接记为可重试失败——绝不标记为已发送。</p>
     */
    public void sendCommand(IotDeviceCommand command) {
        DeviceControlProvider provider = deviceControlProvider.getIfAvailable();
        if (provider == null) {
            commandQueueService.markFailed(command.getId(), CHANNEL_UNAVAILABLE_REASON);
            log.warn("控制通道未装配，命令记为可重试失败（未下发）: id={}", command.getId());
            return;
        }
        try {
            commandQueueService.markSending(command.getId());

            DeviceControlProvider.DeviceControlResult result;
            if ("PROPERTY".equals(command.getCommandType())) {
                result = provider.controlDeviceData(
                        command.getProductId(), command.getDeviceName(), command.getPayload());
            } else {
                result = provider.callDeviceActionSync(
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
