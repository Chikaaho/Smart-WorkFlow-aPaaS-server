package com.sw.ck.bootstrap.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.3.2 库（迁移终点 V0.2.9）升级到 1.0.0（终点 V1.0.0）的 H2 / PostgreSQL 真实升级验证。
 * <p>
 * 0.3.2 的迁移集合 = 当前资源去掉 0.4.0 新增的 {@code V0.4.0__knowledge_vector_persistence.sql}、
 * {@code R__0.4.0_rename_bpm_opinion_comment.sql} 与 1.0.0 新增的
 * {@code V1.0.0__audit_log_and_must_change_password.sql} 后的 28 条（9 个 versioned + 19 个 R__），
 * 与 develop 分支 0.3.2 终点逐字节一致。测试先把这 28 条复制到临时目录并迁移出 0.3.2 库，
 * 写入一条使用旧列名 {@code comment} 的审批意见草稿，再用当前 classpath 迁移集合升级；
 * 断言只新增 3 条迁移、旧脚本校验和不变、{@code comment} 改名后数据仍在。
 * </p>
 * <p>
 * 使用 run 期复制而不是把旧脚本复制进测试资源，避免测试夹具与生产迁移出现两份真相。
 * H2 与 PG 各跑一次，PG 用 zonky embedded-postgres 启动真实二进制，不依赖本机预置库。
 * </p>
 */
@DisplayName("0.3.2 库升级到 V1.0.0：数据保留与旧迁移校验和不变")
class FlywayUpgradeFrom032Test {

    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    /** 与 application.yml 一致；{vendor} 由本测试显式替换（flyway-core 不识别占位符）。 */
    private static final String[] APP_LOCATIONS = {
            "classpath:db/migration/{vendor}",
            "classpath:db/migration/bpm/{vendor}",
            "classpath:db/migration/notify/{vendor}",
            "classpath:db/migration/form/{vendor}",
            "classpath:db/migration/storage/{vendor}",
            "classpath:db/migration/job/{vendor}",
            "classpath:db/migration/agent/{vendor}",
            "classpath:db/migration/iot/{vendor}",
            "classpath:db/migration/openapi/{vendor}",
            "classpath:db/migration/system/{vendor}"
    };

    private static final int BASELINE_MIGRATIONS = 28;
    private static final int UPGRADE_MIGRATIONS = 3;

    @Test
    @DisplayName("H2：0.3.2 → V1.0.0 保留 opinion_comment 数据，旧迁移校验和不变")
    void h2UpgradeKeepsOpinionData(@TempDir Path tempDir) throws Exception {
        String url = "jdbc:h2:mem:upgrade032_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        verifyUpgrade("h2", url, "sa", "", tempDir);
    }

    @Test
    @DisplayName("PostgreSQL：0.3.2 → V1.0.0 保留 opinion_comment 数据，旧迁移校验和不变")
    void postgresUpgradeKeepsOpinionData(@TempDir Path tempDir) throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            verifyUpgrade("postgresql", pg.getJdbcUrl(USER, "postgres"), USER, PASSWORD, tempDir);
        }
    }

    private static void verifyUpgrade(String vendor, String url, String user, String password,
                                      Path tempDir) throws Exception {
        Path baselineDir = Files.createDirectory(tempDir.resolve("baseline-" + vendor));
        int copied = copyBaselineMigrations(vendor, baselineDir);
        assertEquals(BASELINE_MIGRATIONS, copied, "0.3.2 迁移文件数应为 28");

        Flyway baselineFlyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("filesystem:" + baselineDir.toAbsolutePath().toString().replace('\\', '/'))
                .load();
        MigrateResult baseline = baselineFlyway.migrate();
        assertTrue(baseline.success, "0.3.2 基线迁移应成功");
        assertEquals(BASELINE_MIGRATIONS, baseline.migrationsExecuted,
                "0.3.2 终点应执行 28 条（9 个 versioned + 19 个 R__）");

        Map<String, Integer> checksumsBefore;
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals("0.2.9", currentVersion(baselineFlyway), "0.3.2 终点版本应为 V0.2.9");
            assertTrue(columnExists(connection, "SW_BPM_TASK_OPINION_DRAFT", "COMMENT"),
                    "0.3.2 库应存在旧列 comment");
            assertFalse(columnExists(connection, "SW_BPM_TASK_OPINION_DRAFT", "OPINION_COMMENT"),
                    "0.3.2 库不应存在新列 opinion_comment");
            checksumsBefore = migrationChecksums(baselineFlyway);
            assertEquals(BASELINE_MIGRATIONS, checksumsBefore.size(), "基线校验和条数应为 28");
        }

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sw_bpm_task_opinion_draft "
                    + "(id, task_id, process_instance_id, assignee_id, comment) "
                    + "VALUES (9001, 'task-upgrade-1', 'pi-upgrade-1', 42, '保留意见')");
        }

        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", vendor))
                .toArray(String[]::new);
        Flyway upgradeFlyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations)
                .load();
        MigrateResult upgrade = upgradeFlyway.migrate();
        assertTrue(upgrade.success, "1.0.0 升级迁移应成功");
        assertEquals(UPGRADE_MIGRATIONS, upgrade.migrationsExecuted,
                "0.3.2 → 1.0.0 应只新增 V0.4.0、R__0.4.0 与 V1.0.0 三条迁移");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals("1.0.0", currentVersion(upgradeFlyway), "升级终点版本应为 V1.0.0");
            assertFalse(columnExists(connection, "SW_BPM_TASK_OPINION_DRAFT", "COMMENT"),
                    "升级后不应保留旧列 comment");
            assertTrue(columnExists(connection, "SW_BPM_TASK_OPINION_DRAFT", "OPINION_COMMENT"),
                    "升级后应存在新列 opinion_comment");
            assertTrue(tableExists(connection, "SW_KNOWLEDGE_VECTOR"),
                    "升级后应创建向量持久化表 sw_knowledge_vector");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT opinion_comment FROM sw_bpm_task_opinion_draft WHERE id = 9001");
                 ResultSet rs = statement.executeQuery()) {
                assertTrue(rs.next(), "升级后审批意见草稿应仍存在");
                assertEquals("保留意见", rs.getString(1), "comment 改名后数据应保留");
            }

            Map<String, Integer> checksumsAfter = migrationChecksums(upgradeFlyway);
            for (Map.Entry<String, Integer> entry : checksumsBefore.entrySet()) {
                assertEquals(entry.getValue(), checksumsAfter.get(entry.getKey()),
                        "旧迁移校验和应不变: " + entry.getKey());
            }
        }
    }

    /**
     * 把 classpath 中的 0.3.2 迁移集合（去掉 0.4.0/1.0.0 新增的三个文件）复制到临时目录。
     */
    private static int copyBaselineMigrations(String vendor, Path target) throws Exception {
        URL resource = FlywayUpgradeFrom032Test.class.getClassLoader()
                .getResource("db/migration/" + vendor);
        assertNotNull(resource, "迁移目录应存在于 classpath: db/migration/" + vendor);
        Path source = Paths.get(resource.toURI());
        int copied = 0;
        try (Stream<Path> files = Files.list(source)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".sql")
                        || name.startsWith("V0.4.0__")
                        || name.startsWith("R__0.4.0_")
                        || name.startsWith("V1.0.0__")) {
                    continue;
                }
                Files.copy(file, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        return copied;
    }

    private static String currentVersion(Flyway flyway) {
        var current = flyway.info().current();
        return current == null || current.getVersion() == null ? null : current.getVersion().getVersion();
    }

    private static Map<String, Integer> migrationChecksums(Flyway flyway) {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        for (var info : flyway.info().applied()) {
            checksums.put(info.getScript(), info.getChecksum());
        }
        return checksums;
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE LOWER(table_name) = LOWER(?) AND LOWER(column_name) = LOWER(?)")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE LOWER(table_name) = LOWER(?)")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }
}
