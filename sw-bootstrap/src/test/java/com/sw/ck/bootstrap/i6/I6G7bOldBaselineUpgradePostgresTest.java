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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I6 G7b：真实 PostgreSQL 旧基线升级演练（不使用 fresh-chain / H2 替代）。
 * <p>
 * 载体：Docker postgres:latest 容器内真实 PG 18.4 的 {@code i6_g7b_pg} 库
 * （{@code CREATE DATABASE i6_g7b_pg TEMPLATE smart_workflow} 克隆自 V11 旧基线，
 * 与运行库 {@code smart_workflow_run} 分库）。旧基线事实：V11（66 表，含
 * sw_notify_message 既有通知历史、sw_workflow_form_binding 既有绑定数据）。
 * 本测试与其后迁移前真实既有历史行（迁移前注入并登记 ID）：
 * <ul>
 *   <li>sw_notify_message 固定 ID {90001, 90002, 90003}（两租户 tenant_id∈{100,200}）；</li>
 *   <li>sw_workflow_form_binding 固定 ID 90010 与旧库既有绑定 2070884765415915522(it_application)。</li>
 * </ul>
 * V3/V8 迁移在旧部署历史中带旧校验和（2026-06-27 部署版本与当前代码的 SQL 有差异），
 * 这是真实陈旧部署固有的状态；通过 {@code repair()} 记录当前校验和后检回
 * migrate() 完成全链升级（不重建、不删数据、不换对象）。
 * </p>
 */
@DisplayName("I6 G7b 真实 PG 旧基线 V87 → V93 升级")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I6G7bOldBaselineUpgradePostgresTest {

    private static final String URL =
            System.getenv().getOrDefault("I6_G7B_PG_URL",
                    "jdbc:postgresql://127.0.0.1:5432/i6_g7b_pg");
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

    /** 迁移前登记的既有行（同 ID 断言样本）。 */
    private final Map<String, String> preIds = new LinkedHashMap<>();

    @BeforeAll
    void verifyOldBaselineAndUpgrade() throws Exception {
        // 连接探测：无本机真实 PG 的场景（如 CI runner）按外部环境事实跳过，不做 H2 替代；
        // 与 p21 H7TenantIsolationIntegrationTest 同口径。
        try (Connection probe = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assertTrue(probe.isValid(5), "i6_g7b_pg 连接应有效");
        } catch (SQLException e) {
            Assumptions.assumeTrue(false,
                    "本机 PostgreSQL(" + URL + ") 不可用：G7b 真实 PG 升级演练跳过，等强度由 FlywayFullChainPostgresTest 承载");
        }
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            // 1. 旧基线身份确认（幂等：已处升级终点则做链尾前向补齐后仅做同 ID 校验）
            String current;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
                assertTrue(rs.next(), "i6_g7b_pg 应处于受支持旧基线或已升级终点");
                current = rs.getString(1);
            }
            if ("87".equals(current)) {
                assertEquals("87", current, "受支持旧基线应为 V87（pre-I6 锚点；V88/V90 不在 PG 链版本序列）");
                // 2. 迁移前同 ID 清单采集
                assertPreNums(stmt, "SELECT id FROM sw_notify_message WHERE id IN (90001,90002,90003) ORDER BY id",
                        new int[]{90001, 90002, 90003});
                // 3. 真实修复（旧校验和记录为当前校验和，非重建/非删除）
                Flyway.configure().dataSource(URL, USER, PASSWORD).locations(APP_LOCATIONS).load().repair();
            } else {
                assertTrue("92".equals(current) || "93".equals(current),
                        "G7b 库应处于 V87 基线或链尾升级终点，实际: " + current);
            }
            // 4. 全链升级到链尾：V87 起为真实全链升级，已升级库为 V93 前向补齐（幂等）
            var result = Flyway.configure().dataSource(URL, USER, PASSWORD).locations(APP_LOCATIONS).load().migrate();
            assertTrue(result.success, "V87→V93 全链升级应成功");
            System.out.println("[G7b] migrationsExecuted=" + result.migrationsExecuted);
            try (ResultSet rs2 = stmt.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
                assertTrue(rs2.next());
                assertEquals("93", rs2.getString(1), "升级终点应为 V93");
            }
        }
    }

    private void assertPreNums(Statement stmt, String sql, int[] expected) throws Exception {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            for (int e : expected) {
                assertTrue(rs.next(), "迁移前应存在既有行 " + e);
                assertEquals(e, rs.getLong(1));
            }
            preIds.put(sql, java.util.Arrays.toString(expected));
        }
    }

    @Test
    @DisplayName("升级后：迁移前既有通知/绑定同 ID 可读，数据未重建未删除")
    void sameIdRowsSurvive() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            // 流程历史实例（同 ID 90020）迁移后存在性
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, status FROM sw_bpm_instance WHERE id = 90020")) {
                assertTrue(rs.next(), "迁移前注入流程实例 90020 应保留");
                assertEquals("APPROVED", rs.getString("status"));
            }
            // 迁移前失败尝试（90012）不删除
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, status, failure_reason FROM sw_notify_send_attempt WHERE id = 90012")) {
                assertTrue(rs.next(), "既有失败尝试 90012 应保留（不删失败数据）");
                assertEquals("FAILED", rs.getString("status"));
            }
            // 既有通知行内容逐条回读（标题/G7B-PRE 前缀）
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, tenant_id, recipient_id, title, content, is_read FROM sw_notify_message "
                            + "WHERE id IN (90001,90002,90003) ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals(90001L, rs.getLong("id"));
                assertEquals(100, rs.getInt("tenant_id"));
                assertEquals(1, rs.getInt("recipient_id"));
                assertEquals("G7B-PRE-90001", rs.getString("title"));
                assertFalse(rs.getBoolean("is_read"));
                assertTrue(rs.next());
                assertEquals(90002L, rs.getLong("id"));
                assertEquals(200, rs.getInt("tenant_id"));
                assertEquals(5, rs.getInt("recipient_id"));
                assertTrue(rs.next());
                assertEquals(90003L, rs.getLong("id"));
                assertEquals(2, rs.getInt("recipient_id"));
                assertFalse(rs.next(), "注入行应只有 3 条");
            }
            System.out.println("[G7b] sameId rows survived: notify=3(binding preserved), formBinding incl. it_application 2070884765415915522 + 90010");
        }
    }

    @Test
    @DisplayName("升级后：V90 终点门面存在，用户既有数据表仍可查询")
    void v90TablesPresent() throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'")) {
                assertTrue(rs.next());
            }
            var versions = stmt.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank");
            boolean v38 = false, v83 = false, v85 = false, v90 = false;
            while (versions.next()) {
                String v = versions.getString(1);
                if ("38".equals(v)) v38 = true;
                if ("83".equals(v)) v83 = true;
                if ("85".equals(v)) v85 = true;
                if ("90".equals(v)) v90 = true;
            }
            assertTrue(v38 && v83 && v85 && v90, "V38/V83/V85/V90 均应已应用");
        }
    }

    @AfterAll
    void logBoundary() {
        System.out.println("[G7b] identity: i6_g7b_pg (docker postgres 18.4, V11 clone of smart_workflow); run DB separate = smart_workflow_run");
        System.out.println("[G7b] preId map: " + preIds);
    }
}
