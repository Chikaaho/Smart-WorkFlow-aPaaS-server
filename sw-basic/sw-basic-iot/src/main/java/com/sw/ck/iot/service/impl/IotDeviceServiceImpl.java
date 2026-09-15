package com.sw.ck.iot.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.service.IotDeviceService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * IoT 设备控制服务实现。
 * <p>
 * 设备身份固定为 {@code productId + deviceName}，二者均不可为空。
 * 支持两类控制语义：延迟生效（DEFERRED）和在线确认（ONLINE_CONFIRM）。
 * </p>
 */
@Service
public class IotDeviceServiceImpl extends BaseServiceImpl<IotDeviceMapper, IotDevice>
        implements IotDeviceService {

    private static final Logger log = LoggerFactory.getLogger(IotDeviceServiceImpl.class);

    private static final Set<String> REPORTABLE_STATUS = Set.of("SUCCESS", "FAILED");

    private final IotDeviceCommandMapper commandMapper;

    public IotDeviceServiceImpl(IotDeviceCommandMapper commandMapper) {
        this.commandMapper = commandMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDevice register(IotDevice device) {
        if (device.getProductId() == null || device.getProductId().isBlank()) {
            throw new BaseException(400, "productId 不能为空");
        }
        if (device.getDeviceName() == null || device.getDeviceName().isBlank()) {
            throw new BaseException(400, "deviceName 不能为空");
        }
        if (device.getName() == null || device.getName().isBlank()) {
            throw new BaseException(400, "设备名称不能为空");
        }
        IotDevice existing = getByProductAndDeviceName(device.getProductId(), device.getDeviceName());
        if (existing != null) {
            throw new BaseException(400, "设备已存在: productId=" + device.getProductId()
                    + ", deviceName=" + device.getDeviceName());
        }
        if (device.getStatus() == null || device.getStatus().isBlank()) {
            device.setStatus("OFFLINE");
        }
        if (device.getTencentStatus() == null || device.getTencentStatus().isBlank()) {
            device.setTencentStatus("offline");
        }
        save(device);
        log.info("设备已注册: productId={}, deviceName={}, name={}, status={}",
                device.getProductId(), device.getDeviceName(), device.getName(), device.getStatus());
        return device;
    }

    @Override
    public IotDevice getByProductAndDeviceName(String productId, String deviceName) {
        Long tenantId = getCurrentTenantId();
        return baseMapper.selectByProductAndDeviceName(productId, deviceName, tenantId);
    }

    @Override
    public IotDevice getByDeviceKey(String deviceKey) {
        return lambdaQuery()
                .eq(IotDevice::getDeviceKey, deviceKey)
                .last("limit 1")
                .one();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand dispatchCommand(String productId, String deviceName,
                                            String commandKey, String commandType,
                                            String payload, String approvalBizId) {
        IotDevice device = getByProductAndDeviceName(productId, deviceName);
        if (device == null) {
            throw new BaseException(404, "设备不存在: productId=" + productId
                    + ", deviceName=" + deviceName);
        }
        if (commandKey == null || commandKey.isBlank()) {
            throw new BaseException(400, "commandKey 不能为空");
        }
        if (commandType == null || commandType.isBlank()) {
            commandType = "PROPERTY";
        }

        // 生成幂等键
        String idempotentKey = UUID.randomUUID().toString();

        IotDeviceCommand command = new IotDeviceCommand();
        command.setProductId(productId);
        command.setDeviceName(deviceName);
        command.setDeviceKey(device.getDeviceKey());
        command.setCommandType(commandType);
        command.setCommandKey(commandKey);
        command.setSemanticMode("DEFERRED");
        command.setPayload(payload);
        command.setStatus("QUEUED");
        command.setIdempotentKey(idempotentKey);
        command.setExpiryTime(LocalDateTime.now().plusHours(24));
        command.setRetryCount(0);
        command.setApprovalBizId(approvalBizId);
        commandMapper.insert(command);

        log.info("设备命令已入队: id={}, productId={}, deviceName={}, commandKey={}, idempotentKey={}",
                command.getId(), productId, deviceName, commandKey, idempotentKey);

        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand reportResult(Long commandId, String status, String result) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }
        if (status == null || !REPORTABLE_STATUS.contains(status)) {
            throw new BaseException(400, "结果状态只能是 SUCCESS / FAILED");
        }
        command.setStatus(status);
        command.setResult(result);
        commandMapper.updateById(command);
        log.info("设备命令结果已回写: id={}, status={}", commandId, status);
        return command;
    }

    @Override
    public List<IotDeviceCommand> listCommands(String productId, String deviceName) {
        return commandMapper.selectList(
                Wrappers.<IotDeviceCommand>lambdaQuery()
                        .eq(IotDeviceCommand::getProductId, productId)
                        .eq(IotDeviceCommand::getDeviceName, deviceName)
                        .orderByDesc(IotDeviceCommand::getCreateTime));
    }

    @Override
    public IotDeviceCommand getCommand(Long commandId) {
        return commandMapper.selectById(commandId);
    }

    /**
     * 获取当前租户 ID（从 SecurityContext 中提取）。
     */
    private Long getCurrentTenantId() {
        LoginUser current = LoginUserHolder.get();
        return current == null ? null : current.getTenantId();
    }
}
