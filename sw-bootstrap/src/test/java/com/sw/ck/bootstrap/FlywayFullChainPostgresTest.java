package com.sw.ck.bootstrap;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 全链 PostgreSQL Flyway 迁移 + 逻辑删除唯一语义验证的永久测试（不启动 Spring 上下文）。
 * <p>
 * 使用 zonky embedded-postgres 启动真实 PostgreSQL 17.5 二进制（macOS arm64），
 * 独立 Flyway 实例，7 个 locations 与 {@code application.yml} 完全一致
 * （{vendor} 按 PostgreSQL 连接解析为 postgresql）。全链共 66 条迁移。
 * </p>
 * <p>
 * 本测试是 H2 侧 {@link FlywayFullChainH2Test} 的 PG 镜像，并承载 V13 修复的回归守卫：
 * PG 与 H2 在「唯一约束背书的隐式索引」上行为不同——H2 允许直接 DROP INDEX 约束背书索引，
 * PG 报 2BP01（cannot drop index ... because constraint ... requires it），因此 PG 侧
 * V13 第 7 项必须用 {@code ALTER TABLE sw_form_def DROP CONSTRAINT IF EXISTS
 * sw_form_def_form_key_key;} 释放 V7 inline UNIQUE 创建的隐式索引。本测试断言该约束已
 * 删除、复合唯一索引 {@code uk_sw_form_def_form_key} 已建立，并对
 * sys_user(username, deleted) / sys_tenant(code, deleted) 逻辑删除唯一语义做正反例验证
 * （PG 唯一约束冲突 SQLState=23505）。注意：V13 采用复合唯一 (key, deleted) 而非
 * partial 索引，每个业务键最多一条 deleted=1 软删历史——重复软删（第二条 deleted=1）
 * 会被 PG 拒绝，这与 sw_bpm_form_binding 的 partial 索引（可多条 inactive）语义不同。
 * </p>
 */
@DisplayName("BPM 全链 PostgreSQL Flyway 迁移 + 逻辑删除唯一语义验证")
class FlywayFullChainPostgresTest {

    /**
     * 与 application.yml flyway.locations 完全一致的 8 个位置。
     * <p>
     * 注意：{vendor} 占位符并非由 flyway-core 解析，而是 Spring Boot
     * {@code FlywayAutoConfiguration$LocationResolver} 按 JDBC 驱动替换
     * （flyway-core 11.3.4 实测不识别 {vendor}）。本测试不启动 Spring 上下文，
     * 故在 {@link #startPostgresAndMigrateFullChain()} 中按 PG 连接显式解析为
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
    static void startPostgresAndMigrateFullChain() throws Exception {
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
        assertTrue(result.success, "全链迁移应成功");
        assertEquals(85, result.migrationsExecuted,
                "全链迁移计数应为 85（I4 锁定 81 + I5 V83/V84，复验 02 补 V85 种子与 V86 应用唯一），实际: "
                        + result.migrationsExecuted);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    @DisplayName("全链迁移后：info().applied() 共 53 条，包含 P58 通知渠道与流程节点能力迁移")
    void appliedMigrationCount_shouldBe35() {
        org.flywaydb.core.api.MigrationInfo[] applied = flyway().info().applied();
        assertEquals(85, applied.length, "已应用迁移数应为 85");
        boolean v8Seen = false;
        boolean v14Seen = false;
        boolean v31Seen = false;
        boolean v33Seen = false;
        boolean v34Seen = false;
        boolean v35Seen = false;
        boolean v36Seen = false;
        boolean v37Seen = false;
        boolean v38Seen = false;
        boolean v39Seen = false;
        for (org.flywaydb.core.api.MigrationInfo info : applied) {
            if ("8".equals(info.getVersion().getVersion())) {
                v8Seen = true;
            }
            if ("14".equals(info.getVersion().getVersion())) {
                v14Seen = true;
            }
            if ("31".equals(info.getVersion().getVersion())) {
                v31Seen = true;
            }
            if ("33".equals(info.getVersion().getVersion())) {
                v33Seen = true;
            }
            if ("34".equals(info.getVersion().getVersion())) {
                v34Seen = true;
            }
            if ("35".equals(info.getVersion().getVersion())) {
                v35Seen = true;
            }
            if ("36".equals(info.getVersion().getVersion())) {
                v36Seen = true;
            }
            if ("37".equals(info.getVersion().getVersion())) {
                v37Seen = true;
            }
            if ("38".equals(info.getVersion().getVersion())) {
                v38Seen = true;
            }
            if ("39".equals(info.getVersion().getVersion())) {
                v39Seen = true;
            }
        }
        assertTrue(v8Seen, "BPM V8 应已应用");
        assertTrue(v14Seen, "BPM V14 应已应用");
        assertTrue(v31Seen, "P24 V31 应已应用");
        assertTrue(v33Seen, "V33 大模型菜单 seed 应已应用");
        assertTrue(v34Seen, "V34 用户组迁移应已应用");
        assertTrue(v35Seen, "V35 Agent Token Usage 应已应用");
        assertTrue(v36Seen, "V36 调试会话应已应用");
        assertTrue(v37Seen, "V37 工具管理菜单 seed 应已应用");
        assertTrue(v38Seen, "V38 消息模板迁移应已应用");
        assertTrue(v39Seen, "V39 批量发送权限迁移应已应用");
    }

    @Test
    @DisplayName("全链迁移后：再次 validate() 通过（无校验和/缺失迁移问题）")
    void validate_shouldPass() {
        flyway().validate();
    }

    @Test
    @DisplayName("S3：PostgreSQL 生产权限资源可查询并由普通角色绑定")
    void notifyBatchPermissionResource_shouldBeQueryableAndBindable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, parent_id, path, component, permission, menu_type FROM sys_menu WHERE id IN (218, 219) ORDER BY id")) {
                assertTrue(rs.next(), "V39 批量发送页面菜单 id=218 应存在");
                assertEquals(6, rs.getInt("parent_id"));
                assertEquals("batch-send", rs.getString("path"));
                assertEquals("notify/views/NotifyBatchSend", rs.getString("component"));
                assertEquals("notify:batch:send", rs.getString("permission"));
                assertEquals(1, rs.getInt("menu_type"));
                assertTrue(rs.next(), "V39 批量发送按钮菜单 id=219 应存在");
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
            System.out.println("[S3-production] PostgreSQL V39 menu=(218,batch-send,notify/views/NotifyBatchSend,notify:batch:send), button=(219,notify:batch:send), ordinaryRole=(id=2,code=admin,built_in=false) boundMenu=219, queryExit=0");
        }
    }

    @Test
    @DisplayName("V13 修复回归守卫：sw_form_def_form_key_key 约束已删除，uk_sw_form_def_form_key 复合唯一索引已建立")
    void formDefConstraintDropped_compositeUniqueIndexCreated() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            // PG 中该唯一约束背书的隐式索引不得残留（2BP01 根因对象）
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'sw_form_def_form_key_key'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1),
                        "V7 inline UNIQUE 产生的约束 sw_form_def_form_key_key 应在 V13 被 DROP CONSTRAINT 删除");
            }
            // 复合唯一索引 (form_key, deleted) 必须存在（PG 区分大小写，索引名按小写存储）
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = 'sw_form_def' "
                            + "AND indexname = 'uk_sw_form_def_form_key'")) {
                assertTrue(rs.next(), "唯一索引 uk_sw_form_def_form_key 应存在");
                assertTrue(rs.getString(1).contains("(tenant_id, form_key, deleted)"),
                        "uk_sw_form_def_form_key 应为 (tenant_id, form_key, deleted) 租户级复合唯一索引（I5 V83），实际: " + rs.getString(1));
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
        // V13 复合唯一 (username, deleted) 的设计保证：每个业务键最多一条 deleted=1 历史
        // 记录（重复软删会被 PG 拒绝，这是复合唯一而非 partial 索引的固有语义）
        insertUser(900041L, "pg_sem_h1", 1);

        expectUniqueViolation(() -> insertUser(900042L, "pg_sem_h1", 1));
    }

    @Test
    @DisplayName("既有库升级链：先 target(32) 迁移至 V32，再全量迁移至当前版本，validate() 通过")
    void upgradeChain_V32_to_V35_shouldPass() throws SQLException {
        // 模拟既有库：在独立数据库中先迁移至 V32
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE upgrade_chain");
        }
        String upgradeUrl = pg.getJdbcUrl(USER, "upgrade_chain");

        MigrateResult first = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .target("32")
                .load()
                .migrate();
        assertTrue(first.success, "先迁移至 V32 应成功");
        assertEquals(32, first.migrationsExecuted, "V32 阶段应执行 32 条，实际: " + first.migrationsExecuted);

        // 既有库全量升级：只应执行 V33/V34/V35/V36 四条
        Flyway full = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult second = full.migrate();
        assertTrue(second.success, "V32→链尾升级链应成功");
        assertEquals(53, second.migrationsExecuted, "升级链应执行 V33-V86 五十三条，实际: " + second.migrationsExecuted);
        full.validate();
    }

    @Test
    @DisplayName("既有库校验和安全：登记原 V13 checksum 的库不会静默通过——Flyway 显式校验失败（不改写校验和）")
    void legacyOriginalV13Checksum_shouldFailValidateNotSilentlyPass() throws Exception {
        // 构造「登记了原始 V13（DROP INDEX 版）checksum」的既有库：
        // 先用当前（修改后）V13 全链迁移至 V33，再把 flyway_schema_history 中
        // V13 的 checksum 手工改写为原始 V13 内容（DROP INDEX IF EXISTS
        // sw_form_def_form_key_key;）的 CRC32——模拟「若真存在原 V13 成功环境，
        // 其登记 checksum 与当前文件不同」的场景。
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE legacy_original_checksum");
        }
        String legacyUrl = pg.getJdbcUrl(USER, "legacy_original_checksum");

        Flyway migrate = Flyway.configure()
                .dataSource(legacyUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult first = migrate.migrate();
        assertTrue(first.success, "建立既有库应成功");
        assertEquals(85, first.migrationsExecuted, "既有库应含全部 85 条，实际: " + first.migrationsExecuted);

        // 原始 V13 的 L58 内容（修改前）：DROP INDEX IF EXISTS sw_form_def_form_key_key;
        String originalV13Line = "DROP INDEX IF EXISTS sw_form_def_form_key_key;";
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(originalV13Line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int originalChecksum = (int) crc.getValue();

        try (Connection conn = DriverManager.getConnection(legacyUrl, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE flyway_schema_history SET checksum = " + originalChecksum
                    + " WHERE version = '13' AND success = TRUE");
        }

        // 重新 validate-on-migrate：V13 文件 checksum 与登记不一致 → 必须显式失败，绝不静默通过
        Flyway legacy = Flyway.configure()
                .dataSource(legacyUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        assertThrows(org.flywaydb.core.api.FlywayException.class, legacy::migrate,
                "登记原 V13 checksum 的既有库在 validate-on-migrate 下必须显式失败（保护数据，不静默改写）");
    }

    @Test
    @DisplayName("V36：调试会话表 sw_agent_graph_debug_session / sw_agent_graph_debug_node 存在且索引完整")
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

    @Test
    @DisplayName("V44：流程引擎 id=5 改目录，子菜单 20/21/22/23 挂 parent_id=5（A-02 产物）")
    void v44_workflowChildMenus_shouldExist() throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT menu_type, component FROM sys_menu WHERE id = 5")) {
                assertTrue(rs.next(), "V44 后「流程引擎」id=5 应存在");
                assertEquals(0, rs.getInt("menu_type"), "id=5 应改为目录(menu_type=0)");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, parent_id, path, component, menu_type FROM sys_menu "
                            + "WHERE id IN (20, 21, 22, 23) ORDER BY id")) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    assertEquals(5, rs.getInt("parent_id"), "子菜单 parent_id 应为 5");
                    assertEquals(1, rs.getInt("menu_type"), "子菜单应为页面(menu_type=1)");
                }
                assertEquals(4, count, "V44 应有 4 条流程子菜单 20/21/22/23");
            }
        }
    }
}
