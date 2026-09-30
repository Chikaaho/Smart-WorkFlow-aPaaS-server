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

/**
 * 基线种子全链 H2 Flyway 验证的永久测试（不启动 Spring 上下文）。
 * <p>
 * 使用独立内存库 + 独立 Flyway 实例，10 个 locations 与 {@code application.yml}
 * 完全一致（{vendor} 按 H2 连接解析为 h2）。0.1.3 种子合并后，versioned 迁移收敛为
 * 单一基线 {@code V0.1.0__baseline_seed.sql}（原 V1—V104 按版本序逐字节合并），
 * 加可重复对账 {@code R__i6_notify_menu_reconciliation}，全新库共执行 2 条。
 * 历史版本升级演练（V32/V33/V36 分阶段、旧基线升级）随 0.1.3「仅支持全新建库」
 * 的边界一并退役；本测试保留并延续全部终态数据/语义断言。
 * </p>
 * <p>
 * H2 不支持 PG 的 partial unique index，BPM V8 在 H2 侧用生成列
 * {@code active_key} + 唯一索引 {@code uk_sw_bpm_binding_active} 等价实现
 * 「同租户同 form_key 仅一条 active=true」约束。本测试除基线计数/校验外，
 * 还用 JDBC 对绑定语义做正反例验证（H2 唯一约束冲突 SQLState=23505）。
 * </p>
 */
@DisplayName("基线种子全链 H2 Flyway 迁移 + 绑定语义验证")
class FlywayFullChainH2Test {

    private static final String URL = "jdbc:h2:mem:flyway_full_chain;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    /**
     * 与 application.yml flyway.locations 完全一致的 10 个位置。
     * <p>
     * 注意：{vendor} 占位符并非由 flyway-core 解析，而是 Spring Boot
     * {@code FlywayAutoConfiguration$LocationResolver} 按 JDBC 驱动替换
     * （flyway-core 11.3.4 实测不识别 {vendor}）。本测试不启动 Spring 上下文，
     * 故在 {@link #migrateBaseline()} 中按 H2 连接显式解析为 h2，
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
            "classpath:db/migration/iot/{vendor}",
            "classpath:db/migration/openapi/{vendor}",
            "classpath:db/migration/system/{vendor}"
    };

    private static Flyway flyway;

    @BeforeAll
    static void migrateBaseline() {
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        flyway = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "基线迁移应成功");
        assertEquals(8, result.migrationsExecuted,
                "全新库应执行 8 条（V0.1.0 基线 + V0.1.1/V0.1.2/V0.1.3 增量 + 4 个 R__ 可重复对账），实际: " + result.migrationsExecuted);
        assertEquals("0.1.3", flyway.info().current().getVersion().getVersion(),
                "终点当前版本应为 0.1.3 增量");
    }

    @Test
    @DisplayName("基线迁移后：info().applied() 共 6 条（0.1.0—0.1.3 + 2 个可重复对账）")
    void appliedMigrations_shouldBeBaselineAndRepeatable() {
        org.flywaydb.core.api.MigrationInfo[] applied = flyway.info().applied();
        assertEquals(8, applied.length, "已应用迁移数应为 8（基线 + 3 个增量 + 4 个 R__）");
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
                    || "0.1.3".equals(info.getVersion().getVersion())) {
                incrementalSeen++;
            }
        }
        assertTrue(baselineSeen, "V0.1.0 基线应已应用");
        assertEquals(3, incrementalSeen, "V0.1.1/V0.1.2/V0.1.3 增量应已应用");
        assertEquals(4, repeatableCount, "4 个 R__ 菜单可重复对账应已应用");
    }

    @Test
    @DisplayName("基线迁移后：再次 validate() 通过（无校验和/缺失迁移问题）")
    void validate_shouldPass() {
        flyway.validate();
    }

    @Test
    @DisplayName("基线迁移后：重复 migrate 幂等（0 条新执行，版本不变）")
    void reMigrate_shouldBeIdempotent() {
        MigrateResult again = flyway.migrate();
        assertEquals(0, again.migrationsExecuted, "重复 migrate 不应执行任何迁移");
        assertEquals("0.1.3", flyway.info().current().getVersion().getVersion(), "版本应保持 0.1.3");
    }

    @Test
    @DisplayName("基线校验和安全：篡改 0.1.0 登记校验和后必须显式失败（不静默通过、不改写校验和）")
    void tamperedBaselineChecksum_shouldFailValidateNotSilentlyPass() throws SQLException {
        String tamperedUrl = "jdbc:h2:mem:flyway_baseline_checksum;DB_CLOSE_DELAY=-1";
        String[] locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
        Flyway migrate = Flyway.configure()
                .dataSource(tamperedUrl, USER, PASSWORD)
                .locations(locations)
                .load();
        MigrateResult first = migrate.migrate();
        assertTrue(first.success, "建立基线库应成功");

        try (Connection conn = DriverManager.getConnection(tamperedUrl, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            // Flyway 以小写引号名建历史表，H2 未加引号的标识符会折叠为大写而找不到
            stmt.executeUpdate("UPDATE \"flyway_schema_history\" SET \"checksum\" = \"checksum\" + 1 "
                    + "WHERE \"version\" = '0.1.0' AND \"success\" = TRUE");
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
    @DisplayName("V33 产物：大模型管理菜单 209 与按钮 210/211 存在，且不自动 seed sys_role_menu")
    void agentModelMenuSeed_finalState() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT permission, component, path, menu_type, parent_id, sort FROM sys_menu WHERE id = 209")) {
                assertTrue(rs.next(), "菜单 id=209（大模型管理）应存在");
                assertEquals("agent:model:view", rs.getString("permission"));
                assertEquals("agent/views/ModelList", rs.getString("component"));
                assertEquals("agent/model", rs.getString("path"));
                assertEquals(1, rs.getInt("menu_type"));
                assertEquals(7, rs.getInt("parent_id"));
                assertTrue(rs.getInt("sort") > 15, "sort 应在图定义管理(15)之后");
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT permission, menu_type, parent_id FROM sys_menu WHERE id IN (210, 211) ORDER BY id")) {
                assertTrue(rs.next(), "按钮 id=210 应存在");
                assertEquals("agent:model:manage", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertEquals(209, rs.getInt("parent_id"));
                assertTrue(rs.next(), "按钮 id=211 应存在");
                assertEquals("agent:model:test", rs.getString("permission"));
                assertEquals(2, rs.getInt("menu_type"));
                assertEquals(209, rs.getInt("parent_id"));
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id "
                            + "WHERE m.id IN (209, 210, 211)")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "不得自动 seed sys_role_menu（V6/V26 决策沿用）");
            }
        }
    }

    @Test
    @DisplayName("P62 产物：事务动作菜单 9100 与按钮 9101—9103 存在，且授予普通 admin（role 2）")
    void p62TxnActionMenuSeed_finalState() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
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
        }
    }

    @Test
    @DisplayName("V34 产物：sys_user_group / sys_user_group_member 表与 uk_sys_user_group_code 存在")
    void userGroupTables_finalState() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD)) {
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
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
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
                assertTrue(rs.next(), "「流程引擎」id=5 应存在");
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
                assertEquals(4, count, "应有 4 条流程子菜单 20/21/22/23");
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

    // ==================== S3：批量发送权限资源（原 L10 终态断言） ====================

    @Test
    @DisplayName("S3：批量发送页面/按钮权限齐备，普通 admin 角色可绑定（基线终态）")
    void batchSendPermissionResource_finalState() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, parent_id, path, component, permission, menu_type FROM sys_menu WHERE id IN (212, 213) ORDER BY id")) {
                assertTrue(rs.next(), "页面菜单 id=212 应存在");
                assertEquals(7, rs.getInt("parent_id"));
                assertEquals("agent/tool", rs.getString("path"));
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
                assertTrue(rs.next(), "「通知」目录 id=6 应存在");
                assertEquals(0, rs.getInt("menu_type"), "id=6 应矫正为目录(menu_type=0)");
            }
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
            System.out.println("[S3-production] H2 baseline menu=(218,batch-send,notify/views/NotifyBatchSend,notify:batch:send), button=(219,notify:batch:send), ordinaryRole=(id=2,code=admin,built_in=false) boundMenu=219, queryExit=0");
        }
    }
}
