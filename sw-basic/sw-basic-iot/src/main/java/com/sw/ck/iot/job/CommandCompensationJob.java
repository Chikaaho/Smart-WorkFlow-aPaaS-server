package com.sw.ck.iot.job;

import com.sw.ck.iot.config.TencentCloudProperties;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.util.DeferredControlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 命令补偿定时任务。
 * <p>
 * 检测长期滞留队列的命令，处理过期命令，重试瞬时失败。
 * </p>
 */
@Component
public class CommandCompensationJob {

    private static final Logger log = LoggerFactory.getLogger(CommandCompensationJob.class);

    /** 单轮最多补发的命令条数（有界，避免单轮长时间占用调度线程）。 */
    private static final int MAX_RETRY_BATCH = 50;

    /** 滞留判据：QUEUED 且超过该分钟数仍未更新。 */
    private static final int STUCK_MINUTES = 5;

    private final CommandQueueService commandQueueService;
    private final TencentCloudProperties tencentCloudProperties;

    /** 唯一生产发送路径（sw.iot.enabled=true 时才装配）；缺失时补偿只做过期处理并显式告警。 */
    private final ObjectProvider<DeferredControlUtil> deferredControlUtil;

    public CommandCompensationJob(CommandQueueService commandQueueService,
                                  TencentCloudProperties tencentCloudProperties,
                                  ObjectProvider<DeferredControlUtil> deferredControlUtil) {
        this.commandQueueService = commandQueueService;
        this.tencentCloudProperties = tencentCloudProperties;
        this.deferredControlUtil = deferredControlUtil;
    }

    /**
     * 每 5 分钟执行一次补偿任务。
     */
    @Scheduled(fixedDelay = 300000)
    public void execute() {
        log.debug("开始执行命令补偿任务");

        // 1. 处理过期命令
        processExpiredCommands();

        // 2. 处理滞留命令（真实补发）
        processStuckCommands();

        // 2.1 回收滞留发送中的命令（发送者崩溃留下的 SENDING 租约）
        processStaleSending();

        // 3. 重试瞬时失败（真实重试，受重试预算与过期时间约束）
        retryFailedCommands();

        log.debug("命令补偿任务执行完成");
    }

    /**
     * 处理过期命令。
     */
    private void processExpiredCommands() {
        List<IotDeviceCommand> expiredCommands = commandQueueService.getExpiredCommands();
        if (expiredCommands.isEmpty()) {
            return;
        }

        log.info("发现 {} 条过期命令，开始处理", expiredCommands.size());
        for (IotDeviceCommand command : expiredCommands) {
            withCommandTenant(command, () -> commandQueueService.markExpired(command.getId()));
            log.warn("命令已标记为过期: id={}, productId={}, deviceName={}, createTime={}",
                    command.getId(), command.getProductId(), command.getDeviceName(), command.getCreateTime());
        }
    }

    /**
     * 处理滞留命令（超过 30 分钟未处理的 QUEUED 命令）。
     */
    private void processStuckCommands() {
        List<IotDeviceCommand> stuckCommands = commandQueueService.getStuckCommands(30);
        if (stuckCommands.isEmpty()) {
            return;
        }

        log.info("发现 {} 条滞留命令，开始处理", stuckCommands.size());
        for (IotDeviceCommand command : stuckCommands) {
            withCommandTenant(command, () -> {
                // 检查是否已过期
                if (command.getExpiryTime() != null && command.getExpiryTime().isBefore(LocalDateTime.now())) {
                    commandQueueService.markExpired(command.getId());
                    log.warn("滞留命令已过期: id={}", command.getId());
                    return;
                }
                // Phase 4：真实补发——原子领取后走与设备上线补发相同的发送路径
                if (sendClaimed(command, true)) {
                    log.info("滞留命令已补发: id={}, retryCount={}", command.getId(), command.getRetryCount());
                }
            });
        }
    }

    /**
     * 回收滞留 SENDING 命令（发送者认领后崩溃）。
     *
     * <p>回收为可重试失败后由同一轮的重试步骤接管，因此本条队列不会永久停留在 SENDING。</p>
     */
    private void processStaleSending() {
        List<IotDeviceCommand> stale = commandQueueService.findStaleSending(STUCK_MINUTES, MAX_RETRY_BATCH);
        if (stale.isEmpty()) {
            return;
        }
        log.info("发现 {} 条滞留发送中命令，开始回收", stale.size());
        for (IotDeviceCommand command : stale) {
            withCommandTenant(command, () -> commandQueueService.reclaimStaleSending(command.getId(),
                    LocalDateTime.now().minusMinutes(STUCK_MINUTES)));
        }
    }

    /**
     * 重试瞬时失败（FAILED 状态且未超过最大重试次数）。
     */
    private void retryFailedCommands() {
        int maxRetryCount = tencentCloudProperties.getMaxRetryCount();
        List<IotDeviceCommand> failedCommands =
                commandQueueService.findRetryableFailed(maxRetryCount, MAX_RETRY_BATCH);
        if (failedCommands.isEmpty()) {
            return;
        }
        log.info("发现 {} 条可重试失败命令，开始重试（预算 {}）", failedCommands.size(), maxRetryCount);
        for (IotDeviceCommand command : failedCommands) {
            withCommandTenant(command, () -> {
                if (sendClaimed(command, false)) {
                    log.info("失败命令已重试: id={}, retryCount={}/{}",
                            command.getId(), command.getRetryCount(), maxRetryCount);
                }
            });
        }
    }

    /**
     * 补偿调度线程按命令自身的权威租户建立执行边界。
     *
     * <p>调度线程无登录身份，租户拦截器在无身份时 fail-closed：领取与状态回写都会失败。
     * 这里不使用"挂起租户过滤"（那会同时抹掉多租户约束），而是还原行自身的权威租户，
     * 与命令调度器从受理记录还原身份的做法一致。</p>
     */
    private void withCommandTenant(IotDeviceCommand command, Runnable action) {
        com.sw.ck.security.holder.LoginUser previous = com.sw.ck.security.holder.LoginUserHolder.get();
        com.sw.ck.security.holder.LoginUser identity = new com.sw.ck.security.holder.LoginUser();
        identity.setTenantId(command.getTenantId() == null ? 0L : command.getTenantId());
        identity.setUserId(com.sw.ck.common.constant.CommonConstants.SYSTEM_OPERATOR_ID);
        identity.setUsername("iot-command-compensation");
        try {
            com.sw.ck.security.holder.LoginUserHolder.set(identity);
            action.run();
        } finally {
            if (previous == null) {
                com.sw.ck.security.holder.LoginUserHolder.clear();
            } else {
                com.sw.ck.security.holder.LoginUserHolder.set(previous);
            }
        }
    }

    /**
     * 领取并发送一条命令；领取失败（被其他实例领取/状态已变化）时不发送，避免重复下发。
     *
     * @param queued true = 滞留 QUEUED 命令；false = 可重试 FAILED 命令
     * @return true = 本次领取成功并已尝试发送
     */
    private boolean sendClaimed(IotDeviceCommand command, boolean queued) {
        boolean claimed = queued
                ? commandQueueService.claimQueuedForSend(command.getId(),
                        LocalDateTime.now().minusMinutes(STUCK_MINUTES))
                : commandQueueService.claimFailedForRetry(command.getId(),
                        tencentCloudProperties.getMaxRetryCount());
        if (!claimed) {
            log.debug("命令领取未命中（已被其他实例领取或状态已变化）: id={}", command.getId());
            return false;
        }
        DeferredControlUtil sender = deferredControlUtil.getIfAvailable();
        if (sender == null) {
            // 发送通道未装配：显式回退为可重试失败（滞留 SENDING 由 processStaleSending 兜底回收）
            commandQueueService.markFailed(command.getId(), "发送通道未装配（sw.iot.enabled=false）");
            log.warn("发送通道未装配，命令回退为可重试失败: id={}", command.getId());
            return false;
        }
        sender.sendCommand(command);
        return true;
    }
}
