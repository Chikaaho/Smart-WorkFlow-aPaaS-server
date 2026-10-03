package com.sw.ck.bpm.process.queue;

import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.BpmResourceUsage;
import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.bpm.process.service.ResourceFactView;
import com.sw.ck.bpm.process.service.ResourceReleaseService;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源保障对账任务（P62 资源保障）。
 * <p>
 * 两类收敛，均为「据事实收敛」而非重算业务：
 * <ol>
 *   <li><b>轻流程目标释放</b>：生产轻流程命令已完成（启动 SUCCEEDED）但完成点=
 *       TARGET_ACTION_DONE 且未释放的占用，按引擎队列事实释放——实例无未完成
 *       异步 job 且无死信 job 即目标链已终结（动作已落库/拒绝/死信），释放 1 单位；
 *       实例仍有 job 时保持占用（仅因启动命令 SUCCEEDED 释放即违反合同）。</li>
 *   <li><b>计数漂移修复</b>：占用计数（准入加速器）与持久事实（命令/批次项/引擎队列）
 *       的差值按乐观 CAS 修复——并发受理使读取值失效时放弃本轮，不覆盖新鲜占用；
 *       重启/崩溃后占用从持久事实恢复，不双计、不丢账。</li>
 * </ol>
 * 调度线程无登录态：跨租户扫描挂起租户过滤（与命令对账同口径）。
 * </p>
 */
@Component
public class ResourceAssuranceReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ResourceAssuranceReconcileJob.class);

    private static final int LIGHT_RELEASE_LIMIT = 500;
    private static final String FLOW_START_KEY_PREFIX = "FLOW_START:";

    private final com.sw.ck.bpm.process.service.BpmCommandService commandService;
    private final ResourceReleaseService releaseService;
    private final BpmResourceUsageMapper usageMapper;
    private final ResourceFactView factView;

    public ResourceAssuranceReconcileJob(
            com.sw.ck.bpm.process.service.BpmCommandService commandService,
            ResourceReleaseService releaseService,
            BpmResourceUsageMapper usageMapper,
            ResourceFactView factView) {
        this.commandService = commandService;
        this.releaseService = releaseService;
        this.usageMapper = usageMapper;
        this.factView = factView;
    }

    /** 对账结果（运维画像/测试回读用）。 */
    public record ReconcileOutcome(int lightTargetsReleased, int countersRepaired) {
    }

    @Scheduled(fixedDelayString = "${sw.bpm.resource.reconcile-interval-millis:60000}",
            initialDelayString = "${sw.bpm.resource.reconcile-initial-delay-millis:30000}")
    public void sweep() {
        try {
            ReconcileOutcome outcome = reconcileOnce();
            if (outcome.lightTargetsReleased() > 0 || outcome.countersRepaired() > 0) {
                log.info("资源保障对账: lightTargetsReleased={}, countersRepaired={}",
                        outcome.lightTargetsReleased(), outcome.countersRepaired());
            }
        } catch (Exception e) {
            log.error("资源保障对账异常", e);
        }
    }

    /** 单轮对账（公开供测试与画像直调）。 */
    public ReconcileOutcome reconcileOnce() {
        int released = releaseSettledLightProcessTargets();
        int repaired = repairCounterDrift();
        return new ReconcileOutcome(released, repaired);
    }

    // ==================== 轻流程目标释放 ====================

    private int releaseSettledLightProcessTargets() {
        List<BpmCommand> candidates;
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            candidates = commandService.lambdaQuery()
                    .eq(BpmCommand::getStatus, "COMPLETED")
                    .eq(BpmCommand::getResourceClass, "PROD")
                    .eq(BpmCommand::getCompletionPoint,
                            ResourceReleaseService.COMPLETION_POINT_TARGET_DONE)
                    .isNull(BpmCommand::getResourceReleasedAt)
                    .last("LIMIT " + LIGHT_RELEASE_LIMIT)
                    .list();
        }
        int released = 0;
        for (BpmCommand command : candidates) {
            if (!factView.engineTargetChainActive(recordIdOf(command))) {
                if (releaseService.releaseLightProcessTarget(command)) {
                    released++;
                }
            }
        }
        return released;
    }

    private String recordIdOf(BpmCommand command) {
        String key = command.getCommandKey();
        return key != null && key.startsWith(FLOW_START_KEY_PREFIX)
                ? key.substring(FLOW_START_KEY_PREFIX.length()) : null;
    }

    private int repairCounterDrift() {
        Map<String, Long> factBySegment = new HashMap<>();
        Map<String, Long> factByTenant = new HashMap<>();
        factView.accumulate(factBySegment, factByTenant);
        // TOTAL=Σsegments（与准入「段占位+总量占位」同构）；缺行修复会把 TOTAL 误判为 0
        factBySegment.put("TOTAL", factBySegment.values().stream().mapToLong(Long::longValue).sum());
        int repaired = 0;
        repaired += repairRows("GLOBAL", 0, factBySegment);
        for (Map.Entry<String, Long> entry : factByTenant.entrySet()) {
            long tenantId = Long.parseLong(entry.getKey().substring(entry.getKey().indexOf('|') + 1));
            repaired += repairRows("TENANT", tenantId, Map.of("TOTAL", entry.getValue()));
        }
        return repaired;
    }

    private int repairRows(String scope, long scopeKey, Map<String, Long> facts) {
        List<BpmResourceUsage> rows = usageMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<BpmResourceUsage>lambdaQuery()
                        .eq(BpmResourceUsage::getScope, scope)
                        .eq(BpmResourceUsage::getScopeKey, scopeKey));
        int repaired = 0;
        for (BpmResourceUsage row : rows) {
            long fact = facts.getOrDefault(row.getSegment(), 0L);
            long expected = row.getOutstanding() == null ? 0 : row.getOutstanding();
            if (fact != expected
                    && usageMapper.casRepair(scope, scopeKey, row.getSegment(), fact, expected) == 1) {
                log.warn("资源占用计数漂移已按事实修复: scope={}, key={}, segment={}, {} -> {}",
                        scope, scopeKey, row.getSegment(), expected, fact);
                repaired++;
            }
        }
        return repaired;
    }

}
