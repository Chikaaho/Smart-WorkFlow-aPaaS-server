package com.sw.ck.system.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计日志保留清理（1.0.0 F3-6）：每天定时删除超过保留期的审计记录。
 * <p>
 * 保留天数默认 90，可经 {@code sw.audit-log.retention-days} 配置；清理时间经
 * {@code sw.audit-log.cleanup-cron} 配置（默认每日 03:30）。删除为物理删除、分批执行，
 * 失败只记 warn 等待下一轮，不影响业务。
 * </p>
 */
@Component
public class AuditLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuditLogCleanupJob.class);
    private static final int BATCH = 1000;

    private final AuditLogService auditLogService;
    private final int retentionDays;

    public AuditLogCleanupJob(AuditLogService auditLogService,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${sw.audit-log.retention-days:90}") int retentionDays) {
        this.auditLogService = auditLogService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${sw.audit-log.cleanup-cron:0 30 3 * * ?}")
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = auditLogService.purgeBefore(cutoff, BATCH);
        if (deleted > 0) {
            log.info("审计日志清理完成: retentionDays={} cutoff={} deleted={}",
                    retentionDays, cutoff, deleted);
        }
    }
}
