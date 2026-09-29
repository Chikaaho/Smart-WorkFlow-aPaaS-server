package com.sw.ck.bootstrap.i6;




import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I6 G7b：真实 PostgreSQL 基线演练（不使用 H2 替代）。
 * <p>
 * 0.1.3 种子合并后历史升级路径退役（0.1.3 起仅支持全新建库），本测试由
 * 「V87 旧基线 → 链尾升级保留既有数据」改为「真实 PG 上 V0.1.0 基线全新建库」：
 * 在本机真实 PostgreSQL 上自建临时演练库（drop/create，不依赖预置旧库），
 * 执行基线（V0.1.0 + R__ 可重复对账）后验证通知三表在两租户下的真实
 * INSERT/SELECT 语义与终点版本。连接不可用（如 CI runner）按外部环境事实跳过。
 * </p>
 */
@DisplayName("I6 G7b 真实 PG V0.1.0 基线全新建库演练")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I6G7bOldBaselineUpgradePostgresTest {

    private static final String SERVER_URL =
            System.getenv().getOrDefault("I6_G7B_PG_URL",
                    "jdbc:postgresql://127.0.0.1:5432/postgres");
    private static final String DB_NAME = "i6_g7b_baseline";
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

    private String dbUrl;
    private String serverUrl;
    private String dbUser;
    private String dbPassword;

    @BeforeAll
    void createScratchDatabaseAndMigrateBaseline() throws Exception {
        // 连接探测：优先环境变量指定（原 postgres/123456 约定），退回本机默认身份
        // （Homebrew PG：OS 用户名超user、无密码、postgres 库）；均不可用（如 CI runner）
        // 按外部环境事实跳过，不做 H2 替代。
        String osUser = System.getProperty("user.name", "postgres");
        String[][] candidates = System.getenv().getOrDefault("I6_G7B_PG_URL", "").isEmpty()
                ? new String[][]{
                        {SERVER_URL, USER, PASSWORD},
                        {"jdbc:postgresql://127.0.0.1:5432/postgres", osUser, ""}}
                : new String[][]{{SERVER_URL, USER, PASSWORD}};
        boolean connected = false;
        for (String[] c : candidates) {
            try (Connection probe = DriverManager.getConnection(c[0], c[1], c[2])) {
                if (probe.isValid(5)) {
                    serverUrl = c[0];
                    dbUser = c[1];
                    dbPassword = c[2];
                    connected = true;
                    break;
                }
            } catch (SQLException e) {
                // 尝试下一候选
            }
        }
        if (!connected) {
            Assumptions.assumeTrue(false,
                    "本机 PostgreSQL 不可用（已尝试 postgres/123456 与本机默认身份）：G7b 真实 PG 基线演练跳过，等强度由 FlywayFullChainPostgresTest 承载");
        }
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
            stmt.executeUpdate("CREATE DATABASE " + DB_NAME);
        }
        dbUrl = serverUrl.replace("/postgres", "/" + DB_NAME);

        var result = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(APP_LOCATIONS).load().migrate();
        assertTrue(result.success, "基线迁移应成功");
        assertEquals(2, result.migrationsExecuted, "全新库应执行 2 条（V0.1.0 + R__）");
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals("0.1.0", rs.getString(1), "升级终点应为 0.1.0 基线");
        }
    }

    @AfterAll
    void dropScratchDatabase() {
        if (dbUrl == null) {
            return; // @BeforeAll 探测失败跳过时无库可清理
        }
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
        } catch (SQLException e) {
            throw new IllegalStateException("清理临时库失败", e);
        }
        System.out.println("[G7b] identity: " + DB_NAME + " (本机真实 PG 临时库，基线终态 0.1.0，已清理)");
    }

    @Test
    @DisplayName("基线库：通知消息两租户真实写入/回读，模板与发送尝试语义可解释")
    void notificationSemanticsOnRealPostgres() throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO sw_notify_message (id, recipient_id, title, content, biz_type, biz_id, is_read, deleted, tenant_id, version, create_time, update_time) "
                    + "VALUES (90001, 1, 'G7B-90001', 'G7B-PRE-90001', 'SYSTEM', 'g7b-1', FALSE, 0, 100, 0, now(), now())");
            stmt.execute("INSERT INTO sw_notify_message (id, recipient_id, title, content, biz_type, biz_id, is_read, deleted, tenant_id, version, create_time, update_time) "
                    + "VALUES (90002, 5, 'G7B-90002', 'G7B-PRE-90002', 'SYSTEM', 'g7b-2', FALSE, 0, 200, 0, now(), now())");
            stmt.execute("INSERT INTO sw_notify_message (id, recipient_id, title, content, biz_type, biz_id, is_read, deleted, tenant_id, version, create_time, update_time) "
                    + "VALUES (90003, 2, 'G7B-90003', 'G7B-PRE-90003', 'SYSTEM', 'g7b-3', FALSE, 0, 100, 0, now(), now())");
            stmt.execute("INSERT INTO sw_notify_send_attempt (id, message_id, attempt_no, channel, status, failure_reason, deleted, tenant_id, version) "
                    + "VALUES (90012, 90001, 1, 'IN_APP', 'FAILED', 'g7b-failure', 0, 100, 0)");
        }
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, tenant_id, recipient_id, title, content, is_read FROM sw_notify_message "
                            + "WHERE id IN (90001,90002,90003) ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(90001L, rs.getLong("id"));
                assertEquals(100, rs.getInt("tenant_id"));
                assertEquals(1, rs.getInt("recipient_id"));
                assertEquals("G7B-90001", rs.getString("title"));
                assertFalse(rs.getBoolean("is_read"));
                assertTrue(rs.next());
                assertEquals(90002L, rs.getLong("id"));
                assertEquals(200, rs.getInt("tenant_id"));
                assertEquals(5, rs.getInt("recipient_id"));
                assertTrue(rs.next());
                assertEquals(90003L, rs.getLong("id"));
                assertEquals(2, rs.getInt("recipient_id"));
                assertFalse(rs.next(), "写入行应只有 3 条");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, status, failure_reason FROM sw_notify_send_attempt WHERE id = 90012")) {
                assertTrue(rs.next(), "失败尝试可按原 ID 读取");
                assertEquals("FAILED", rs.getString("status"));
                assertEquals("g7b-failure", rs.getString("failure_reason"));
            }
            System.out.println("[G7b] baseline rows: notify=3(two tenants), attempt=1(FAILED preserved semantics)");
        }
    }

    @Test
    @DisplayName("基线库：通知门面表齐备，历史表含 0.1.0 与可重复对账")
    void baselineHistoryAndFacadeTables() throws Exception {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            for (String table : new String[]{"sw_notify_message", "sw_notify_template", "sw_notify_send_attempt"}) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '" + table + "'")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), table + " 应存在");
                }
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "历史表应恰有 2 条成功记录（0.1.0 + R__）");
            }
        }
    }
}
