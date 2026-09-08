package com.sw.ck.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BPM 迁移链纳入真实 H2 全链 Flyway 验证的永久测试（不启动 Spring 上下文）。
 * <p>
 * 使用独立内存库 + 独立 Flyway 实例，8 个 locations 与 {@code application.yml}
 * 完全一致（{vendor} 按 H2 连接解析为 h2）。全链共 66 条迁移
 * （含 V35 Agent Token Usage）。
 * </p>
 * <p>
 * H2 不支持 PG 的 partial unique index，BPM V8 在 H2 侧用生成列
 * {@code active_key} + 唯一索引 {@code uk_sw_bpm_binding_active} 等价实现
 * 「同租户同 form_key 仅一条 active=true」约束。本测试除迁移计数/校验外，
 * 还用 JDBC 对绑定语义做正反例验证（H2 唯一约束冲突 SQLState=23505）。
 * </p>
 */
@DisplayName("BPM 全链 H2 Flyway 迁移 + 绑定语义验证")
class FlywayFullChainH2Test {

    private static final String URL = "jdbc:h2:mem:flyway_full_chain;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    /**
     * 与 application.yml flyway.locations 完全一致的 8 个位置。
     * <p>
     * 注意：{vendor} 占位符并非由 flyway-core 解析，而是 Spring Boot
     * {@code FlywayAutoConfiguration$LocationResolver} 按 JDBC 驱动替换
     * （flyway-core 11.3.4 实测不识别 {vendor}）。本测试不启动 Spring 上下文，
     * 故在 {@link #migrateFullChain()} 中按 H2 连接显式解析为 h2，
     * 目录结构与 application.yml 一一对应。
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
            "classpath:db/migration/iot/{vendor}"
    };

    private static Flyway flyway;

    @BeforeAll
    static void migrateFullChain() {
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        flyway = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "全链迁移应成功");
        assertEquals(66, result.migrationsExecuted,
                "全链迁移计数应为 66（P49=49 + P4 V50-V55 六条 + v0.0.2 V56-V58 三条 + P21 V59—V66 八条），实际: " + result.migrationsExecuted);
    }

    @Test
    @DisplayName("全链迁移后：info().applied() 共 55 条，包含 P58 通知渠道与流程节点能力迁移")
    void appliedMigrationCount_shouldBe35() {
        org.flywaydb.core.api.MigrationInfo[] applied = flyway.info().applied();
        assertEquals(66, applied.length, "已应用迁移数应为 66");
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
        flyway.validate();
    }

    @Test
    @DisplayName("P24 V31：admin seed 字段与 job/storage 显式权限完整")
    void adminSeed_shouldHaveStableRoleAndPermissions() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT name, code, status, data_scope, built_in FROM sys_role WHERE id = 2")) {
                assertTrue(rs.next(), "普通 admin 角色应存在");
                assertEquals("管理员", rs.getString("name"));
                assertEquals("admin", rs.getString("code"));
                assertEquals(1, rs.getInt("status"));
                assertEquals(0, rs.getInt("data_scope"));
                assertTrue(!rs.getBoolean("built_in"), "admin 不得标记为内置角色");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id "
                            + "WHERE rm.role_id = 2 AND m.permission IN "
                            + "('job:create','job:update','job:delete','job:pause','job:resume','job:trigger',"
                            + "'storage:upload','storage:delete','storage:download')")) {
                assertTrue(rs.next());
                assertEquals(9, rs.getInt(1), "admin 应显式拥有 job/storage 全部方法权限");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT code, built_in FROM sys_role WHERE id = 1")) {
                assertTrue(rs.next());
                assertEquals("superadmin", rs.getString("code"));
                assertTrue(rs.getBoolean("built_in"), "superadmin 应保持内置角色");
            }
        }
    }

    @Test
    @DisplayName("P24 V31：既有 admin/id=2 冲突必须显式失败")
    void adminSeedConflict_shouldFailExplicitly() throws SQLException {
        String conflictUrl = "jdbc:h2:mem:flyway_p24_conflict;DB_CLOSE_DELAY=-1";
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        Flyway beforeV31 = Flyway.configure()
                .dataSource(conflictUrl, USER, PASSWORD)
                .locations(locations)
                .target("30")
                .load();
        beforeV31.migrate();
        try (Connection conn = DriverManager.getConnection(conflictUrl, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version, "
                    + "name, code, sort, status, data_scope, built_in, remark) VALUES "
                    + "(2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, '既有角色', 'admin', 1, 1, 0, FALSE, 'collision')");
        }
        Flyway afterV31 = Flyway.configure()
                .dataSource(conflictUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        assertThrows(FlywayException.class, afterV31::migrate,
                "既有 admin/id=2 冲突不得静默跳过 V31");
    }

    @Test
    @DisplayName("V33/V34/V35/V37/V54：V32→链尾升级链（先至 V32 再全量）执行成功，且大模型菜单/按钮 seed 产物正确")
    void upgradeChain_V32_to_V35_shouldPass() throws SQLException {
        String upgradeUrl = "jdbc:h2:mem:flyway_upgrade_v35a;DB_CLOSE_DELAY=-1";
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        Flyway toV32 = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .target("32")
                .load();
        MigrateResult first = toV32.migrate();
        assertTrue(first.success, "先迁移至 V32 应成功");
        assertEquals(32, first.migrationsExecuted, "V32 阶段应执行 32 条，实际: " + first.migrationsExecuted);

        Flyway full = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult second = full.migrate();
        assertTrue(second.success, "V32→链尾升级链应成功");
        assertEquals(34, second.migrationsExecuted, "升级链应执行 V33-V66 三十四条，实际: " + second.migrationsExecuted);
        full.validate();

        try (Connection conn = DriverManager.getConnection(upgradeUrl, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT permission, component, path, menu_type, parent_id, sort FROM sys_menu WHERE id = 209")) {
                assertTrue(rs.next(), "V33 菜单 id=209（大模型管理）应存在");
                assertEquals("agent:model:view", rs.getString("permission"));
                assertEquals("agent/views/ModelList", rs.getString("component"));
                assertEquals("model", rs.getString("path"));
                assertEquals(1, rs.getInt("menu_type"));
                assertEquals(7, rs.getInt("parent_id"));
                assertTrue(rs.getInt("sort") > 15, "sort 应在图定义管理(15)之后");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT permission, menu_type, parent_id FROM sys_menu WHERE id IN (210, 211) ORDER BY id")) {
                assertTrue(rs.next(), "V33 按钮 id=210 应存在");
                assertEquals("agent:model:manage", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertEquals(209, rs.getInt("parent_id"));
                assertTrue(rs.next(), "V33 按钮 id=211 应存在");
                assertEquals("agent:model:test", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertEquals(209, rs.getInt("parent_id"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id "
                            + "WHERE m.id IN (209, 210, 211)")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V33 不得自动 seed sys_role_menu（V6/V26 决策沿用）");
            }
        }
    }

    @Test
    @DisplayName("V34/V35/V37/V54：V33→链尾升级链（先至 V33 再全量）执行成功，且用户组表/唯一约束产物正确")
    void upgradeChain_V33_to_V35_shouldPass() throws SQLException {
        String upgradeUrl = "jdbc:h2:mem:flyway_upgrade_v35;DB_CLOSE_DELAY=-1";
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        Flyway toV33 = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .target("33")
                .load();
        MigrateResult first = toV33.migrate();
        assertTrue(first.success, "先迁移至 V33 应成功");
        assertEquals(33, first.migrationsExecuted, "V33 阶段应执行 33 条，实际: " + first.migrationsExecuted);

        Flyway full = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult second = full.migrate();
        assertTrue(second.success, "V33→V36 升级链应成功");
        assertEquals(33, second.migrationsExecuted, "升级链应执行 V34-V66 三十三条，实际: " + second.migrationsExecuted);
        full.validate();

        try (Connection conn = DriverManager.getConnection(upgradeUrl, USER, PASSWORD)) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getTables(null, null, "SYS_USER_GROUP", new String[]{"TABLE"})) {
                assertTrue(rs.next(), "sys_user_group 表应存在");
            }
            try (ResultSet rs = md.getTables(null, null, "SYS_USER_GROUP_MEMBER", new String[]{"TABLE"})) {
                assertTrue(rs.next(), "sys_user_group_member 表应存在");
            }
            boolean ukFound = false;
            try (ResultSet rs = md.getIndexInfo(null, null, "SYS_USER_GROUP", false, false)) {
                while (rs.next()) {
                    if ("UK_SYS_USER_GROUP_CODE".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        ukFound = true;
                        break;
                    }
                }
            }
            assertTrue(ukFound, "唯一索引 uk_sys_user_group_code 应存在");
        }
    }

    @Test
    @DisplayName("V34：用户组逻辑删除唯一语义 —— 同租户同标识两条 deleted=0 冲突(23505)，deleted=1 历史可共存")
    void userGroupCode_uniqueSemantics() throws SQLException {
        String url = "jdbc:h2:mem:flyway_v34_semantics;DB_CLOSE_DELAY=-1";
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        Flyway.configure().dataSource(url, USER, PASSWORD).locations(locations).load().migrate();

        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sys_user_group (id, create_time, update_time, deleted, tenant_id, version, "
                    + "group_code, group_name, status, remark) VALUES "
                    + "(1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'G-001', '技术委员会', 0, NULL)");
            // 同租户同标识第二条 deleted=0 → 唯一冲突
            expectUniqueViolation(() -> stmt.executeUpdate(
                    "INSERT INTO sys_user_group (id, create_time, update_time, deleted, tenant_id, version, "
                            + "group_code, group_name, status, remark) VALUES "
                            + "(2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'G-001', '技术委员会B', 0, NULL)"));
            // 同租户同标识 deleted=1 历史可共存（稳定引用 + 逻辑删除唯一语义）
            stmt.executeUpdate("INSERT INTO sys_user_group (id, create_time, update_time, deleted, tenant_id, version, "
                    + "group_code, group_name, status, remark) VALUES "
                    + "(3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0, 0, 'G-001', '技术委员会-已删', 0, NULL)");
            // 不同租户同标识各自有效
            stmt.executeUpdate("INSERT INTO sys_user_group (id, create_time, update_time, deleted, tenant_id, version, "
                    + "group_code, group_name, status, remark) VALUES "
                    + "(4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 9, 0, 'G-001', '租户9技术委员会', 0, NULL)");
            // 成员表唯一：同组同用户两条 deleted=0 冲突
            stmt.executeUpdate("INSERT INTO sys_user_group_member (id, create_time, update_time, deleted, tenant_id, version, "
                    + "group_id, user_id) VALUES "
                    + "(1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 1, 100)");
            expectUniqueViolation(() -> stmt.executeUpdate(
                    "INSERT INTO sys_user_group_member (id, create_time, update_time, deleted, tenant_id, version, "
                            + "group_id, user_id) VALUES "
                            + "(2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 1, 100)"));
        }
    }

    @Test
    @DisplayName("sw_bpm_form_binding 表、uk_sw_bpm_binding_active 索引与 active_key 生成列存在")
    void bindingTableIndexAndGeneratedColumn_shouldExist() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
            DatabaseMetaData md = conn.getMetaData();

            try (ResultSet rs = md.getTables(null, null, "SW_BPM_FORM_BINDING", new String[]{"TABLE"})) {
                assertTrue(rs.next(), "sw_bpm_form_binding 表应存在");
            }

            boolean indexFound = false;
            try (ResultSet rs = md.getIndexInfo(null, null, "SW_BPM_FORM_BINDING", false, false)) {
                while (rs.next()) {
                    if ("UK_SW_BPM_BINDING_ACTIVE".equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                        indexFound = true;
                        break;
                    }
                }
            }
            assertTrue(indexFound, "唯一索引 uk_sw_bpm_binding_active 应存在");

            boolean columnFound = false;
            try (ResultSet rs = md.getColumns(null, null, "SW_BPM_FORM_BINDING", "ACTIVE_KEY")) {
                columnFound = rs.next();
            }
            assertTrue(columnFound, "生成列 active_key 应存在");
        }
    }

    @Test
    @DisplayName("正例：插入 active=true 成功；同租户同 form_key 第二条 active=true 冲突（SQLState=23505）")
    void duplicateActiveBinding_shouldFailWith23505() throws SQLException {
        insertBinding(1001L, 100L, "form_chain", "def_chain", true);

        expectUniqueViolation(() -> insertBinding(1002L, 100L, "form_chain", "def_chain2", true));
    }

    @Test
    @DisplayName("正例：同租户同 form_key 多条 active=false 历史记录可共存")
    void multipleInactiveBindings_shouldCoexist() throws SQLException {
        insertBinding(2001L, 200L, "form_history", "def_old1", false);
        insertBinding(2002L, 200L, "form_history", "def_old2", false);
        insertBinding(2003L, 200L, "form_history", "def_old3", false);

        assertEquals(3, countRows(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE tenant_id = 200 AND form_key = 'form_history'"));
    }

    @Test
    @DisplayName("正例：不同 tenant_id 同 form_key 各一条 active=true 可共存（租户隔离）")
    void activeBindings_differentTenants_shouldCoexist() throws SQLException {
        insertBinding(3001L, 301L, "form_tenant", "def_chain", true);
        insertBinding(3002L, 302L, "form_tenant", "def_chain", true);
        insertBinding(3003L, 303L, "form_tenant", "def_chain", true);

        assertEquals(3, countRows(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE active = true AND form_key = 'form_tenant'"));
    }

    @Test
    @DisplayName("正例：先停用 active=true→false，再插入新 active=true 成功（启停切换）")
    void deactivateThenInsertNewActive_shouldSucceed() throws SQLException {
        insertBinding(4001L, 400L, "form_switch", "def_a", true);
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE sw_bpm_form_binding SET active = false WHERE id = 4001");
        }

        insertBinding(4002L, 400L, "form_switch", "def_b", true);

        assertEquals(1, countRows(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE tenant_id = 400 AND form_key = 'form_switch' AND active = true"));
    }

    @Test
    @DisplayName("反例：已有 active=true 时，把另一条 active=false 更新为 true 被拒绝（SQLState=23505）")
    void updateInactiveToActive_withExistingActive_shouldFail() throws SQLException {
        insertBinding(5001L, 500L, "form_update", "def_a", false);
        insertBinding(5002L, 500L, "form_update", "def_b", true);

        expectUniqueViolation(() -> {
            try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("UPDATE sw_bpm_form_binding SET active = true WHERE id = 5001");
            }
        });
    }

    @Test
    @DisplayName("V44：流程引擎 id=5 改目录，子菜单 20/21/22/23 挂 parent_id=5（A-02 产物）")
    void v44_workflowChildMenus_shouldExist() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
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

    // ==================== 辅助方法 ====================

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    /** 断言唯一约束冲突：H2 唯一索引冲突 SQLState 为 23505，用 try/catch 显式捕获断言。 */
    private void expectUniqueViolation(SqlRunnable action) throws SQLException {
        try {
            action.run();
            fail("预期唯一约束冲突，但语句执行成功");
        } catch (SQLException e) {
            assertEquals("23505", e.getSQLState(),
                    "唯一约束冲突 SQLState 应为 23505，实际: " + e.getSQLState() + "，消息: " + e.getMessage());
        }
    }

    private void insertBinding(Long id, Long tenantId, String formKey, String processDefKey, boolean active)
            throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sw_bpm_form_binding (id, tenant_id, form_key, process_def_key, active) VALUES ("
                    + id + ", " + tenantId + ", '" + formKey + "', '" + processDefKey + "', " + active + ")");
        }
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ==================== L10：独立 V36 起点 → 仅迁移 V37（D197 审查 L10） ====================

    @Test
    @DisplayName("L10: 独立 V36 现有库 → 迁移至链尾（V37-V61），同一会话查询批量发送页面/按钮权限")
    void upgrade_V36_to_V37_only_and_query() throws SQLException {
        String upgradeUrl = "jdbc:h2:mem:flyway_l10_v36;DB_CLOSE_DELAY=-1";
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);

        // 1. 建立真实 V36 起点：先迁移至 V36（当前版本 V36）
        long startNanos = System.nanoTime();
        Flyway toV36 = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .target("36")
                .load();
        MigrateResult first = toV36.migrate();
        assertTrue(first.success, "V1→V36 应成功");
        assertEquals(36, first.migrationsExecuted, "V36 阶段应执行 36 条，实际: " + first.migrationsExecuted);

        // 2. 输出起点当前版本 V36（info().current()）
        org.flywaydb.core.api.MigrationInfoService infoBefore = toV36.info();
        String beforeVersion = infoBefore.current() == null ? "EMPTY" : infoBefore.current().getVersion().getVersion();
        assertEquals("36", beforeVersion, "起点当前版本应为 V36，实际: " + beforeVersion);


        // 3. 只迁移到链尾（不再 target），应执行 V37-V61 二十五条
        Flyway toV37 = Flyway.configure()
                .dataSource(upgradeUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult second = toV37.migrate();
        assertTrue(second.success, "V36→链尾 应成功");
        assertEquals(30, second.migrationsExecuted, "V36→链尾 应执行 V37-V66 三十条，实际: " + second.migrationsExecuted);
        org.flywaydb.core.api.MigrationInfoService infoAfter = toV37.info();
        String afterVersion = infoAfter.current() == null ? "EMPTY" : infoAfter.current().getVersion().getVersion();
        assertEquals("66", afterVersion, "终点当前版本应为 V66，实际: " + afterVersion);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // 4. 同一数据库会话实际查询：页面/按钮行的 id,parent_id,path,component,permission + view/manage
        try (Connection conn = DriverManager.getConnection(upgradeUrl, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, parent_id, path, component, permission, menu_type FROM sys_menu WHERE id IN (212, 213) ORDER BY id")) {
                assertTrue(rs.next(), "页面菜单 id=212 应存在");
                assertEquals(7, rs.getInt("parent_id"));
                assertEquals("tool", rs.getString("path"));
                assertEquals("agent/views/ToolList", rs.getString("component"));
                assertEquals("agent:tool:view", rs.getString("permission"));
                assertEquals(1, rs.getInt("menu_type"));
                assertTrue(rs.next(), "按钮菜单 id=213 应存在");
                assertEquals(212, rs.getInt("parent_id"));
                assertEquals("agent:tool:manage", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertFalse(rs.next(), "不应有第三行");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT menu_type, component FROM sys_menu WHERE id = 6")) {
                assertTrue(rs.next(), "V38 后「通知」目录 id=6 应存在");
                assertEquals(0, rs.getInt("menu_type"), "id=6 应矫正为目录(menu_type=0)");
            }
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
            stmt.executeUpdate("INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id, version, role_id, menu_id) VALUES (900001, current_timestamp, current_timestamp, 0, 0, 0, 2, 219)");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT r.id, r.code, r.built_in, m.permission FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id JOIN sys_menu m ON m.id = rm.menu_id WHERE rm.role_id = 2 AND rm.menu_id = 219 AND rm.deleted = 0")) {
                assertTrue(rs.next(), "普通 admin 角色应能绑定批量发送按钮权限");
                assertEquals(2, rs.getInt("id"));
                assertEquals("admin", rs.getString("code"));
                assertFalse(rs.getBoolean("built_in"));
                assertEquals("notify:batch:send", rs.getString("permission"));
                assertFalse(rs.next(), "普通角色绑定应只有一条有效关系");
            }
            System.out.println("[S3-production] H2 V39 menu=(218,batch-send,notify/views/NotifyBatchSend,notify:batch:send), button=(219,notify:batch:send), ordinaryRole=(id=2,code=admin,built_in=false) boundMenu=219, queryExit=0");
        }

        System.out.println("[L10] V36→链尾 独立升级: 起点=" + beforeVersion + ", 终点=" + afterVersion
                + ", 执行迁移数=" + second.migrationsExecuted + ", 耗时=" + elapsedMs + "ms, 查询退出=0");
    }
}
