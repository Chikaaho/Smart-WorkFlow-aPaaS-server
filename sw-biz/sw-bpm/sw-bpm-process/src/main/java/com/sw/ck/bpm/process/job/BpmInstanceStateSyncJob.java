package com.sw.ck.bpm.process.job;

import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 轻流程实例状态对账（P62 分级执行 S6/G7）。
 *
 * <p>TXN_ACTION 节点为 async 独立短事务：流程启动命令消费完成时 async 节点可能仍在
 * 执行，启动时刻的 processActive 判定会竞态落后，业务实例行停留 RUNNING 而引擎侧已
 * 终态。本对账按"引擎实际状态是唯一真相"收敛：RUNNING 且引擎已非活跃（无待办/已结束）
 * 的业务行收敛为 APPROVED（与审批完成路径同一语义）；引擎仍活跃的行不动。</p>
 */
@Component
public class BpmInstanceStateSyncJob {

    private static final Logger log = LoggerFactory.getLogger(BpmInstanceStateSyncJob.class);

    /** 单轮最多核对条数（有界）。 */
    private static final int MAX_BATCH = 200;

    /** 竞态保护：仅核对落库超过该分钟数仍 RUNNING 的行（刚启动的行留给启动时刻判定）。 */
    private static final int QUIET_MINUTES = 1;

    private final BpmInstanceService instanceService;
    private final ObjectProvider<BpmTaskFacade> taskFacadeProvider;

    public BpmInstanceStateSyncJob(BpmInstanceService instanceService,
                                   ObjectProvider<BpmTaskFacade> taskFacadeProvider) {
        this.instanceService = instanceService;
        this.taskFacadeProvider = taskFacadeProvider;
    }

    @Scheduled(fixedDelayString = "${sw.bpm.instance.state-sync-interval-millis:60000}",
            initialDelayString = "${sw.bpm.instance.state-sync-initial-delay-millis:20000}")
    public void sweep() {
        try {
            int synced = sweepOnce();
            if (synced > 0) {
                log.info("轻流程实例状态对账: synced={}", synced);
            }
        } catch (Exception e) {
            log.error("轻流程实例状态对账异常", e);
        }
    }

    /** 单轮对账（对账线程无登录态：跨租户扫描，行内 tenant_id 自承载）。 */
    public int sweepOnce() {
        BpmTaskFacade facade = taskFacadeProvider.getIfAvailable();
        if (facade == null) {
            return 0;
        }
        // 对账线程无登录态：与命令对账同口径挂起租户过滤（行内 tenant_id 自承载）
        List<BpmInstance> candidates;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            candidates = instanceService.list(
                    Wrappers.<BpmInstance>lambdaQuery()
                            .eq(BpmInstance::getStatus, InstanceStatusEnum.RUNNING.getCode())
                            .lt(BpmInstance::getUpdateTime, LocalDateTime.now().minusMinutes(QUIET_MINUTES))
                            .last("LIMIT " + MAX_BATCH));
        }
        int synced = 0;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            for (BpmInstance instance : candidates) {
                Boolean active = facade.isProcessActive(instance.getProcessInstanceId()).orElse(true);
                if (!active) {
                    instance.setStatus(InstanceStatusEnum.APPROVED.getCode());
                    instanceService.updateById(instance);
                    synced++;
                }
            }
        }
        return synced;
    }
}
