package com.sw.ck.openapi.biz.job;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.openapi.biz.entity.OpenApiApp;
import com.sw.ck.openapi.biz.entity.OpenApiCallbackTask;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.openapi.biz.mapper.OpenApiCallbackTaskMapper;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站回调恢复调度（Phase 4 可靠业务事件 —— OpenAPI 回调 MUST_DELIVER）。
 *
 * <p>扫描业务事务内登记的持久回调任务（{@code sw_openapi_callback_task}），
 * 领取后复用既有签名投递实现（{@link OpenApiCallbackDeliveryService}，去重/签名/尝试流水不变）：</p>
 * <ul>
 *   <li>已投递成功（既有 SUCCESS 流水，通常由提交后加速完成）→ 仅关闭任务，不重复出站；</li>
 *   <li>原子领取（PENDING/FAILED → SENDING，attempts+1）：多实例只有一个领取者；</li>
 *   <li>失败按指数退避重排（1/2/4/8/16 分钟）；重试预算耗尽进入 {@code RETRY_EXHAUSTED}
 *       可审计终态，仍可由既有手工重发入口重投（重发成功后任务在下一轮被关闭为 SUCCESS）。</li>
 * </ul>
 */
@Component
public class OpenApiCallbackRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCallbackRecoveryJob.class);

    /** 恢复调度的重试预算（自提交后加速失败起算；不含进程内 3 次尝试）。 */
    static final int MAX_ATTEMPTS = 5;

    /** 单轮最多处理任务数（有界，避免长时间占用调度线程）。 */
    static final int MAX_BATCH = 50;

    /** 投递租约判据（分钟）：SENDING 超过该时长视为投递者已崩溃，回收后可继续重试。 */
    static final int STALE_LEASE_MINUTES = 5;

    private final OpenApiCallbackTaskMapper taskMapper;
    private final OpenApiAppMapper appMapper;
    private final OpenApiCallbackDeliveryService deliveryService;

    public OpenApiCallbackRecoveryJob(OpenApiCallbackTaskMapper taskMapper,
                                      OpenApiAppMapper appMapper,
                                      OpenApiCallbackDeliveryService deliveryService) {
        this.taskMapper = taskMapper;
        this.appMapper = appMapper;
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${sw.openapi.callback.recovery.fixed-delay-ms:60000}")
    public void recoverDue() {
        reclaimStaleLeases();
        List<OpenApiCallbackTask> due = findDue();
        if (due.isEmpty()) {
            return;
        }
        log.info("回调恢复调度：发现 {} 条到期任务", due.size());
        for (OpenApiCallbackTask task : due) {
            try {
                // 投递阶段（对端 HTTP 之外的一切：领取、查询、成功判定、结论文回写）都在
                // 任务自身租户身份下执行：调度线程无登录态，而租户拦截器在无身份时 fail-closed，
                // 逐一挂起过滤会同时抹掉多租户约束，故显式还原行自身的权威租户边界。
                withTenantScope(task.getTenantId(), () -> process(task));
            } catch (Exception e) {
                log.warn("回调恢复处理失败（留在可重试态，下轮继续）: taskId={}, error={}",
                        task.getId(), e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 租约回收：投递者认领（PENDING/FAILED → SENDING）后崩溃会把任务永久留在 SENDING
     * ——它既不满足到期扫描的状态集合，也没有其他路径能推进它。超过租约即回收：
     * 预算已耗尽转可审计终态，否则退回可重试失败并由同一轮扫描接管。
     */
    private void reclaimStaleLeases() {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(STALE_LEASE_MINUTES);
            int exhausted = taskMapper.update(null,
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaUpdate()
                            .set(OpenApiCallbackTask::getStatus, "RETRY_EXHAUSTED")
                            .set(OpenApiCallbackTask::getLastError, "投递租约过期且重试预算耗尽，可由手工重发入口重投")
                            .set(OpenApiCallbackTask::getNextRetryTime, null)
                            .eq(OpenApiCallbackTask::getStatus, "SENDING")
                            .ge(OpenApiCallbackTask::getAttempts, MAX_ATTEMPTS)
                            .le(OpenApiCallbackTask::getUpdateTime, staleBefore));
            int reclaimed = taskMapper.update(null,
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaUpdate()
                            .set(OpenApiCallbackTask::getStatus, "FAILED")
                            .set(OpenApiCallbackTask::getLastError, "投递租约过期（投递者可能已崩溃）")
                            .set(OpenApiCallbackTask::getNextRetryTime, LocalDateTime.now())
                            .eq(OpenApiCallbackTask::getStatus, "SENDING")
                            .lt(OpenApiCallbackTask::getAttempts, MAX_ATTEMPTS)
                            .le(OpenApiCallbackTask::getUpdateTime, staleBefore));
            if (reclaimed > 0 || exhausted > 0) {
                log.warn("回收滞留回调投递租约: reclaimed={}, exhausted={}", reclaimed, exhausted);
            }
        }
    }

    private List<OpenApiCallbackTask> findDue() {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return taskMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaQuery()
                            .eq(OpenApiCallbackTask::getDeleted, 0)
                            .in(OpenApiCallbackTask::getStatus, "PENDING", "FAILED")
                            .le(OpenApiCallbackTask::getNextRetryTime, LocalDateTime.now())
                            .lt(OpenApiCallbackTask::getAttempts, MAX_ATTEMPTS)
                            .orderByAsc(OpenApiCallbackTask::getNextRetryTime)
                            .last("LIMIT " + MAX_BATCH));
        }
    }

    private void process(OpenApiCallbackTask task) {
        BpmNotifyTrigger trigger = BpmNotifyTrigger.valueOf(task.getEvent());
        if (deliveryService.isDelivered(task.getAppId(), trigger, task.getBizRef())) {
            // 领取前对账：该 (应用,事件,业务对象) 已有成功投递，直接关闭任务；
            // 此处任务尚未被领取，条件绑定为未领取态，避免与并发领取者互相覆盖。
            closeUnclaimedDelivered(task);
            log.info("回调任务关闭（已投递成功）: taskId={}, appId={}, bizRef={}",
                    task.getId(), task.getAppId(), task.getBizRef());
            return;
        }
        int claimedAttempts = task.getAttempts() == null ? 1 : task.getAttempts() + 1;
        if (!claim(task)) {
            return;
        }
        OpenApiApp app = findApp(task);
        if (app == null || app.getCallbackUrl() == null || app.getCallbackUrl().isBlank()) {
            writeTerminal(task, claimedAttempts, "RETRY_EXHAUSTED", "应用已停用或未配置回调地址", false);
            return;
        }
        BpmNotifyEvent event = new BpmNotifyEvent(trigger, null, task.getTenantId(), null, task.getBizRef());
        deliveryService.dispatch(app, event);
        if (deliveryService.isDelivered(task.getAppId(), trigger, task.getBizRef())) {
            writeTerminal(task, claimedAttempts, "SUCCESS", null, true);
            log.info("回调任务完成: taskId={}, appId={}, attempts={}",
                    task.getId(), task.getAppId(), claimedAttempts);
            return;
        }
        if (claimedAttempts >= MAX_ATTEMPTS) {
            writeTerminal(task, claimedAttempts, "RETRY_EXHAUSTED", "重试预算耗尽，可由手工重发入口重投", false);
            log.warn("回调任务重试预算耗尽: taskId={}, appId={}, bizRef={}",
                    task.getId(), task.getAppId(), task.getBizRef());
            return;
        }
        writeFailed(task, claimedAttempts, "投递未成功，按退避重排");
    }

    /**
     * 原子领取：PENDING/FAILED → SENDING，attempts+1 并刷新租约时间；未命中说明已被其他实例领取。
     * 调度线程无登录身份，租户拦截器在无身份时 fail-closed，因此认领必须显式挂起租户过滤
     * （与 {@link #findDue()} 同口径），否则认领恒失败、恢复链整体失效。
     */
    private boolean claim(OpenApiCallbackTask task) {
        int affected = taskMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaUpdate()
                        .set(OpenApiCallbackTask::getStatus, "SENDING")
                        .set(OpenApiCallbackTask::getAttempts,
                                (task.getAttempts() == null ? 0 : task.getAttempts()) + 1)
                        .set(OpenApiCallbackTask::getUpdateTime, LocalDateTime.now())
                        .eq(OpenApiCallbackTask::getId, task.getId())
                        .in(OpenApiCallbackTask::getStatus, "PENDING", "FAILED"));
        return affected == 1;
    }

    private OpenApiApp findApp(OpenApiCallbackTask task) {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return appMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenApiApp>()
                    .eq(OpenApiApp::getAppId, task.getAppId())
                    .eq(OpenApiApp::getTenantId, task.getTenantId())
                    .eq(OpenApiApp::getDeleted, 0)
                    .last("LIMIT 1"));
        }
    }

    /** 未领取态下的关闭：只允许从未领取状态（PENDING/FAILED）改判成功。 */
    private void closeUnclaimedDelivered(OpenApiCallbackTask task) {
        taskMapper.update(null,
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaUpdate()
                            .set(OpenApiCallbackTask::getStatus, "SUCCESS")
                            .set(OpenApiCallbackTask::getNextRetryTime, null)
                            .set(OpenApiCallbackTask::getDeliveredAt, LocalDateTime.now())
                        .eq(OpenApiCallbackTask::getId, task.getId())
                        .in(OpenApiCallbackTask::getStatus, "PENDING", "FAILED"));
    }

    /**
     * 结论文写回绑定本次领取（SENDING + 领取后的 attempts），使租约被回收后
     * 旧投递者的迟到结论无法覆盖新持有者（与命令队列同一等强度条件）。
     */
    private void writeTerminal(OpenApiCallbackTask task, int claimedAttempts, String status, String error,
                               boolean delivered) {
        int updated = taskMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaUpdate()
                        .set(OpenApiCallbackTask::getStatus, status)
                        .set(OpenApiCallbackTask::getLastError, error)
                        .set(OpenApiCallbackTask::getNextRetryTime, null)
                        .set(OpenApiCallbackTask::getDeliveredAt,
                                delivered ? LocalDateTime.now() : task.getDeliveredAt())
                        .eq(OpenApiCallbackTask::getId, task.getId())
                        .eq(OpenApiCallbackTask::getStatus, "SENDING")
                        .eq(OpenApiCallbackTask::getAttempts, claimedAttempts));
        if (updated == 0) {
            log.warn("回调结论文写回被跳过: taskId={} 已离开本次领取权（租约被回收或已被他人推进）",
                    task.getId());
        }
    }

    private void writeFailed(OpenApiCallbackTask task, int claimedAttempts, String error) {
        int updated = taskMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OpenApiCallbackTask>lambdaUpdate()
                        .set(OpenApiCallbackTask::getStatus, "FAILED")
                        .set(OpenApiCallbackTask::getLastError, error)
                        .set(OpenApiCallbackTask::getNextRetryTime,
                                LocalDateTime.now().plusMinutes(backoffMinutes(claimedAttempts)))
                        .eq(OpenApiCallbackTask::getId, task.getId())
                        .eq(OpenApiCallbackTask::getStatus, "SENDING")
                        .eq(OpenApiCallbackTask::getAttempts, claimedAttempts));
        if (updated == 0) {
            log.warn("回调重排写回被跳过: taskId={} 已离开本次领取权", task.getId());
        }
    }

    /** 调度线程按任务自身的权威租户建立执行边界（对齐命令调度器还原身份的做法）。 */
    private void withTenantScope(Long tenantId, Runnable action) {
        com.sw.ck.security.holder.LoginUser previous = com.sw.ck.security.holder.LoginUserHolder.get();
        com.sw.ck.security.holder.LoginUser identity = new com.sw.ck.security.holder.LoginUser();
        identity.setTenantId(tenantId == null ? 0L : tenantId);
        identity.setUserId(com.sw.ck.common.constant.CommonConstants.SYSTEM_OPERATOR_ID);
        identity.setUsername("openapi-callback-recovery");
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

    /** 指数退避：1/2/4/8/16 分钟（封顶 16）。 */
    static long backoffMinutes(int attempts) {
        return Math.min(16L, 1L << Math.min(Math.max(attempts - 1, 0), 4));
    }
}
