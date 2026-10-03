package com.sw.ck.bpm.process.dto;

import com.sw.ck.bpm.process.entity.BpmResourcePolicy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 资源保障运维视图（P62 资源保障 RG04/RG05/RG06）。
 * <p>
 * 只读视图集合：运行画像（有效值+消费者+勾稽）、积压分层汇总（命令/引擎目标/批量项）、
 * 分页明细与拒绝审计。报表显式携带统计窗口说明、完成点口径与未完成数；
 * 汇总指标全部低基数（按状态/类别/段/租户聚合），身份明细经授权查询端点关联。
 * </p>
 */
public final class BpmResourceOpsViews {

    private BpmResourceOpsViews() {
    }

    /** 运行画像（RG04：有效池/线程/消费者/调度画像可回读）。 */
    public record RuntimeProfile(
            String generatedAt,
            boolean policyEnabled,
            BpmResourcePolicy activePolicy,
            List<String> enablementViolations,
            Map<String, Object> pool,
            Map<String, Object> asyncExecutor,
            Map<String, Object> dispatcher,
            Map<String, Object> consumers,
            Map<String, Long> usageCounters,
            Map<String, Long> factBySegment,
            Map<Long, Long> factByTenant,
            Map<String, Object> realtimeGuard,
            String reconciliationNote) {
    }

    /** 积压分层汇总（RG05：命令积压与引擎目标积压分层展示并可对账）。 */
    public record BacklogSummary(
            String generatedAt,
            String windowNote,
            String completionPointNote,
            long totalCommandsOpen,
            long incompleteUnits,
            List<Map<String, Object>> commandsByStatus,
            List<Map<String, Object>> commandsByClass,
            Map<String, Long> engineTargets,
            long batchPendingItems,
            Map<String, Long> usageCounters,
            Map<String, Long> factBySegment,
            boolean countersConsistentWithFacts) {
    }

    /** 命令分页明细（过滤：租户/类别/状态/通道/策略版本/时间段）。 */
    public record CommandPage(
            long page,
            long size,
            long total,
            List<Map<String, Object>> rows) {
    }

    /** 命令详情（含效果权威、拒绝审计、目标动作关联）。 */
    public record CommandDetail(
            Map<String, Object> command,
            Map<String, Object> effect,
            List<Map<String, Object>> rejectLogs,
            List<Object> targetInvocations) {
    }

    /** 拒绝审计分页。 */
    public record RejectPage(
            long page,
            long size,
            long total,
            List<Map<String, Object>> rows) {
    }

    /** 明细行时间字段（受理→领取→执行→终态全链可回读）。 */
    public record CommandTimeline(LocalDateTime createTime, LocalDateTime claimedAt,
                                  LocalDateTime finishedAt, LocalDateTime deadlineAt,
                                  LocalDateTime overdueAt, LocalDateTime releasedAt) {
    }
}
