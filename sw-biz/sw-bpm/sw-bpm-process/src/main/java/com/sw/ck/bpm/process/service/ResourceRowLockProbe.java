package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.common.jdbc.DatabaseVendor;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 资源段空闲探测的方言实现。
 * <p>
 * 支持 {@code SKIP LOCKED} 的数据库保持原语义（不等待、不污染事务）；SQL Server 用
 * {@code READPAST} 表提示；MySQL 5.7 无 {@code SKIP LOCKED}，用会话级
 * {@code innodb_lock_wait_timeout=1} 的 {@code FOR UPDATE}，1 秒是等待上限，超时视为
 * “段忙”返回 null，不无限阻塞；达梦用语句超时，B2 实测若支持 {@code SKIP LOCKED}
 * 再切换。行被并发事务持有时返回 null，调用方放弃借用。
 * </p>
 * <p>
 * MySQL 5.7 为什么必须保留行锁探测，而不是改成独立的原子 {@code UPDATE ... WHERE}：
 * 调用方在探测返回后、同一事务内执行 {@code incrementWithinCap}。探测持有的行锁保证
 * “段空闲”判断到条件占用之间不被其他事务插入；独立 UPDATE 既不持有该锁，也仍会等待
 * 被占用的行锁（MySQL 5.7 无 NOWAIT），反而失去原子性。会话变量在同一个
 * {@link ConnectionCallback} 内设置并还原，连接归还池子前已恢复。
 * </p>
 */
@Component
public class ResourceRowLockProbe {

    private static final Logger log = LoggerFactory.getLogger(ResourceRowLockProbe.class);
    private static final int PROBE_TIMEOUT_SECONDS = 1;
    /** MySQL 5.x 不支持 SKIP LOCKED；按 JDBC URL 缓存主版本判定。 */
    private static final Map<String, Boolean> MYSQL_57_CACHE = new ConcurrentHashMap<>();

    private final BpmResourceUsageMapper usageMapper;
    private final JdbcTemplate jdbcTemplate;

    public ResourceRowLockProbe(BpmResourceUsageMapper usageMapper, JdbcTemplate jdbcTemplate) {
        this.usageMapper = usageMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Long probe(String scope, long scopeKey, String segment) {
        DatabaseVendor vendor = JdbcVendorSupport.vendor(jdbcTemplate);
        // MySQL 5.7 没有 SKIP LOCKED，supportsSkipLocked() 只代表 8.0+；先按版本分流。
        if (vendor == DatabaseVendor.MYSQL && isMySql57()) {
            return probeMySql57(scope, scopeKey, segment);
        }
        if (vendor.supportsSkipLocked()) {
            return usageMapper.probeIdleRow(scope, scopeKey, segment);
        }
        if (vendor == DatabaseVendor.SQL_SERVER) {
            return queryOne("SELECT id FROM sw_bpm_resource_usage WITH (UPDLOCK, ROWLOCK, READPAST) "
                    + "WHERE scope = ? AND scope_key = ? AND segment = ?", scope, scopeKey, segment);
        }
        if (vendor == DatabaseVendor.DM) {
            return probeWithTimeout(scope, scopeKey, segment);
        }
        throw new IllegalStateException("未实现的资源行锁探测方言: " + vendor.displayName());
    }

    private boolean isMySql57() {
        var dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("JdbcTemplate 未绑定 DataSource，无法判断 MySQL 版本");
        }
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null) {
                return false;
            }
            Boolean cached = MYSQL_57_CACHE.get(url);
            if (cached != null) {
                return cached;
            }
            boolean mysql57 = connection.getMetaData().getDatabaseMajorVersion() == 5;
            MYSQL_57_CACHE.put(url, mysql57);
            return mysql57;
        } catch (SQLException e) {
            throw new IllegalStateException("判断 MySQL 版本失败", e);
        }
    }

    private Long probeMySql57(String scope, long scopeKey, String segment) {
        return jdbcTemplate.execute((ConnectionCallback<Long>) connection ->
                probeMySqlOnConnection(connection, scope, scopeKey, segment));
    }

    /**
     * 在同一个连接上设置、探测并还原会话变量；连接归还池子后超时值已恢复。
     */
    Long probeMySqlOnConnection(Connection connection, String scope, long scopeKey, String segment) {
        Integer previous = null;
        boolean changed = false;
        try {
            previous = queryInt(connection, "SELECT @@SESSION.innodb_lock_wait_timeout");
            if (previous == null) {
                return null;
            }
            execute(connection, "SET SESSION innodb_lock_wait_timeout = " + PROBE_TIMEOUT_SECONDS);
            changed = true;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM sw_bpm_resource_usage "
                            + "WHERE scope = ? AND scope_key = ? AND segment = ? FOR UPDATE")) {
                ps.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
                ps.setString(1, scope);
                ps.setLong(2, scopeKey);
                ps.setString(3, segment);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        } catch (SQLException e) {
            log.debug("MySQL 5.7 资源段探测失败，按段忙处理: segment={}, cause={}", segment, e.getMessage());
            return null;
        } finally {
            if (changed) {
                try {
                    execute(connection, "SET SESSION innodb_lock_wait_timeout = " + previous);
                } catch (SQLException e) {
                    log.warn("恢复 MySQL innodb_lock_wait_timeout 失败: {}", e.getMessage());
                }
            }
        }
    }

    private static Integer queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
