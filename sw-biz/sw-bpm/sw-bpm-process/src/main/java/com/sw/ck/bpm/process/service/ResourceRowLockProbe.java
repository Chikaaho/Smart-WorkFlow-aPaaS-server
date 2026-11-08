package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.common.jdbc.DatabaseVendor;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 资源段空闲探测的方言实现。
 * <p>
 * 支持 {@code SKIP LOCKED} 的数据库保持原语义（不等待、不污染事务）；SQL Server 用
 * {@code READPAST} 表提示；MySQL 5.7/达梦用带语句超时的 {@code FOR UPDATE}，
 * 超时视为“段忙”返回 null，不无限阻塞。行被并发事务持有时返回 null，调用方放弃借用。
 * </p>
 */
@Component
public class ResourceRowLockProbe {

    private static final Logger log = LoggerFactory.getLogger(ResourceRowLockProbe.class);
    private static final int PROBE_TIMEOUT_SECONDS = 1;

    private final BpmResourceUsageMapper usageMapper;
    private final JdbcTemplate jdbcTemplate;

    public ResourceRowLockProbe(BpmResourceUsageMapper usageMapper, JdbcTemplate jdbcTemplate) {
        this.usageMapper = usageMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long probe(String scope, long scopeKey, String segment) {
        DatabaseVendor vendor = JdbcVendorSupport.vendor(jdbcTemplate);
        if (vendor.supportsSkipLocked()) {
            return usageMapper.probeIdleRow(scope, scopeKey, segment);
        }
        if (vendor == DatabaseVendor.SQL_SERVER) {
            return queryOne("SELECT id FROM sw_bpm_resource_usage WITH (UPDLOCK, ROWLOCK, READPAST) "
                    + "WHERE scope = ? AND scope_key = ? AND segment = ?", scope, scopeKey, segment);
        }
        if (vendor == DatabaseVendor.MYSQL) {
            return probeMySql57(scope, scopeKey, segment);
        }
        if (vendor == DatabaseVendor.DM) {
            return probeWithTimeout(scope, scopeKey, segment);
        }
        throw new IllegalStateException("未实现的资源行锁探测方言: " + vendor.displayName());
    }

    private Long probeMySql57(String scope, long scopeKey, String segment) {
        Integer previous = null;
        try {
            previous = jdbcTemplate.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
            jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
            return probeWithTimeout(scope, scopeKey, segment);
        } catch (DataAccessException e) {
            log.debug("MySQL 5.7 资源段探测失败，按段忙处理: segment={}, cause={}", segment, e.getMessage());
            return null;
        } finally {
            if (previous != null) {
                try {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = " + previous);
                } catch (DataAccessException e) {
                    log.warn("恢复 MySQL innodb_lock_wait_timeout 失败: {}", e.getMessage());
                }
            }
        }
    }

    private Long probeWithTimeout(String scope, long scopeKey, String segment) {
        try {
            return jdbcTemplate.query(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id FROM sw_bpm_resource_usage "
                                + "WHERE scope = ? AND scope_key = ? AND segment = ? FOR UPDATE");
                ps.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
                ps.setString(1, scope);
                ps.setLong(2, scopeKey);
                ps.setString(3, segment);
                return ps;
            }, rs -> rs.next() ? rs.getLong(1) : null);
        } catch (DataAccessException e) {
            // 锁等待超时等同段忙：放弃借用，调用方继续下一个段
            log.debug("资源段探测超时，按段忙处理: scope={}, segment={}, cause={}",
                    scope, segment, e.getMessage());
            return null;
        }
    }

    private Long queryOne(String sql, Object... args) {
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, args);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
