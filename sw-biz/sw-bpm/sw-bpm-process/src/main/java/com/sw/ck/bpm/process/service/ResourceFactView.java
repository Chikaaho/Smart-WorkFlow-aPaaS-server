package com.sw.ck.bpm.process.service;

import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 资源占用事实视图（P62 资源保障；权威口径唯一来源）。
 * <p>
 * 占用事实=持久行的确定性聚合（不依赖计数行、不依赖进程内存）：
 * <ul>
 *   <li>普通命令：status IN (PENDING, PROCESSING) 且未释放 → resource_units；</li>
 *   <li>生产轻流程目标未决：status=COMPLETED 且完成点=TARGET_ACTION_DONE 且未释放
 *       → 每命令 1 单位（目标在引擎队列/死信即占用成立）；</li>
 *   <li>批量命令：status IN (PENDING, PROCESSING) 且未释放 → 非终态项数（按项计费）。</li>
 * </ul>
 * 供资源对账（计数修复）与运维画像（勾稽展示）共用；查询含跨租户扫描，挂起租户过滤。
 * </p>
 */
@Component
public class ResourceFactView {

    static final String TARGET_DONE = "TARGET_ACTION_DONE";

    private final JdbcTemplate jdbcTemplate;

    public ResourceFactView(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按容量段聚合的占用事实（segment → units；不含 TOTAL，TOTAL 为三段之和）。 */
    public Map<String, Long> factBySegment() {
        Map<String, Long> bySegment = new HashMap<>();
        Map<String, Long> ignored = new HashMap<>();
        try (TenantLineSuspension.Suspended suspended = TenantLineSuspension.suspended()) {
            accumulate(bySegment, ignored);
        }
        return bySegment;
    }

    /** 按租户聚合的占用事实（tenantId → units；TENANT 计数行口径）。 */
    public Map<Long, Long> factByTenant() {
        Map<String, Long> ignored = new HashMap<>();
        Map<String, Long> byTenant = new HashMap<>();
        try (TenantLineSuspension.Suspended suspended = TenantLineSuspension.suspended()) {
            accumulate(ignored, byTenant);
        }
        Map<Long, Long> result = new HashMap<>();
        byTenant.forEach((key, value) -> result.put(Long.parseLong(key.substring(key.indexOf('|') + 1)), value));
        return result;
    }

    /** 同时输出段维度与租户维度（对账单次扫描复用）。 */
    public void accumulate(Map<String, Long> bySegment, Map<String, Long> byTenant) {
        jdbcTemplate.query(
                "SELECT resource_segment, tenant_id, SUM(resource_units) AS units FROM sw_bpm_command "
                        + "WHERE resource_units IS NOT NULL AND resource_released_at IS NULL "
                        + "AND status IN ('PENDING','PROCESSING') "
                        + "AND (command_type IS NULL OR command_type <> 'BATCH_INVOKE') "
                        + "GROUP BY resource_segment, tenant_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> merge(rs, bySegment, byTenant));
        jdbcTemplate.query(
                "SELECT resource_segment, tenant_id, COUNT(*) AS units FROM sw_bpm_command "
                        + "WHERE resource_units IS NOT NULL AND resource_released_at IS NULL "
                        + "AND status = 'COMPLETED' AND resource_class = 'PROD' "
                        + "AND completion_point = '" + TARGET_DONE + "' "
                        + "GROUP BY resource_segment, tenant_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> merge(rs, bySegment, byTenant));
        jdbcTemplate.query(
                "SELECT c.resource_segment AS segment, c.tenant_id AS tenant_id, "
                        + "SUM(CASE WHEN i.status = 'PENDING' THEN 1 ELSE 0 END) AS units "
                        + "FROM sw_bpm_command c "
                        + "JOIN sw_bpm_command_batch b ON b.command_id = c.id "
                        + "JOIN sw_bpm_command_batch_item i ON i.batch_id = b.id "
                        + "WHERE c.resource_units IS NOT NULL AND c.resource_released_at IS NULL "
                        + "AND c.status IN ('PENDING','PROCESSING') "
                        + "GROUP BY c.resource_segment, c.tenant_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    if (rs.getLong("units") <= 0) {
                        return;
                    }
                    merge(rs, bySegment, byTenant);
                });
    }

    /** 轻流程目标链占用事实：实例仍有未完成异步 job 或死信 job → true。 */
    public boolean engineTargetChainActive(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return false;
        }
        Long activeJobs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM act_ru_job j "
                        + "JOIN act_ru_execution e ON e.proc_inst_id_ = j.process_instance_id_ "
                        + "WHERE e.business_key_ = ?", Long.class, recordId);
        Long deadLetters = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM act_ru_deadletter_job j "
                        + "JOIN act_ru_execution e ON e.proc_inst_id_ = j.process_instance_id_ "
                        + "WHERE e.business_key_ = ?", Long.class, recordId);
        return (activeJobs != null && activeJobs > 0) || (deadLetters != null && deadLetters > 0);
    }

    /** 引擎目标积压（分层展示：命令积压 vs 引擎异步 job）。 */
    public long enginePendingJobs() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM act_ru_job", Long.class);
        return count == null ? 0 : count;
    }

    /** 引擎死信 job（未终结目标，需运维关注）。 */
    public long engineDeadLetterJobs() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM act_ru_deadletter_job", Long.class);
        return count == null ? 0 : count;
    }

    private void merge(java.sql.ResultSet rs, Map<String, Long> bySegment, Map<String, Long> byTenant)
            throws java.sql.SQLException {
        String segment = rs.getString(1) == null ? "SHARED" : rs.getString(1);
        long tenantId = rs.getLong(2);
        long units = rs.getLong(3);
        if (units <= 0) {
            return;
        }
        bySegment.merge(segment, units, Long::sum);
        byTenant.merge("TENANT|" + tenantId, units, Long::sum);
    }
}
