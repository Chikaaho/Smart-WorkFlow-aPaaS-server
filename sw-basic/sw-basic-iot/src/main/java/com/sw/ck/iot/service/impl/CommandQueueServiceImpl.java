package com.sw.ck.iot.service.impl;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.tenant.TenantWorkAdmission;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import lombok.extern.log4j.Log4j2;
/**
 * 命令队列服务实现。
 * <p>
 * 管理 IoT 设备命令队列，支持并发控制、幂等性和过期处理。
 * </p>
 */
@Service
@Log4j2
public class CommandQueueServiceImpl implements CommandQueueService {

    @Autowired
    private IotDeviceCommandMapper commandMapper;
    @Autowired
    private TenantWorkAdmission tenantWorkAdmission;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand enqueue(IotDeviceCommand command) {
        // 检查幂等键
        if (command.getIdempotentKey() != null && isIdempotentKeyExists(command.getIdempotentKey())) {
            throw new BaseException(400, "幂等键已存在: " + command.getIdempotentKey());
        }

        command.setStatus("QUEUED");
        command.setRetryCount(0);
        commandMapper.insert(command);

        log.info("命令已入队: id={}, productId={}, deviceName={}, idempotentKey={}",
                command.getId(), command.getProductId(), command.getDeviceName(), command.getIdempotentKey());
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markSending(Long commandId) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }
        if (!"QUEUED".equals(command.getStatus()) && !"FAILED".equals(command.getStatus())) {
            throw new BaseException(400, "命令状态不允许发送: " + command.getStatus());
        }

        int claimed = tenantWorkAdmission.claim(command.getTenantId(), () -> commandMapper.update(null,
                Wrappers.<IotDeviceCommand>lambdaUpdate()
                        .set(IotDeviceCommand::getStatus, "SENDING")
                        .set(IotDeviceCommand::getUpdateTime, LocalDateTime.now())
                        .eq(IotDeviceCommand::getId, commandId)
                        .eq(IotDeviceCommand::getTenantId, command.getTenantId())
                        .eq(IotDeviceCommand::getStatus, command.getStatus())
                        .and(q -> q.isNull(IotDeviceCommand::getExpiryTime)
                                .or().gt(IotDeviceCommand::getExpiryTime, LocalDateTime.now()))));
        if (claimed != 1) return null;
        command.setStatus("SENDING");

        log.debug("命令已标记为发送中: id={}", commandId);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markSent(Long commandId, String requestId) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        command.setStatus("SENT");
        command.setTencentRequestId(requestId);
        commandMapper.updateById(command);

        log.info("命令已标记为腾讯已发送: id={}, requestId={}", commandId, requestId);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markDelivered(Long commandId) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        command.setStatus("DELIVERED");
        commandMapper.updateById(command);

        log.debug("命令已标记为设备已送达: id={}", commandId);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markAcked(Long commandId, String clientToken, String outputParams) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        command.setStatus("ACKED");
        command.setClientToken(clientToken);
        command.setDeviceOutput(outputParams);
        commandMapper.updateById(command);

        log.info("命令已标记为设备已回复: id={}, clientToken={}", commandId, clientToken);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markSuccess(Long commandId, String result) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        command.setStatus("SUCCESS");
        command.setResult(result);
        commandMapper.updateById(command);

        log.info("命令已标记为成功: id={}", commandId);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markFailed(Long commandId, String error) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        String previous = command.getStatus();
        int retries = (command.getRetryCount() == null ? 0 : command.getRetryCount()) + 1;
        if ("QUEUED".equals(previous) || "FAILED".equals(previous)) {
            int changed = tenantWorkAdmission.claim(command.getTenantId(), () -> commandMapper.update(null,
                    Wrappers.<IotDeviceCommand>lambdaUpdate()
                            .set(IotDeviceCommand::getStatus, "FAILED")
                            .set(IotDeviceCommand::getLastError, error)
                            .set(IotDeviceCommand::getRetryCount, retries)
                            .set(IotDeviceCommand::getUpdateTime, LocalDateTime.now())
                            .eq(IotDeviceCommand::getId, commandId)
                            .eq(IotDeviceCommand::getTenantId, command.getTenantId())
                            .eq(IotDeviceCommand::getStatus, previous)));
            if (changed != 1) return command;
            command.setStatus("FAILED");
            command.setLastError(error);
            command.setRetryCount(retries);
        } else {
            command.setStatus("FAILED");
            command.setLastError(error);
            command.setRetryCount(retries);
            commandMapper.updateById(command);
        }

        log.warn("命令已标记为失败: id={}, error={}, retryCount={}",
                commandId, error, command.getRetryCount());
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markUnknown(Long commandId, String error) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        command.setStatus("UNKNOWN");
        command.setLastError(error);
        commandMapper.updateById(command);

        log.warn("命令已标记为结果未知: id={}, error={}", commandId, error);
        return command;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDeviceCommand markExpired(Long commandId) {
        IotDeviceCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new BaseException(404, "命令不存在: " + commandId);
        }

        command.setStatus("EXPIRED");
        commandMapper.updateById(command);

        log.warn("命令已标记为已过期: id={}", commandId);
        return command;
    }

    @Override
    public List<IotDeviceCommand> getPendingCommands(String productId, String deviceName) {
        Long tenantId = getCurrentTenantId();
        return commandMapper.selectPendingByProductAndDevice(productId, deviceName, tenantId);
    }

    @Override
    public List<IotDeviceCommand> getExpiredCommands() {
        // 补偿调度线程无登录态：挂起租户过滤（补偿作业跨租户扫描过期命令）
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            return commandMapper.selectExpired(LocalDateTime.now(), null);
        }
    }

    @Override
    public List<IotDeviceCommand> getReceiptTimeoutCommands() {
        // 补偿调度线程无登录态：挂起租户过滤（同 getExpiredCommands 口径）
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            return commandMapper.selectReceiptTimeout(LocalDateTime.now(), null);
        }
    }

    @Override
    public List<IotDeviceCommand> getStuckCommands(int stuckMinutes) {
        // 补偿调度线程无登录态：挂起租户过滤（同 getExpiredCommands 口径）
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            LocalDateTime since = LocalDateTime.now().minusMinutes(stuckMinutes);
            // 查询所有 QUEUED 状态且创建时间早于阈值的命令
            return commandMapper.selectList(
                    Wrappers.<IotDeviceCommand>lambdaQuery()
                            .eq(IotDeviceCommand::getDeleted, 0)
                            .eq(IotDeviceCommand::getStatus, "QUEUED")
                            .le(IotDeviceCommand::getCreateTime, since));
        }
    }

    @Override
    public boolean claimQueuedForSend(Long commandId, LocalDateTime staleBefore) {
        IotDeviceCommand owner = commandMapper.selectById(commandId);
        if (owner == null) return false;
        int affected = tenantWorkAdmission.claim(owner.getTenantId(), () -> commandMapper.update(null,
                Wrappers.<IotDeviceCommand>lambdaUpdate()
                        .set(IotDeviceCommand::getStatus, "SENDING")
                        // 领取即刷新租约时间：发送者崩溃后据此回收（wrapper 更新不会自动填充）
                        .set(IotDeviceCommand::getUpdateTime, LocalDateTime.now())
                        .eq(IotDeviceCommand::getId, commandId)
                        .eq(IotDeviceCommand::getTenantId, owner.getTenantId())
                        .eq(IotDeviceCommand::getStatus, "QUEUED")
                        .le(IotDeviceCommand::getUpdateTime, staleBefore)
                        .and(q -> q.isNull(IotDeviceCommand::getExpiryTime)
                                .or().gt(IotDeviceCommand::getExpiryTime, LocalDateTime.now()))));
        return affected == 1;
    }

    @Override
    public boolean claimFailedForRetry(Long commandId, int maxRetryCount) {
        IotDeviceCommand owner = commandMapper.selectById(commandId);
        if (owner == null) return false;
        int affected = tenantWorkAdmission.claim(owner.getTenantId(), () -> commandMapper.update(null,
                Wrappers.<IotDeviceCommand>lambdaUpdate()
                        .set(IotDeviceCommand::getStatus, "SENDING")
                        .setSql("retry_count = retry_count + 1")
                        .set(IotDeviceCommand::getUpdateTime, LocalDateTime.now())
                        .eq(IotDeviceCommand::getId, commandId)
                        .eq(IotDeviceCommand::getTenantId, owner.getTenantId())
                        .eq(IotDeviceCommand::getStatus, "FAILED")
                        .lt(IotDeviceCommand::getRetryCount, maxRetryCount)
                        .and(q -> q.isNull(IotDeviceCommand::getExpiryTime)
                                .or().gt(IotDeviceCommand::getExpiryTime, LocalDateTime.now()))));
        return affected == 1;
    }

    @Override
    public List<IotDeviceCommand> findRetryableFailed(int maxRetryCount, int limit) {
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            return commandMapper.selectList(
                    Wrappers.<IotDeviceCommand>lambdaQuery()
                            .eq(IotDeviceCommand::getDeleted, 0)
                            .eq(IotDeviceCommand::getStatus, "FAILED")
                            .lt(IotDeviceCommand::getRetryCount, maxRetryCount)
                            .gt(IotDeviceCommand::getExpiryTime, LocalDateTime.now())
                            .orderByAsc(IotDeviceCommand::getCreateTime)
                            .last("LIMIT " + Math.max(1, limit)));
        }
    }

    @Override
    public List<IotDeviceCommand> findStuckQueued(int stuckMinutes, int limit) {
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            return commandMapper.selectList(
                    Wrappers.<IotDeviceCommand>lambdaQuery()
                            .eq(IotDeviceCommand::getDeleted, 0)
                            .eq(IotDeviceCommand::getStatus, "QUEUED")
                            .le(IotDeviceCommand::getUpdateTime,
                                    LocalDateTime.now().minusMinutes(stuckMinutes))
                            .orderByAsc(IotDeviceCommand::getCreateTime)
                            .last("LIMIT " + Math.max(1, limit)));
        }
    }

    @Override
    public List<IotDeviceCommand> findStaleSending(int staleMinutes, int limit) {
        // 补偿调度线程无登录态：与其余补偿查询同口径挂起租户过滤
        try (Suspended ignored =
                     TenantLineSuspension.suspended()) {
            return commandMapper.selectList(
                    Wrappers.<IotDeviceCommand>lambdaQuery()
                            .eq(IotDeviceCommand::getDeleted, 0)
                            .eq(IotDeviceCommand::getStatus, "SENDING")
                            .le(IotDeviceCommand::getUpdateTime,
                                    LocalDateTime.now().minusMinutes(staleMinutes))
                            .orderByAsc(IotDeviceCommand::getCreateTime)
                            .last("LIMIT " + Math.max(1, limit)));
        }
    }

    @Override
    public boolean reclaimStaleSending(Long commandId, LocalDateTime staleBefore) {
        int affected = commandMapper.update(null,
                Wrappers.<IotDeviceCommand>lambdaUpdate()
                        .set(IotDeviceCommand::getStatus, "FAILED")
                        .set(IotDeviceCommand::getLastError, "发送租约过期（发送者可能已崩溃）")
                        .set(IotDeviceCommand::getUpdateTime, LocalDateTime.now())
                        .eq(IotDeviceCommand::getId, commandId)
                        .eq(IotDeviceCommand::getStatus, "SENDING")
                        .le(IotDeviceCommand::getUpdateTime, staleBefore));
        if (affected > 0) {
            log.warn("回收滞留 SENDING 命令（发送者可能已崩溃）: id={}", commandId);
        }
        return affected == 1;
    }

    @Override
    public boolean isIdempotentKeyExists(String idempotentKey) {
        return commandMapper.selectByIdempotentKey(idempotentKey) != null;
    }

    /**
     * 获取当前租户 ID。
     */
    private Long getCurrentTenantId() {
        LoginUser current = LoginUserHolder.get();
        return current == null ? null : current.getTenantId();
    }
}
