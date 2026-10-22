package com.sw.ck.bootstrap.p64;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P64 A12 追加迁移演练（真实 PostgreSQL）。
 * <p>
 * 原形式为「0.1.6 非空基线 → V0.1.7—V0.1.9 追加升级」；0.1.6/0.1.7 发版收敛后
 * versioned 链为单一种子 {@code V0.1.7__baseline_seed.sql}，中间版本不再存在，
 * 追加升级语义按开发声明（development-declaration.md §1/§5）重写为
 * <b>开发时间戳命名</b>：{@code V<yyyymmddhhmmss>__<功能需求>.sql}。</p>
 * <p>
 * 本演练证明：0.1.7 种子全新库 + 非空存量（旧流程定义/运行实例/任务级审批动作）→
 * 追加一支时间戳迁移（新建表，不触碰存量）→ 存量行原义逐字节保持、
 * 新表零写回、迁移历史恰一条时间戳成功记录、validate 通过、重复 migrate 零执行。
 * 即：发布基线（0.1.7）与开发时间戳迁移两套命名可在同一库内正确衔接，
 * 不会重复应用、跳过变化或重写历史。</p>
 * <p>
 * 沿既有配方：一次性临时库、连接不可用按外部环境事实跳过（等强度由
 * FlywayFullChainPostgresTest / H2 链承载）。
 * </p>
 */
@DisplayName("P64 A12 真实 PG 0.1.7 种子基线 → 开发时间戳迁移追加升级演练")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P64AppendMigrationUpgradePostgresTest {

    private static final String SERVER_URL =
            System.getenv().getOrDefault("P64_PG_URL", "jdbc:postgresql://127.0.0.1:5432/postgres");
    private static final String DB_NAME = "p64_upgrade_check";
    private static final String USER = "postgres";
    private static final String PASSWORD = "123456";

    private static final String[] APP_LOCATIONS = {
            "classpath:db/migration/postgresql",
            "classpath:db/migration/bpm/postgresql",
            "classpath:db/migration/notify/postgresql",
            "classpath:db/migration/form/postgresql",
            "classpath:db/migration/storage/postgresql",
            "classpath:db/migration/job/postgresql",
            "classpath:db/migration/agent/postgresql",
            "classpath:db/migration/iot/postgresql",
            "classpath:db/migration/openapi/postgresql",
            "classpath:db/migration/system/postgresql"
    };

    /** 包内共享：全链迁移位置（回退边界观察测试复用同一在役配置）。 */
    static String[] appLocations() {
        return APP_LOCATIONS.clone();
    }

    private String serverUrl;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;

    @Test
    @DisplayName("0.1.7 种子非空库追加时间戳迁移：存量原义保持、新表零写回、历史恰一条时间戳记录")
    void appendMigrationPreservesLegacyRows() throws Exception {
        probeConnection();
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + DB_NAME);
        }

        // 1) 迁移到最新链（V0.1.7 种子）
        var baselineResult = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(APP_LOCATIONS).load().migrate();
        assertTrue(baselineResult.success, "种子迁移应成功");
        assertEquals("0.1.10", currentVersion(conn()), "基线终点应为最新迁移");

        // 2) 非空存量：旧流程定义 + 运行实例 + 任务级审批动作行（追加前真实业务行）
        String legacyGraphJson = "{\"processKey\":\"legacy_def_p64\",\"elements\":["
                + "{\"kind\":\"node\",\"id\":\"start\",\"config\":{}},"
                + "{\"kind\":\"node\",\"id\":\"end\",\"config\":{}}]}";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sw_bpm_process_def (id, tenant_id, process_key, name, "
                    + "form_key, def_version, status, graph_json) VALUES "
                    + "(9001, 9, 'legacy_def_p64', '存量定义', 'legacy_form', 2, 'PUBLISHED', "
                    + "'" + legacyGraphJson.replace("'", "''") + "')");
            stmt.executeUpdate("INSERT INTO sw_bpm_instance (id, tenant_id, process_instance_id, "
                    + "process_def_key, business_key, form_key, initiator_id, status) VALUES "
                    + "(9002, 9, 'legacy-pi-1', 'legacy_def_p64', 'legacy-rec-1', 'legacy_form', "
                    + "7, 'RUNNING')");
            stmt.executeUpdate("INSERT INTO sw_bpm_approval_action (id, tenant_id, process_instance_id, "
                    + "node_key, task_id, actor_id, action) VALUES "
                    + "(9003, 9, 'legacy-pi-1', 'node_legacy', 'legacy-task-1', 7, 'APPROVE')");
        }

        // 3) 追加一支开发时间戳迁移（开发声明 §1 命名：V<yyyymmddhhmmss>__<功能需求>.sql，
        //    时区 Asia/Singapore；只新建表，不触碰存量）
        String ts = ZonedDateTime.now(ZoneId.of("Asia/Singapore"))
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String migrationName = "V" + ts + "__p64_append_drill.sql";
        Path drillDir = Files.createTempDirectory("p64-append-drill");
        Files.writeString(drillDir.resolve(migrationName),
                "-- 开发时间戳迁移演练：追加新表，不触碰存量数据。\n"
                        + "CREATE TABLE sw_p64_append_drill (\n"
                        + "    id bigint not null primary key,\n"
                        + "    note varchar(200)\n"
                        + ");\n", StandardCharsets.UTF_8);
        String[] withDrill = concat(APP_LOCATIONS, "filesystem:" + drillDir.toAbsolutePath());

        MigrateResult result = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(withDrill).load().migrate();
        assertTrue(result.success, "追加迁移应成功");
        assertEquals(1, result.migrationsExecuted, "追加升级应恰执行 1 条时间戳迁移");

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            // 终点版本 = 时间戳版本（0.1.6 之后）
            assertEquals(ts, currentVersion(conn), "升级终点应为时间戳版本");

            // 新表就位且零写回
            assertTrue(tableExists(conn, "sw_p64_append_drill"), "时间戳迁移新表应存在");
            try (Statement st2 = conn.createStatement();
                 ResultSet rs = st2.executeQuery("SELECT COUNT(*) FROM sw_p64_append_drill")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "追加升级不得向新表写回数据");
            }

            // 存量行原义保持
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT process_key, name, status, def_version, graph_json FROM sw_bpm_process_def "
                            + "WHERE id = 9001");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "存量定义应保留");
                assertEquals("legacy_def_p64", rs.getString(1));
                assertEquals("存量定义", rs.getString(2));
                assertEquals("PUBLISHED", rs.getString(3));
                assertEquals(2, rs.getInt(4));
                assertEquals(legacyGraphJson, rs.getString(5), "存量定义 graph_json 应逐字节不变");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT process_instance_id, business_key, status FROM sw_bpm_instance "
                            + "WHERE id = 9002");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "存量实例应保留");
                assertEquals("legacy-pi-1", rs.getString(1));
                assertEquals("legacy-rec-1", rs.getString(2));
                assertEquals("RUNNING", rs.getString(3));
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT task_id, actor_id, action FROM sw_bpm_approval_action WHERE id = 9003");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "存量任务动作行应保留");
                assertEquals("legacy-task-1", rs.getString(1));
                assertEquals(7, rs.getInt(2));
                assertEquals("APPROVE", rs.getString(3));
            }

            // 迁移历史：基础种子、两条租户增量与演练时间戳各一条成功记录
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version, COUNT(*) FROM flyway_schema_history "
                            + "WHERE version IS NOT NULL AND success = TRUE GROUP BY version");
                 ResultSet rs = ps.executeQuery()) {
                int seen = 0;
                while (rs.next()) {
                    String version = rs.getString(1);
                    assertEquals(1, rs.getInt(2), version + " 应恰有一条成功迁移记录");
                    assertTrue(java.util.Set.of("0.1.7", "0.1.8", "0.1.9", "0.1.10", ts).contains(version),
                            "versioned 历史应仅含基础种子、租户增量与时间戳，实际: " + version);
                    seen++;
                }
                assertEquals(4, seen, "versioned 历史应为基础种子、两条租户增量与时间戳共四条");
            }
        }

        // validate 通过 + 重复 migrate 零执行
        Flyway after = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(withDrill).load();
        after.validate();
        MigrateResult again = after.migrate();
        assertEquals(0, again.migrationsExecuted, "重复 migrate 不应执行任何迁移");
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    }

    private static String[] concat(String[] left, String right) {
        String[] out = new String[left.length + 1];
        System.arraycopy(left, 0, out, 0, left.length);
        out[left.length] = right;
        return out;
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
                "本机 PostgreSQL 不可用（已尝试 postgres/123456 与本机默认身份）：P64 追加迁移演练跳过，"
                        + "等强度由 FlywayFullChainPostgresTest 与 H2 链承载");
    }

    private String currentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM flyway_schema_history "
                     + "WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next(), "迁移历史不应为空");
            return rs.getString(1);
        }
    }

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
