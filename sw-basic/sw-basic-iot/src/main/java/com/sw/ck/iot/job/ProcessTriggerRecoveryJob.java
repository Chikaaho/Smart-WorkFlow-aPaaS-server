package com.sw.ck.iot.job;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IoT 流程触发恢复调度（Phase 4 可靠业务事件 —— IoT 触发 MUST_DELIVER）。
 *
 * <p>触发意图在业务/脚本事务内已落 {@code sw_iot_process_trigger}（PENDING，唯一幂等键）。
 * 本调度负责“可恢复消费”：</p>
 * <ol>
 *   <li><b>认领</b>：条件更新 {@code PENDING/FAILED → PROCESSING} 且 retry_count 递增，
 *       多实例只有一个领取者；领取同时写退避时间，避免热循环；</li>
 *   <li><b>重投</b>：在显式事务内重新发布 {@link IotProcessTriggerEvent}（幂等键不变），
 *       由既有监听器执行；订阅方在提交后异步执行，失败同样可被下一轮再次认领；</li>
 *   <li><b>滞留回收</b>：{@code PROCESSING} 超时（消费者崩溃）回到 {@code PENDING}；</li>
 *   <li><b>终态</b>：retry_count 达上限后不再认领，行保持 FAILED + 错误原因（可审计；消除
 *       原先“PENDING 永远滞留、FAILED 无人再试”的静默丢失）。</li>
 * </ol>
 */
@Component
public class ProcessTriggerRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ProcessTriggerRecoveryJob.class);

    /** 触发恢复的重试预算（含首次执行后的失败重试）。 */
    static final int MAX_RETRY = 5;

    /** 单轮最多回收的滞留触发数。 */
    static final int MAX_BATCH = 50;

    /** PROCESSING 滞留判据（分钟）：超过则视为消费者崩溃并回收。 */
    static final int STALE_MINUTES = 5;

    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String FAILED = "FAILED";

    private final IotProcessTriggerMapper triggerMapper;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public ProcessTriggerRecoveryJob(IotProcessTriggerMapper triggerMapper,
                                     DomainEventPublisher eventPublisher,
                                     PlatformTransactionManager transactionManager) {
        this.triggerMapper = triggerMapper;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${sw.iot.trigger.recovery.fixed-delay-ms:60000}")
    public void recoverDue() {
        reclaimStale();
        List<IotProcessTrigger> due = findDue();
        if (due.isEmpty()) {
            return;
        }
        log.info("IoT 触发恢复调度：发现 {} 条待恢复触发", due.size());
        for (IotProcessTrigger trigger : due) {
            try {
                if (claim(trigger)) {
                    republish(trigger);
                }
            } catch (Exception e) {
                log.warn("IoT 触发恢复失败（下轮继续）: triggerId={}, error={}",
                        trigger.getId(), e.getClass().getSimpleName());
            }
        }
    }

    private void reclaimStale() {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            int reclaimed = triggerMapper.update(null,
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotProcessTrigger>lambdaUpdate()
                            .set(IotProcessTrigger::getStatus, PENDING)
                            .eq(IotProcessTrigger::getStatus, PROCESSING)
                            .le(IotProcessTrigger::getUpdateTime,
                                    LocalDateTime.now().minusMinutes(STALE_MINUTES)));
            if (reclaimed > 0) {
                log.warn("回收滞留 PROCESSING 触发（消费者可能已崩溃）: count={}", reclaimed);
            }
        }
    }

    private List<IotProcessTrigger> findDue() {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            return triggerMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotProcessTrigger>lambdaQuery()
                            .eq(IotProcessTrigger::getDeleted, 0)
                            .in(IotProcessTrigger::getStatus, PENDING, FAILED)
                            .lt(IotProcessTrigger::getRetryCount, MAX_RETRY)
                            .and(w -> w.isNull(IotProcessTrigger::getNextRetryTime)
                                    .or().le(IotProcessTrigger::getNextRetryTime, LocalDateTime.now()))
                            .isNotNull(IotProcessTrigger::getProcessTemplateKey)
                            .orderByAsc(IotProcessTrigger::getTriggerTime)
                            .last("LIMIT " + MAX_BATCH));
        }
    }

    /**
     * 原子认领：PENDING/FAILED → PROCESSING，retry_count+1 并写退避时间。
     *
     * <p>调度线程无登录态：与 {@link #findDue()}/{@link #reclaimStale()} 同口径挂起租户过滤，
     * 否则租户拦截器 fail-closed（"租户上下文缺失"）会让认领恒失败、恢复链整体失效。</p>
     */
    private boolean claim(IotProcessTrigger trigger) {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            return claimSuspended(trigger);
        }
    }

    private boolean claimSuspended(IotProcessTrigger trigger) {
        int nextRetry = (trigger.getRetryCount() == null ? 0 : trigger.getRetryCount()) + 1;
        int affected = triggerMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotProcessTrigger>lambdaUpdate()
                        .set(IotProcessTrigger::getStatus, PROCESSING)
                        .set(IotProcessTrigger::getRetryCount, nextRetry)
                        .set(IotProcessTrigger::getNextRetryTime,
                                LocalDateTime.now().plusMinutes(backoffMinutes(nextRetry)))
                        .eq(IotProcessTrigger::getId, trigger.getId())
                        .in(IotProcessTrigger::getStatus, PENDING, FAILED));
        return affected == 1;
    }

    /** 在显式事务内重投触发事件（幂等键不变，监听器在提交后异步执行）。 */
    private void republish(IotProcessTrigger trigger) {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            transactionTemplate.executeWithoutResult(status -> {
                IotProcessTriggerEvent event = new IotProcessTriggerEvent();
                event.setTenantId(trigger.getTenantId());
                event.setScriptId(trigger.getScriptId());
                event.setDeviceId(trigger.getDeviceId());
                event.setProcessTemplateKey(trigger.getProcessTemplateKey());
                event.setIdempotentKey(trigger.getIdempotentKey());
                event.setTriggerSource(trigger.getTriggerSource());
                event.setConfiguredBy(trigger.getConfiguredBy());
                event.setTriggerId(trigger.getId());
                Map<String, Object> formData = new LinkedHashMap<>();
                if (trigger.getFormSnapshot() != null && !trigger.getFormSnapshot().isBlank()) {
                    try {
                        formData = com.alibaba.fastjson2.JSON.parseObject(trigger.getFormSnapshot(), Map.class);
                    } catch (Exception e) {
                        log.warn("触发快照解析失败，按空表单重投: triggerId={}", trigger.getId());
                    }
                }
                event.setFormData(formData);
                eventPublisher.publish(event);
                log.info("IoT 触发已重投: triggerId={}, idempotentKey={}, retryCount={}",
                        trigger.getId(), trigger.getIdempotentKey(), trigger.getRetryCount());
            });
        }
    }

    /** 指数退避：1/2/4/8/16 分钟（封顶 16）。 */
    static long backoffMinutes(int retryCount) {
        return Math.min(16L, 1L << Math.min(Math.max(retryCount - 1, 0), 4));
    }
}
