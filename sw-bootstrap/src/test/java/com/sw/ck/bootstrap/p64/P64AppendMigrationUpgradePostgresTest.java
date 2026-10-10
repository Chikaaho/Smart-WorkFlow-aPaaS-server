package com.sw.ck.bootstrap.p64;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P64 阶段Ⅰ A12 追加迁移演练（真实 PostgreSQL）：以 0.1.6 迁移链 + 非空存量
 * （旧流程定义/运行实例）为基线，验证 V0.1.7 追加升级——三张新表就位、存量行原义保持、
 * 迁移历史恰一条 0.1.7。
 * <p>
 * 沿 I6G7b 既有配方：一次性临时库、连接不可用按外部环境事实跳过（等强度由
 * FlywayFullChainPostgresTest / H2 链承载）；回退边界（旧代码不读写新表、数据保留）
 * 由 ADR-P64-001 §6 契约承载。
 * </p>
 */
@DisplayName("P64 A12 真实 PG 0.1.6 非空基线 → V0.1.7—V0.1.9 追加升级演练（阶段Ⅱ链尾）")
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

    private String serverUrl;
    private String dbUrl;
    private String dbUser;
    private String dbPassword;

    @Test
    @DisplayName("0.1.6 非空基线追加至链尾：新表就位、存量原义保持、链尾 0.1.9")
    void appendMigrationPreservesLegacyRows() throws Exception {
        probeConnection();
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + DB_NAME);
        }

        // 1) 迁移到 0.1.6 基线
        Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(APP_LOCATIONS).target("0.1.6").load().migrate();

        // 2) 非空存量：旧流程定义 + 运行实例 + 任务级审批动作行（升级前真实业务行）
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

        // 3) 追加升级到链尾（V0.1.7—V0.1.9）
        var result = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(APP_LOCATIONS).load().migrate();
        assertTrue(result.success, "追加迁移应成功");

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            // 终点版本 0.1.9
            assertEquals("0.1.9", currentVersion(conn), "升级终点应为 0.1.9");

            // 三张新表就位
            for (String table : new String[]{
                    "sw_bpm_task_form_data", "sw_bpm_trigger_exec", "sw_bpm_action_ref",
                    "sw_bpm_child_batch", "sw_bpm_child_item", "sys_post_delegate"}) {
                assertTrue(tableExists(conn, table), "新表应存在: " + table);
            }

            // 存量行原义保持（A12：受影响旧定义和实际实例原义保持）
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT process_key, name, status, def_version, graph_json FROM sw_bpm_process_def "
                            + "WHERE id = 9001");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "存量定义应保留");
                assertEquals("legacy_def_p64", rs.getString(1));
                assertEquals("存量定义", rs.getString(2));
                assertEquals("PUBLISHED", rs.getString(3));
                assertEquals(2, rs.getInt(4));
                // 冻结图逐字节不变：运行实例升级后按同一冻结图继续（原义完成的数据前提）
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
            // 任务级动作行原义保持（任务/轮次事实不受迁移影响）
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT task_id, actor_id, action FROM sw_bpm_approval_action WHERE id = 9003");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "存量任务动作行应保留");
                assertEquals("legacy-task-1", rs.getString(1));
                assertEquals(7, rs.getInt(2));
                assertEquals("APPROVE", rs.getString(3));
            }
            // 升级不写入新表：存量数据零迁移、零改写（追加式升级）
            try (Statement st2 = conn.createStatement();
                 ResultSet rs = st2.executeQuery(
                         "SELECT (SELECT COUNT(*) FROM sw_bpm_task_form_data) + "
                                 + "(SELECT COUNT(*) FROM sw_bpm_trigger_exec) + "
                                 + "(SELECT COUNT(*) FROM sw_bpm_action_ref) + "
                                 + "(SELECT COUNT(*) FROM sw_bpm_child_batch) + "
                                 + "(SELECT COUNT(*) FROM sw_bpm_child_item) + "
                                 + "(SELECT COUNT(*) FROM sys_post_delegate)")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "六张新表在升级后应为空（零迁移写回）");
            }

            // 迁移历史恰一条 0.1.7/0.1.8/0.1.9 且成功
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version, COUNT(*) FROM flyway_schema_history "
                            + "WHERE version IN ('0.1.7','0.1.8','0.1.9') AND success = TRUE "
                            + "GROUP BY version");
                 ResultSet rs = ps.executeQuery()) {
                int seen = 0;
                while (rs.next()) {
                    assertEquals(1, rs.getInt(2), rs.getString(1) + " 应恰有一条成功迁移记录");
                    seen++;
                }
                assertEquals(3, seen, "0.1.7/0.1.8/0.1.9 应各有一条成功迁移记录");
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
                "本机 PostgreSQL 不可用（已尝试 postgres/123456 与本机默认身份）：P64 追加迁移演练跳过，"
                        + "等强度由 FlywayFullChainPostgresTest 与 H2 链承载");
    }

    private String currentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM flyway_schema_history "
                     + "WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1")) {
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
