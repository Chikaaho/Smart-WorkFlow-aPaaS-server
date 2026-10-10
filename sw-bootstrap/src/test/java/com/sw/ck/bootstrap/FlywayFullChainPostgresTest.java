package com.sw.ck.bootstrap;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 基线种子全链 PostgreSQL Flyway 迁移 + 逻辑删除唯一语义验证的永久测试（不启动 Spring 上下文）。
 * <p>
 * 使用 zonky embedded-postgres 启动真实 PostgreSQL 二进制，独立 Flyway 实例，
 * 10 个 locations 与 {@code application.yml} 完全一致（{vendor} 按 PostgreSQL 连接
 * 解析为 postgresql）。0.1.3 种子合并后，versioned 迁移收敛为单一基线
 * {@code V0.1.0__baseline_seed.sql}（原 V1—V104 按版本序逐字节合并），
 * 加可重复对账 {@code R__i6_notify_menu_reconciliation}，全新库共执行 2 条。
 * 历史版本升级演练（V32 分阶段、旧 V13 校验和场景）随 0.1.3「仅支持全新建库」
 * 的边界一并退役，分别由「重复 migrate 幂等」与「基线校验和篡改显式失败」承接；
 * V13/V83 的逻辑删除唯一语义守卫以终态对象断言延续。
 * </p>
 * <p>
 * 本测试是 H2 侧 {@link FlywayFullChainH2Test} 的 PG 镜像。PG 与 H2 在「唯一约束
 * 背书的隐式索引」上行为不同——PG 报 2BP01（cannot drop index ... because
 * constraint ... requires it），因此基线内该段落用 {@code ALTER TABLE sw_form_def
 * DROP CONSTRAINT IF EXISTS sw_form_def_form_key_key;} 释放隐式索引。本测试断言
 * 该约束已删除、复合唯一索引 {@code uk_sw_form_def_form_key} 已建立，并对
 * sys_user(username, deleted) / sys_tenant(code, deleted) 逻辑删除唯一语义做正反例
 * 验证（PG 唯一约束冲突 SQLState=23505）。注意：复合唯一 (key, deleted) 而非
 * partial 索引，每个业务键最多一条 deleted=1 软删历史——重复软删（第二条 deleted=1）
 * 会被 PG 拒绝，这与 sw_bpm_form_binding 的 partial 索引（可多条 inactive）语义不同。
 * </p>
 */
@DisplayName("基线种子全链 PostgreSQL Flyway 迁移 + 逻辑删除唯一语义验证")
class FlywayFullChainPostgresTest {

    /**
     * 与 application.yml flyway.locations 完全一致的 10 个位置。
     * <p>
     * 注意：{vendor} 占位符并非由 flyway-core 解析，而是 Spring Boot
     * {@code FlywayAutoConfiguration$LocationResolver} 按 JDBC 驱动替换
     * （flyway-core 11.3.4 实测不识别 {vendor}）。本测试不启动 Spring 上下文，
     * 故在 {@link #startPostgresAndMigrateBaseline()} 中按 PG 连接显式解析为
     * postgresql，目录结构与 application.yml 一一对应。
     * </p>
     */
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

    /** zonky initdb 使用 -A trust -U postgres，任意密码均可通过。 */
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    private static EmbeddedPostgres pg;
    private static String url;
    private static String[] locations;

    @BeforeAll
    static void startPostgresAndMigrateBaseline() throws Exception {
        // builder() 默认随机空闲端口（detectPort），避免与本机已有 PG 冲突
        pg = EmbeddedPostgres.builder().start();
        // getJdbcUrl(user, db) → jdbc:postgresql://localhost:{port}/{db}?user={user}
        url = pg.getJdbcUrl(USER, "postgres");
        locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "postgresql"))
                .toArray(String[]::new);
        MigrateResult result = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(locations)
                .load()
                .migrate();
        assertTrue(result.success, "基线迁移应成功");
        assertEquals(17, result.migrationsExecuted,
                "全新库应执行 17 条（V0.1.0 基线 + V0.1.1—V0.1.9 增量 + 7 个 R__ 可重复对账），实际: "
                        + result.migrationsExecuted);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    @DisplayName("基线迁移后：info().applied() 共 17 条（0.1.0—0.1.9 + 7 个可重复对账），终点 0.1.9")
    void appliedMigrations_shouldBeBaselineAndRepeatable() {
        org.flywaydb.core.api.MigrationInfo[] applied = flyway().info().applied();
        assertEquals(17, applied.length, "已应用迁移数应为 17（基线 + 9 个增量 + 7 个 R__）");
        boolean baselineSeen = false;
        int incrementalSeen = 0;
        int repeatableCount = 0;
        for (org.flywaydb.core.api.MigrationInfo info : applied) {
            if (info.getVersion() == null) {
                repeatableCount++;
            } else if ("0.1.0".equals(info.getVersion().getVersion())) {
                baselineSeen = true;
            } else if ("0.1.1".equals(info.getVersion().getVersion())
                    || "0.1.2".equals(info.getVersion().getVersion())
                    || "0.1.3".equals(info.getVersion().getVersion())
                    || "0.1.4".equals(info.getVersion().getVersion())
                    || "0.1.5".equals(info.getVersion().getVersion())
                    || "0.1.6".equals(info.getVersion().getVersion())
                    || "0.1.7".equals(info.getVersion().getVersion())
                    || "0.1.8".equals(info.getVersion().getVersion())
                    || "0.1.9".equals(info.getVersion().getVersion())) {
                incrementalSeen++;
            }
        }
        assertTrue(baselineSeen, "V0.1.0 基线应已应用");
        assertEquals(9, incrementalSeen, "V0.1.1—V0.1.9 增量应已应用");
        assertEquals(7, repeatableCount, "7 个 R__ 菜单可重复对账应已应用");
        assertEquals("0.1.9", flyway().info().current().getVersion().getVersion(),
                "终点当前版本应为 0.1.9 增量");
    }

    @Test
    @DisplayName("基线迁移后：再次 validate() 通过（无校验和/缺失迁移问题）")
    void validate_shouldPass() {
        flyway().validate();
    }

    @Test
    @DisplayName("基线迁移后：重复 migrate 幂等（0 条新执行，版本不变）")
    void reMigrate_shouldBeIdempotent() {
        MigrateResult again = flyway().migrate();
        assertEquals(0, again.migrationsExecuted, "重复 migrate 不应执行任何迁移");
        assertEquals("0.1.9", flyway().info().current().getVersion().getVersion(), "版本应保持 0.1.9");
    }

    @Test
    @DisplayName("基线校验和安全：篡改 0.1.0 登记校验和后必须显式失败（不静默通过、不改写校验和）")
    void tamperedBaselineChecksum_shouldFailValidateNotSilentlyPass() throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE baseline_checksum_tampered");
        }
        String tamperedUrl = pg.getJdbcUrl(USER, "baseline_checksum_tampered");

        Flyway migrate = Flyway.configure()
                .dataSource(tamperedUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult first = migrate.migrate();
        assertTrue(first.success, "建立基线库应成功");
        assertEquals(14, first.migrationsExecuted, "基线库应含 14 条，实际: " + first.migrationsExecuted);

        try (Connection conn = DriverManager.getConnection(tamperedUrl, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE flyway_schema_history SET checksum = checksum + 1 "
                    + "WHERE version = '0.1.0' AND success = TRUE");
        }

        Flyway legacy = Flyway.configure()
                .dataSource(tamperedUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        try {
            legacy.migrate();
            fail("篡改基线校验和的库在 validate-on-migrate 下必须显式失败（保护数据，不静默改写）");
        } catch (FlywayException expected) {
            // 预期：checksum 不匹配显式失败
        }
    }

    @Test
    @DisplayName("S3：PostgreSQL 生产权限资源可查询并由普通角色绑定")
    void notifyBatchPermissionResource_shouldBeQueryableAndBindable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, parent_id, path, component, permission, menu_type FROM sys_menu WHERE id IN (218, 219) ORDER BY id")) {
                assertTrue(rs.next(), "批量发送页面菜单 id=218 应存在");
                assertEquals(6, rs.getInt("parent_id"));
                assertEquals("notify/batch-send", rs.getString("path"));
                assertEquals("notify/views/NotifyBatchSend", rs.getString("component"));
                assertEquals("notify:batch:send", rs.getString("permission"));
                assertEquals(1, rs.getInt("menu_type"));
                assertTrue(rs.next(), "批量发送按钮菜单 id=219 应存在");
                assertEquals(218, rs.getInt("parent_id"));
                assertEquals("notify:batch:send", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertFalse(rs.next(), "批量发送权限资源不应多出第三行");
            }
            stmt.executeUpdate("INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) VALUES (900001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 2, 219)");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT r.id, r.code, r.built_in, m.permission FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id WHERE rm.role_id = 2 AND rm.menu_id = 219 AND rm.deleted = 0")) {
                assertTrue(rs.next(), "普通 admin 角色应能绑定批量发送按钮权限");
                assertEquals(2, rs.getInt("id"));
                assertEquals("admin", rs.getString("code"));
                assertFalse(rs.getBoolean("built_in"));
                assertEquals("notify:batch:send", rs.getString("permission"));
                assertFalse(rs.next(), "普通角色绑定应只有一条有效关系");
            }
            System.out.println("[S3-production] PostgreSQL baseline menu=(218,batch-send,notify/views/NotifyBatchSend,notify:batch:send), button=(219,notify:batch:send), ordinaryRole=(id=2,code=admin,built_in=false) boundMenu=219, queryExit=0");
        }
    }

    @Test
    @DisplayName("逻辑删除唯一语义守卫：sw_form_def_form_key_key 约束已删除，uk_sw_form_def_form_key 复合唯一索引已建立")
    void formDefConstraintDropped_compositeUniqueIndexCreated() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            // PG 中该唯一约束背书的隐式索引不得残留（2BP01 根因对象）
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'sw_form_def_form_key_key'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1),
                        "V7 inline UNIQUE 产生的约束 sw_form_def_form_key_key 应在原 V13 段被 DROP CONSTRAINT 删除");
            }
            // 复合唯一索引 (form_key, deleted) 必须存在（PG 区分大小写，索引名按小写存储）
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'sw_form_def' "
                            + "AND indexname = 'uk_sw_form_def_form_key'")) {
                assertTrue(rs.next(), "唯一索引 uk_sw_form_def_form_key 应存在");
                assertTrue(rs.getString(1).contains("(tenant_id, form_key, deleted)"),
                        "uk_sw_form_def_form_key 应为 (tenant_id, form_key, deleted) 租户级复合唯一索引（原 I5 V83 段），实际: " + rs.getString(1));
            }
        }
    }

    @Test
    @DisplayName("正例1：插入 username=x deleted=0 成功；软删（deleted=1）后以同 username 重建 deleted=0 成功（两条共存）")
    void logicalDelete_positive_insertAfterSoftDelete() throws SQLException {
        insertUser(900001L, "pg_sem_u1", 0);
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE sys_user SET deleted = 1 WHERE id = 900001");
        }
        insertUser(900002L, "pg_sem_u1", 0);

        assertEquals(2, countRows(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'pg_sem_u1'"),
                "软删记录与重建的 deleted=0 记录应共存");
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'pg_sem_u1' AND deleted = 1"));
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM sys_user WHERE username = 'pg_sem_u1' AND deleted = 0"));
    }

    @Test
    @DisplayName("正例2：sys_tenant(code, deleted) 同样支持软删重建 — 插入 code=c deleted=0 成功，软删后重建 deleted=0 成功（两条共存）")
    void logicalDelete_positive_tenantSoftDeleteRebuild() throws SQLException {
        insertTenant(900031L, "pg_sem_t1");
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE sys_tenant SET deleted = 1 WHERE id = 900031");
        }
        insertTenant(900032L, "pg_sem_t1");

        assertEquals(2, countRows(
                "SELECT COUNT(*) FROM sys_tenant WHERE code = 'pg_sem_t1'"),
                "软删租户与重建的 deleted=0 记录应共存");
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM sys_tenant WHERE code = 'pg_sem_t1' AND deleted = 1"));
        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM sys_tenant WHERE code = 'pg_sem_t1' AND deleted = 0"));
    }

    @Test
    @DisplayName("反例：已存在 username=x deleted=0 时，再插 username=x deleted=0 被拒绝（SQLState=23505）")
    void logicalDelete_negative_duplicateActiveRow_shouldFail23505() throws SQLException {
        insertUser(900021L, "pg_sem_u2", 0);

        expectUniqueViolation(() -> insertUser(900022L, "pg_sem_u2", 0));
    }

    @Test
    @DisplayName("边界（复合唯一语义）：已存在 username=x deleted=1 软删历史时，再插第二条 username=x deleted=1 被拒绝（SQLState=23505）")
    void logicalDelete_negative_secondSoftDeletedRow_shouldFail23505() throws SQLException {
        // 复合唯一 (username, deleted) 的设计保证：每个业务键最多一条 deleted=1 历史
        // 记录（重复软删会被 PG 拒绝，这是复合唯一而非 partial 索引的固有语义）
        insertUser(900041L, "pg_sem_h1", 1);

        expectUniqueViolation(() -> insertUser(900042L, "pg_sem_h1", 1));
    }

    @Test
    @DisplayName("P62 产物：事务动作菜单 9100 与按钮 9101—9103 存在，且授予普通 admin（role 2）")
    void p62TxnActionMenuSeed_finalState() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT name, permission, component, path, menu_type, parent_id FROM sys_menu WHERE id = 9100")) {
                assertTrue(rs.next(), "菜单 id=9100（事务动作）应存在");
                assertEquals("FormTxnAction", rs.getString("name"));
                assertEquals("form:action:view", rs.getString("permission"));
                assertEquals("form/views/TxnActionList", rs.getString("component"));
                assertEquals("txn-action", rs.getString("path"));
                assertEquals(1, rs.getInt("menu_type"));
                assertEquals(2, rs.getInt("parent_id"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT permission, menu_type, parent_id FROM sys_menu WHERE id IN (9101, 9102, 9103) ORDER BY id")) {
                assertTrue(rs.next(), "按钮 id=9101 应存在");
                assertEquals("form:action:manage", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertEquals(9100, rs.getInt("parent_id"));
                assertTrue(rs.next(), "按钮 id=9102 应存在");
                assertEquals("form:action:publish", rs.getString("permission"));
                assertEquals(9100, rs.getInt("parent_id"));
                assertTrue(rs.next(), "按钮 id=9103 应存在");
                assertEquals("form:action:invoke", rs.getString("permission"));
                assertEquals(9100, rs.getInt("parent_id"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id "
                            + "WHERE rm.role_id = 2 AND m.id IN (9100, 9101, 9102, 9103) AND rm.deleted = 0")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1), "普通 admin 角色应获授全部 4 个事务动作菜单");
            }
            System.out.println("[S3-production] P62 txn menu=(9100,form:action:view,form/views/TxnActionList),"
                    + " buttons=(9101/9102/9103 manage/publish/invoke), ordinaryRoleBound=4, queryExit=0");
        }
    }

    @Test
    @DisplayName("V36 产物：调试会话表 sw_agent_graph_debug_session / sw_agent_graph_debug_node 存在")
    void debugSessionTables_shouldExist() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sw_agent_graph_debug_session'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "sw_agent_graph_debug_session 表应存在");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'sw_agent_graph_debug_node'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "sw_agent_graph_debug_node 表应存在");
            }
        }
    }

    // ==================== 辅助方法 ====================

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(locations)
                .load();
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    /** 断言唯一约束冲突：PG 唯一约束冲突 SQLState 为 23505，用 try/catch 显式捕获断言。 */
    private void expectUniqueViolation(SqlRunnable action) throws SQLException {
        try {
            action.run();
            fail("预期唯一约束冲突，但语句执行成功");
        } catch (SQLException e) {
            assertEquals("23505", e.getSQLState(),
                    "唯一约束冲突 SQLState 应为 23505，实际: " + e.getSQLState() + "，消息: " + e.getMessage());
        }
    }

    private void insertTenant(Long id, String code) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sys_tenant (id, name, code, deleted, tenant_id) VALUES ("
                    + id + ", '" + code + "', '" + code + "', 0, 0)");
        }
    }

    private void insertUser(Long id, String username, int deleted) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sys_user (id, username, password, deleted, tenant_id) VALUES ("
                    + id + ", '" + username + "', 'x', " + deleted + ", 0)");
        }
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
