package com.sw.ck.bootstrap.p64;

import org.flywaydb.core.Flyway;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P64 阶段Ⅱ A12 回退边界观察（真实 PostgreSQL）：
 * 以在役 0.1.9 全链库为基线，把回退代码等价的旧迁移集（仅 ≤0.1.7 的资源）作为
 * Flyway location 对该库执行 validate——0.1.8/0.1.9 已应用而本地不可解析，
 * validate 必须失败（不得静默通过），即"不能把不能处理存量的旧代码直接切回"。
 * <p>
 * 旧迁移集经 classpath 真实资源复制生成（V0.1.0—V0.1.7 全部 SQL，含各子域目录），
 * 非手工快照；复制清单非空且数量可核。连接不可用按外部环境事实跳过。
 * </p>
 */
@DisplayName("P64 A12 真实 PG 回退边界观察：旧迁移集对在役 0.1.9 库 validate 必须失败")
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
    @DisplayName("在役 0.1.9 库 × 旧迁移集(≤0.1.7)：validate 失败且指名未解析的 0.1.8/0.1.9；新配置完整链 validate 成功")
    void rollbackToLegacyMigrationSetFailsValidateNotSilentlyPass() throws Exception {
        probeConnection();
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + DB_NAME);
        }

        // 1) 在役基线：全链迁移至 0.1.9
        var result = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(P64AppendMigrationUpgradePostgresTest.appLocations()).load().migrate();
        assertTrue(result.success, "全链迁移应成功");
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            assertEquals("0.1.9", currentVersion(conn), "在役基线应为 0.1.9");
        }

        // 2) 旧代码等价迁移集：从 classpath 真实复制全部 ≤0.1.7 的 SQL 到临时目录
        //    （8 个版本化 V0.1.0—V0.1.7 + 6 个阶段Ⅰ R__；R__p64_position_delegate_menu 属 0.1.9 期，排除）
        Path legacyDir = Files.createTempDirectory("p64-legacy-migrations");
        PathMatchingResourceResolverBridge resolver = new PathMatchingResourceResolverBridge();
        int copied = resolver.copyLegacyMigrations(legacyDir);
        assertTrue(copied >= 14, "旧迁移集应含全部阶段Ⅰ迁移（8 版本 + 6 R__，实际复制 " + copied + " 个文件）");
        java.util.List<String> legacyNames;
        try (java.util.stream.Stream<Path> list = Files.list(legacyDir)) {
            legacyNames = list.map(p -> p.getFileName().toString()).toList();
        }
        assertTrue(legacyNames.stream().anyMatch(n -> n.startsWith("V0.1.7")),
                "旧迁移集应含链内既有 0.1.7");
        assertTrue(legacyNames.stream().noneMatch(n -> n.startsWith("V0.1.8")),
                "旧迁移集不得包含 0.1.8（等价回退代码）");
        assertTrue(legacyNames.stream().noneMatch(n -> n.startsWith("V0.1.9")),
                "旧迁移集不得包含 0.1.9（等价回退代码）");

        // 3) 回退观察：旧迁移集对在役 0.1.9 库 validate → 必须失败并指名未解析迁移
        //    （ignoreMigrationPatterns 仅忽略 ignored：库领先旧代码的 0.1.8/0.1.9 属 future 类，
        //     默认被忽略；本断言按完整校验口径显式检查）
        var validation = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations("filesystem:" + legacyDir.toAbsolutePath())
                .ignoreMigrationPatterns("*:ignored").load().validateWithResult();
        assertFalse(validation.validationSuccessful,
                "旧迁移集对在役 0.1.9 库 validate 必须失败（不得把不能处理存量的旧代码直接切回）");
        java.util.List<String> unresolvedVersions = validation.invalidMigrations.stream()
                .map(migration -> String.valueOf(migration.version))
                .toList();
        System.out.println("[p64-rollback] unresolved=" + unresolvedVersions);
        assertTrue(unresolvedVersions.contains("0.1.8") && unresolvedVersions.contains("0.1.9"),
                "validate 未解析迁移应含 0.1.8 与 0.1.9，实际: " + unresolvedVersions);

        // 4) 对照一：旧迁移集对旧终点库（0.1.7）迁移+validate 全通过——失败只源于 0.1.8/0.1.9 新增
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + LEGACY_DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + LEGACY_DB_NAME);
        }
        String legacyUrl = serverUrl.replace("/postgres", "/" + LEGACY_DB_NAME);
        var legacyMigrate = Flyway.configure().dataSource(legacyUrl, dbUser, dbPassword)
                .locations("filesystem:" + legacyDir.toAbsolutePath()).load().migrate();
        assertTrue(legacyMigrate.success, "旧迁移集对旧终点库应迁移成功");
        Flyway.configure().dataSource(legacyUrl, dbUser, dbPassword)
                .locations("filesystem:" + legacyDir.toAbsolutePath())
                .ignoreMigrationPatterns("*:ignored").load().validate();

        // 5) 对照二：在役（完整）配置对同一 0.1.9 库 validate 成功——失败只源于回退集缺迁移
        Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(P64AppendMigrationUpgradePostgresTest.appLocations())
                .ignoreMigrationPatterns("*:ignored").load().validate();

        // 4) 对照：在役（完整）配置对同一库 validate 成功——失败只源于回退集缺迁移
        Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(P64AppendMigrationUpgradePostgresTest.appLocations()).load().validate();
    }

    /** classpath 迁移资源复制桥（仅测试可见的清单复制逻辑）。 */
    private static final class PathMatchingResourceResolverBridge {
        private static final Pattern VERSION = Pattern.compile("V(0\\.1\\.\\d+)__");
        /** 0.1.9 期随岗位委托新增的可重复迁移，不属旧代码迁移集。 */
        private static final String PHASE2_R = "R__p64_position_delegate_menu.sql";

        int copyLegacyMigrations(Path targetDir) throws Exception {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver
                    .getResources("classpath*:db/migration/**/postgresql/*.sql");
            int copied = 0;
            for (Resource resource : resources) {
                String name = resource.getFilename();
                if (name == null) {
                    continue;
                }
                boolean legacy;
                Matcher matcher = VERSION.matcher(name);
                if (matcher.find()) {
                    legacy = matcher.group(1).compareTo("0.1.8") < 0;
                } else {
                    legacy = name.startsWith("R__") && !PHASE2_R.equals(name);
                }
                if (!legacy) {
                    continue;
                }
                Files.writeString(targetDir.resolve(name), read(resource), StandardCharsets.UTF_8);
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
                "本机 PostgreSQL 不可用（已尝试 postgres/123456 与本机默认身份）：P64 回退边界观察跳过，"
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

    @SuppressWarnings("unused")
    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1) > 0;
            }
        }
    }
}
