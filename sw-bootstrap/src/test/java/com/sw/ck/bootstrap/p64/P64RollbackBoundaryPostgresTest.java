package com.sw.ck.bootstrap.p64;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A12 回退边界观察（真实 PostgreSQL），0.1.5 收敛后口径：
 * 以在役 0.1.5 种子库为基线，把收敛前的旧迁移集（legacy 0.1.x 全链 + 现行 R__，
 * 取自独立升级通道 {@code db/upgrade/0.1.5/legacy-0.1.x/} 的 classpath 真实资源）
 * 作为 Flyway location 对该库执行 validate——在役库已应用的 0.1.5 种子在旧集中
 * 不可解析，validate 必须失败（不得静默通过），即"不能把不能处理存量的旧代码直接切回"。
 * <p>
 * 对照一：旧迁移集对旧终点库（0.1.10）迁移 + validate 全通过——失败只源于收敛，
 * 不是旧集文件损坏。对照二：在役（完整新链）配置对同一 0.1.5 库 validate 成功。
 * </p>
 */
@DisplayName("A12 真实 PG 回退边界观察：legacy 0.1.x 旧迁移集对在役 0.1.5 库 validate 必须失败")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P64RollbackBoundaryPostgresTest {

    private static final String SERVER_URL =
            System.getenv().getOrDefault("P64_PG_URL", "jdbc:postgresql://127.0.0.1:5432/postgres");
    private static final String DB_NAME = "p64_rollback_check";
    private static final String LEGACY_DB_NAME = "p64_rollback_legacy";
    private static final String USER = "postgres";
    private static final String PASSWORD = "123456";

    private String serverUrl;
    private String dbUser;
    private String dbPassword;
    private String dbUrl;

    @Test
    @DisplayName("在役 0.1.5 库 × legacy 0.1.x 旧迁移集：validate 失败且指名 0.1.5；旧集对旧终点库全通过；新链 validate 成功")
    void rollbackToLegacyMigrationSetFailsValidateNotSilentlyPass() throws Exception {
        probeConnection();
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + DB_NAME);
        }

        // 1) 在役基线：新链迁移至最新（0.1.5 种子 + 本轮 V20261015190000）
        var result = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(P64AppendMigrationUpgradePostgresTest.appLocations()).load().migrate();
        assertTrue(result.success, "种子链迁移应成功");
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            assertEquals("20261015190000", currentVersion(conn), "在役基线应为最新迁移");
        }

        // 2) 旧代码等价迁移集：从 classpath 真实复制升级通道 legacy 0.1.x 全链（11 个版本化）
        //    + 现行 10 个 R__（收敛后 R__ 继续随主链，内容未变）
        Path legacyDir = Files.createTempDirectory("r015-legacy-migrations");
        PathMatchingResourceResolverBridge resolver = new PathMatchingResourceResolverBridge();
        int copied = resolver.copyLegacyMigrations(legacyDir);
        assertEquals(21, copied, "旧迁移集应含 11 个 legacy 版本化 + 10 个现行 R__，实际复制 " + copied + " 个文件");
        List<String> legacyNames;
        try (var list = Files.list(legacyDir)) {
            legacyNames = list.map(p -> p.getFileName().toString()).toList();
        }
        assertTrue(legacyNames.stream().anyMatch(n -> n.startsWith("V0.1.10")),
                "旧迁移集应含 legacy 链尾 0.1.10");
        assertTrue(legacyNames.stream().noneMatch(n -> n.equals("V0.1.5__baseline_seed.sql")),
                "旧迁移集不得包含 0.1.5 种子（等价回退代码）");

        // 3) 回退观察：旧迁移集对在役 0.1.5 库 validate → 必须失败并指名未解析的 0.1.5
        ValidateResult validation = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations("filesystem:" + legacyDir.toAbsolutePath())
                .ignoreMigrationPatterns("*:ignored").load().validateWithResult();
        assertFalse(validation.validationSuccessful,
                "旧迁移集对在役 0.1.5 库 validate 必须失败（不得把不能处理存量的旧代码直接切回）");
        List<String> unresolvedVersions = validation.invalidMigrations.stream()
                .map(migration -> String.valueOf(migration.version))
                .toList();
        System.out.println("[r015-rollback] unresolved=" + unresolvedVersions);
        assertTrue(unresolvedVersions.contains("0.1.5"),
                "validate 未解析迁移应含 0.1.5 种子，实际: " + unresolvedVersions);
        assertTrue(unresolvedVersions.contains("20261015190000"),
                "validate 未解析迁移应含本轮迁移，实际: " + unresolvedVersions);

        // 4) 对照一：旧迁移集对旧终点库（0.1.10）迁移 + validate 全通过——失败只源于收敛
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + LEGACY_DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + LEGACY_DB_NAME);
        }
        String legacyUrl = serverUrl.replace("/postgres", "/" + LEGACY_DB_NAME);
        var legacyMigrate = Flyway.configure().dataSource(legacyUrl, dbUser, dbPassword)
                .locations("filesystem:" + legacyDir.toAbsolutePath()).load().migrate();
        assertTrue(legacyMigrate.success, "旧迁移集对旧终点库应迁移成功");
        try (Connection conn = DriverManager.getConnection(legacyUrl, dbUser, dbPassword)) {
            assertEquals("0.1.10", currentVersion(conn), "旧终点库应停在 legacy 链尾 0.1.10");
        }
        Flyway.configure().dataSource(legacyUrl, dbUser, dbPassword)
                .locations("filesystem:" + legacyDir.toAbsolutePath())
                .ignoreMigrationPatterns("*:ignored").load().validate();

        // 5) 对照二：在役（完整新链）配置对同一 0.1.5 库 validate 成功——失败只源于回退集缺种子
        Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(P64AppendMigrationUpgradePostgresTest.appLocations())
                .ignoreMigrationPatterns("*:ignored").load().validate();
        Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(P64AppendMigrationUpgradePostgresTest.appLocations()).load().validate();
    }

    /** classpath 迁移资源复制桥（仅测试可见的清单复制逻辑）。 */
    private static final class PathMatchingResourceResolverBridge {
        private static final String LEGACY_DIR = "db/upgrade/0.1.5/legacy-0.1.x/postgresql";
        private static final String MAIN_DIR = "db/migration/postgresql";
        private static final String[] REPEATABLES = {
                "R__bf20261013_catalog_admin_menu.sql", "R__i6_notify_menu_reconciliation.sql",
                "R__p62_form_txn_action_menu.sql", "R__p62_iot_command_verify_menu.sql",
                "R__p62_resource_ops_menu.sql", "R__p62_txn_batch_menu.sql",
                "R__p63_iot_reservation_menu.sql", "R__p64_approval_form_menu.sql",
                "R__p64_position_delegate_menu.sql", "R__application_center_menu.sql"};

        int copyLegacyMigrations(Path targetDir) throws Exception {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            int copied = 0;
            Resource[] legacy = resolver.getResources("classpath*:" + LEGACY_DIR + "/*.sql");
            for (Resource resource : legacy) {
                String name = resource.getFilename();
                if (name == null) {
                    continue;
                }
                Files.writeString(targetDir.resolve(name), read(resource), StandardCharsets.UTF_8);
                copied++;
            }
            for (String r : REPEATABLES) {
                Resource resource = resolver.getResource("classpath:" + MAIN_DIR + "/" + r);
                assertTrue(resource.exists(), "现行可重复迁移缺失: " + r);
                Files.writeString(targetDir.resolve(r), read(resource), StandardCharsets.UTF_8);
                copied++;
            }
            return copied;
        }

        private String read(Resource resource) throws Exception {
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private void probeConnection() {
        String osUser = System.getProperty("user.name", "postgres");
        String[][] candidates = System.getenv("P64_PG_URL") == null || System.getenv("P64_PG_URL").isEmpty()
                ? new String[][]{
                        {SERVER_URL, USER, PASSWORD},
                        {"jdbc:postgresql://127.0.0.1:5432/postgres", osUser, ""}}
                : new String[][]{{SERVER_URL, USER, PASSWORD}};
        for (String[] candidate : candidates) {
            try (Connection probe = DriverManager.getConnection(candidate[0], candidate[1], candidate[2])) {
                if (probe.isValid(5)) {
                    serverUrl = candidate[0];
                    dbUser = candidate[1];
                    dbPassword = candidate[2];
                    dbUrl = serverUrl.replace("/postgres", "/" + DB_NAME);
                    return;
                }
            } catch (SQLException e) {
                // 尝试下一候选
            }
        }
        assumeTrue(false,
                "本机 PostgreSQL 不可用（已尝试 postgres/123456 与本机默认身份）：回退边界观察跳过，"
                        + "等强度由 ADR 回退契约与既有迁移锚承载");
    }

    private String currentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM flyway_schema_history "
                     + "WHERE success = TRUE AND version IS NOT NULL "
                     + "ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next(), "迁移历史不应为空");
            return rs.getString(1);
        }
    }
}
