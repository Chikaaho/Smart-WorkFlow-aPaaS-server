package com.sw.ck.bootstrap.compat;

import com.sw.ck.common.jdbc.DatabaseVendor;
import com.sw.ck.common.jdbc.JdbcVendorSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 迁移前数据库兼容性检查。
 * <p>
 * 多数据库适配的前提条件不满足时拒绝启动，避免表先按错误的字符集/排序规则建好、
 * 之后再报错。检查由 {@code FlywayConfiguration} 在调用任何迁移之前执行，
 * 不再作为 {@code ApplicationRunner} 在上下文启动完成后才跑：
 * <ul>
 *   <li>MySQL/MariaDB：utf8mb4 + utf8mb4_bin；MySQL 5.7 另查 DYNAMIC 行格式与大前缀。</li>
 *   <li>SQL Server：数据库排序规则区分大小写（_CS_ 或 _BIN）。</li>
 *   <li>Oracle：NLS_CHARACTERSET=AL32UTF8（或 UTF8）。</li>
 *   <li>PostgreSQL/Kingbase/openGauss：server_encoding=UTF8。</li>
 *   <li>达梦：只记录 CASE_SENSITIVE、LENGTH_IN_CHAR、COMPATIBLE_MODE，不阻断。</li>
 * </ul>
 */
@Component
public class DatabaseCompatibilityGuard {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCompatibilityGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCompatibilityGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在迁移策略执行任何 Flyway/SwVendorMigrationRunner 之前调用；失败则抛出，迁移零执行。
     */
    public void check() {
        DatabaseVendor vendor = JdbcVendorSupport.vendor(jdbcTemplate);
        switch (vendor) {
            case H2 -> log.info("Database compatibility guard skipped for H2 test/dev database");
            case MYSQL, MARIADB -> checkMySql(vendor);
            case SQL_SERVER -> checkSqlServer();
            case ORACLE -> checkOracle();
            case POSTGRESQL, KINGBASE, OPENGAUSS -> checkPostgresFamily(vendor);
            case DM -> logDmSettings();
        }
    }

    private void checkMySql(DatabaseVendor vendor) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT @@character_set_database AS charset, @@collation_database AS collation");
        String charset = String.valueOf(row.get("charset")).toLowerCase(Locale.ROOT);
        String collation = String.valueOf(row.get("collation")).toLowerCase(Locale.ROOT);
        if (!"utf8mb4".equals(charset)) {
            throw new IllegalStateException("MySQL/MariaDB 数据库字符集必须是 utf8mb4，当前为 " + charset);
        }
        if (!"utf8mb4_bin".equals(collation)) {
            throw new IllegalStateException("MySQL/MariaDB 数据库排序规则必须是 utf8mb4_bin，当前为 " + collation);
        }
        if (vendor == DatabaseVendor.MYSQL) {
            String rowFormat = String.valueOf(jdbcTemplate.queryForObject(
                    "SELECT @@innodb_default_row_format", String.class)).toLowerCase(Locale.ROOT);
            if (!"dynamic".equals(rowFormat)) {
                throw new IllegalStateException("MySQL innodb_default_row_format 必须是 DYNAMIC，当前为 " + rowFormat);
            }
            String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
            if (version != null && version.startsWith("5.7")) {
                Integer largePrefix;
                try {
                    largePrefix = jdbcTemplate.queryForObject("SELECT @@innodb_large_prefix", Integer.class);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("MySQL 5.7 无法读取 innodb_large_prefix，拒绝启动", e);
                }
                if (largePrefix == null || largePrefix != 1) {
                    throw new IllegalStateException("MySQL 5.7 innodb_large_prefix 必须为 ON，当前为 " + largePrefix);
                }
            }
        }
        log.info("Database compatibility check passed: vendor={}, charset={}, collation={}",
                vendor.displayName(), charset, collation);
    }

    private void checkSqlServer() {
        String collation = jdbcTemplate.queryForObject(
                "SELECT CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation'))", String.class);
        String upper = collation == null ? "" : collation.toUpperCase(Locale.ROOT);
        if (!upper.contains("_CS_") && !upper.contains("_BIN")) {
            throw new IllegalStateException("SQL Server 数据库排序规则必须区分大小写（_CS_ 或 _BIN），当前为 " + collation);
        }
        log.info("Database compatibility check passed: vendor=SQL Server, collation={}", collation);
    }

    private void checkOracle() {
        String charset = jdbcTemplate.queryForObject(
                "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'", String.class);
        String upper = charset == null ? "" : charset.toUpperCase(Locale.ROOT);
        if (!"AL32UTF8".equals(upper) && !"UTF8".equals(upper)) {
            throw new IllegalStateException("Oracle 数据库字符集必须是 AL32UTF8/UTF8，当前为 " + charset);
        }
        log.info("Database compatibility check passed: vendor=Oracle, charset={}", charset);
    }

    private void checkPostgresFamily(DatabaseVendor vendor) {
        String encoding = jdbcTemplate.queryForObject("SHOW server_encoding", String.class);
        if (encoding == null || !"UTF8".equalsIgnoreCase(encoding)) {
            throw new IllegalStateException(vendor.displayName() + " server_encoding 必须是 UTF8，当前为 " + encoding);
        }
        log.info("Database compatibility check passed: vendor={}, server_encoding={}",
                vendor.displayName(), encoding);
    }

    private void logDmSettings() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT PARA_NAME, PARA_VALUE FROM V$DM_INI WHERE PARA_NAME IN "
                            + "('CASE_SENSITIVE','LENGTH_IN_CHAR','COMPATIBLE_MODE')");
            for (Map<String, Object> row : rows) {
                log.info("DM setting {}={}", row.get("PARA_NAME"), row.get("PARA_VALUE"));
            }
        } catch (RuntimeException e) {
            log.warn("达梦初始化参数读取失败，请人工核对 CASE_SENSITIVE/LENGTH_IN_CHAR/COMPATIBLE_MODE: {}",
                    e.getMessage());
        }
    }
}
