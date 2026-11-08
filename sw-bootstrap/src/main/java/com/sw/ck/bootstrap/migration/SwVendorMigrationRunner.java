package com.sw.ck.bootstrap.migration;

import com.sw.ck.common.jdbc.DatabaseVendor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 达梦/KingbaseES/openGauss 的迁移执行器（Flyway 无对应数据库模块）。
 * <p>
 * 与 Flyway 行为对齐的关键点：版本化脚本只执行一次并校验 SHA-256；重复脚本在校验和
 * 变化时重跑；已有业务表但没有 {@code sw_schema_history} 时拒绝自动 baseline；
 * 单个历史行锁防止多实例同时迁移；校验和不一致直接拒绝启动。
 * </p>
 */
public final class SwVendorMigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(SwVendorMigrationRunner.class);
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9][0-9A-Za-z_.]*)__(.+)\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPEATABLE = Pattern.compile("^R__(.+)\\.sql$", Pattern.CASE_INSENSITIVE);
    private static final String HISTORY_TABLE = "sw_schema_history";
    private static final String LOCK_TABLE = "sw_schema_history_lock";
    private static final long LOCK_STALE_MILLIS = 10 * 60 * 1000L;

    private SwVendorMigrationRunner() {
    }

    public static void migrate(DataSource dataSource, DatabaseVendor vendor) {
        try (Connection connection = dataSource.getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            boolean historyExisted = tableExists(connection, HISTORY_TABLE);
            if (!historyExisted && businessTableExists(connection)) {
                throw new IllegalStateException("检测到已有业务表但没有 " + HISTORY_TABLE
                        + "，拒绝自动 baseline；请先人工确认迁移历史");
            }
            createHistoryTable(jdbc, connection);
            createLockTable(jdbc, connection);
            String owner = lockOwner();
            acquireLock(jdbc, owner);
            try {
                runScripts(jdbc, connection, vendor);
            } finally {
                releaseLock(jdbc, owner);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("数据库迁移失败: " + vendor.displayName(), e);
        }
    }

    private static void runScripts(JdbcTemplate jdbc, Connection connection, DatabaseVendor vendor) {
        List<Script> scripts = loadScripts(vendor);
        List<Script> versioned = scripts.stream().filter(s -> s.version != null).toList();
        List<Script> repeatable = scripts.stream().filter(s -> s.version == null).toList();
        for (Script script : versioned) {
            String recorded = recordedChecksum(jdbc, script.name);
            if (recorded != null) {
                if (!recorded.equals(script.checksum)) {
                    throw new IllegalStateException("迁移校验和不一致: " + script.name
                            + "，已记录 " + recorded + "，当前 " + script.checksum);
                }
                continue;
            }
            execute(jdbc, connection, script);
            record(jdbc, script, "SQL");
        }
        for (Script script : repeatable) {
            String recorded = recordedChecksum(jdbc, script.name);
            if (recorded != null && recorded.equals(script.checksum)) {
                continue;
            }
            execute(jdbc, connection, script);
            record(jdbc, script, "SQL");
        }
    }

    private static void execute(JdbcTemplate jdbc, Connection connection, Script script) {
        long start = System.currentTimeMillis();
        try {
            boolean autoCommit = connection.getAutoCommit();
            if (autoCommit) {
                connection.setAutoCommit(false);
            }
            ScriptUtils.executeSqlScript(connection, script.resource);
            connection.commit();
            script.executionMillis = (int) (System.currentTimeMillis() - start);
            log.info("Executed migration {} in {} ms", script.name, script.executionMillis);
        } catch (Exception e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // 保留原始异常
            }
            throw new IllegalStateException("迁移脚本执行失败: " + script.name, e);
        }
    }

    private static void record(JdbcTemplate jdbc, Script script, String type) {
        Integer maxRank = jdbc.queryForObject(
                "SELECT COALESCE(MAX(installed_rank), 0) FROM " + HISTORY_TABLE, Integer.class);
        int rank = (maxRank == null ? 0 : maxRank) + 1;
        jdbc.update("DELETE FROM " + HISTORY_TABLE + " WHERE script = ?", script.name);
        jdbc.update("INSERT INTO " + HISTORY_TABLE
                        + " (installed_rank, version, description, type, script, checksum, installed_by,"
                        + " execution_time, success) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)",
                rank, script.version, script.description, type, script.name, script.checksum,
                lockOwner(), script.executionMillis);
    }

    private static String recordedChecksum(JdbcTemplate jdbc, String script) {
        List<String> values = jdbc.queryForList(
                "SELECT checksum FROM " + HISTORY_TABLE + " WHERE script = ? AND success = 1",
                String.class, script);
        return values.isEmpty() ? null : values.get(0);
    }

    private static List<Script> loadScripts(DatabaseVendor vendor) {
        String vendorDir = vendor.name().toLowerCase(Locale.ROOT);
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Script> scripts = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(
                    "classpath*:db/migration/" + vendorDir + "/*.sql");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                Matcher vm = VERSIONED.matcher(filename);
                if (vm.matches()) {
                    scripts.add(new Script(vm.group(1), vm.group(2), filename,
                            sha256(resource), resource));
                    continue;
                }
                Matcher rm = REPEATABLE.matcher(filename);
                if (rm.matches()) {
                    scripts.add(new Script(null, rm.group(1), filename,
                            sha256(resource), resource));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + vendor.displayName() + " 迁移脚本失败", e);
        }
        scripts.sort(Comparator
                .comparing((Script s) -> s.version == null)
                .thenComparing(s -> s.version == null ? s.name : versionKey(s.version)));
        return scripts;
    }

    private static String versionKey(String version) {
        String[] parts = version.split("[._]");
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            try {
                key.append(String.format("%08d", Integer.parseInt(part)));
            } catch (NumberFormatException e) {
                key.append(part);
            }
            key.append('.');
        }
        return key.toString();
    }

    private static String sha256(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("计算迁移校验和失败: " + resource, e);
        }
    }

    private static void createHistoryTable(JdbcTemplate jdbc, Connection connection) {
        if (tableExists(connection, HISTORY_TABLE)) {
            return;
        }
        try {
            jdbc.execute("CREATE TABLE " + HISTORY_TABLE + " ("
                + "installed_rank INTEGER NOT NULL PRIMARY KEY,"
                + "version VARCHAR(50),"
                + "description VARCHAR(200) NOT NULL,"
                + "type VARCHAR(20) NOT NULL,"
                + "script VARCHAR(1000) NOT NULL,"
                + "checksum VARCHAR(64),"
                + "installed_by VARCHAR(100) NOT NULL,"
                + "installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "execution_time INTEGER NOT NULL,"
                + "success SMALLINT NOT NULL)");
        } catch (RuntimeException e) {
            if (!tableExists(connection, HISTORY_TABLE)) {
                throw e;
            }
        }
    }

    private static void createLockTable(JdbcTemplate jdbc, Connection connection) {
        if (tableExists(connection, LOCK_TABLE)) {
            return;
        }
        try {
            jdbc.execute("CREATE TABLE " + LOCK_TABLE + " ("
                    + "id INTEGER NOT NULL PRIMARY KEY,"
                    + "locked_by VARCHAR(200),"
                    + "locked_at TIMESTAMP)");
        } catch (RuntimeException e) {
            if (!tableExists(connection, LOCK_TABLE)) {
                throw e;
            }
        }
        try {
            jdbc.update("INSERT INTO " + LOCK_TABLE + " (id, locked_by, locked_at) VALUES (1, NULL, NULL)");
        } catch (RuntimeException e) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM " + LOCK_TABLE + " WHERE id = 1", Integer.class) == 0) {
                throw e;
            }
        }
    }

    private static void acquireLock(JdbcTemplate jdbc, String owner) {
        long staleBefore = System.currentTimeMillis() - LOCK_STALE_MILLIS;
        int updated = jdbc.update("UPDATE " + LOCK_TABLE
                        + " SET locked_by = ?, locked_at = CURRENT_TIMESTAMP"
                        + " WHERE id = 1 AND (locked_by IS NULL OR locked_at < ?)",
                owner, new java.sql.Timestamp(staleBefore));
        if (updated != 1) {
            throw new IllegalStateException("另一个实例正在执行迁移，未获得 " + LOCK_TABLE + " 行锁");
        }
    }

    private static void releaseLock(JdbcTemplate jdbc, String owner) {
        try {
            jdbc.update("UPDATE " + LOCK_TABLE + " SET locked_by = NULL, locked_at = NULL"
                    + " WHERE id = 1 AND locked_by = ?", owner);
        } catch (RuntimeException e) {
            log.warn("释放迁移锁失败，请人工检查 {}: {}", LOCK_TABLE, e.getMessage());
        }
    }

    private static boolean businessTableExists(Connection connection) {
        return tableExists(connection, "sys_user")
                || tableExists(connection, "sw_form_config")
                || tableExists(connection, "sw_bpm_instance");
    }

    private static boolean tableExists(Connection connection, String table) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT),
                    table.toLowerCase(Locale.ROOT)}) {
                try (ResultSet rs = meta.getTables(null, null, candidate, new String[]{"TABLE"})) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            throw new IllegalStateException("检查表是否存在失败: " + table, e);
        }
    }

    private static String lockOwner() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "unknown-" + ProcessHandle.current().pid();
        }
    }

    private static final class Script {
        private final String version;
        private final String description;
        private final String name;
        private final String checksum;
        private final Resource resource;
        private int executionMillis;

        private Script(String version, String description, String name,
                       String checksum, Resource resource) {
            this.version = version;
            this.description = description;
            this.name = name;
            this.checksum = checksum;
            this.resource = resource;
        }
    }
}
