package com.sw.ck.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用中心（表单分类统一）存量承接迁移验证。
 * <p>
 * 用同一个 H2 内存库先迁移到 0.1.5（不含应用中心增量迁移），插入代表性存量夹具
 * （分类 / 流程事项 / 表单，含跨租户与多归属歧义），再应用
 * {@code V20261015190000__application_center.sql}，逐对象断言承接规则：
 * </p>
 * <ol>
 *   <li>每个有效分类承接为一个应用（id = lc&lt;categoryId&gt;，可追溯映射，可见范围 ALL）；</li>
 *   <li>唯一分类表单归属该分类承接应用；</li>
 *   <li>分类缺失（含跨租户事项导致的同租户零分类）表单归本租户「未分类」承接应用；</li>
 *   <li>跨分类共用表单（多归属歧义）不自动转换，app_id 保持 NULL（保留原可用路径，交冲突清单裁决）；</li>
 *   <li>已删除表单不承接；</li>
 *   <li>后台应用管理 / 用户端应用中心菜单与授权就位。</li>
 * </ol>
 */
@DisplayName("应用中心存量承接迁移（H2 全链）")
class ApplicationCenterLegacyUpgradeTest {

    private static final String URL = "jdbc:h2:mem:app_center_legacy;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

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

    private static String[] h2Locations() {
        return Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
    }

    @BeforeAll
    static void prepareLegacyAndMigrate() throws SQLException {
        // —— 1. 迁移到 0.1.5 基线（target 不含应用中心增量迁移）——
        Flyway baseline = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2Locations())
                .target(MigrationVersion.fromVersion("0.1.5"))
                .load();
        MigrateResult base = baseline.migrate();
        assertTrue(base.success, "0.1.5 基线迁移应成功");

        // —— 2. 代表存量夹具 ——
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            // 分类：租户 0 两个、租户 100 一个
            stmt.executeUpdate("INSERT INTO sw_bpm_category (id, tenant_id, name, sort_no, deleted, version) "
                    + "VALUES (101, 0, '人事', 1, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_bpm_category (id, tenant_id, name, sort_no, deleted, version) "
                    + "VALUES (102, 0, '财务', 2, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_bpm_category (id, tenant_id, name, sort_no, deleted, version) "
                    + "VALUES (103, 100, '行政租户100', 1, 0, 0)");
            // 事项：ka→101；kb 两条均为 102；kd 跨 101/102；ke 分类为空；kt(租户100)→103；
            // kx 事项在租户 100（对租户 0 的 kx 表单构成跨租户异常，不应参与其判定）
            insertProcessDef(stmt, 1001, "pk_a", "ka", 101, 0);
            insertProcessDef(stmt, 1002, "pk_b1", "kb", 102, 0);
            insertProcessDef(stmt, 1003, "pk_b2", "kb", 102, 0);
            insertProcessDef(stmt, 1004, "pk_d1", "kd", 101, 0);
            insertProcessDef(stmt, 1005, "pk_d2", "kd", 102, 0);
            insertProcessDef(stmt, 1006, "pk_e", "ke", null, 0);
            insertProcessDef(stmt, 1007, "pk_t", "kt", 103, 100);
            insertProcessDef(stmt, 1008, "pk_x100", "kx", null, 100);
            // 表单：fa(唯一分类101)、fb(唯一分类102)、fc(无事项)、fd(歧义)、fe(零有效分类)、
            // fx(只有跨租户事项)、ft(租户100)、fh(已删除)
            insertFormDef(stmt, "fa", "ka", "表单A", 0, 0);
            insertFormDef(stmt, "fb", "kb", "表单B", 0, 0);
            insertFormDef(stmt, "fc", "kc", "表单C", 0, 0);
            insertFormDef(stmt, "fd", "kd", "表单D", 0, 0);
            insertFormDef(stmt, "fe", "ke", "表单E", 0, 0);
            insertFormDef(stmt, "fx", "kx", "表单X", 0, 0);
            insertFormDef(stmt, "ft", "kt", "表单T", 100, 0);
            insertFormDef(stmt, "fh", "kh", "表单历史", 0, 1);
        }

        // —— 3. 应用应用中心增量迁移 ——
        Flyway upgrade = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2Locations())
                .load();
        MigrateResult result = upgrade.migrate();
        assertTrue(result.success, "应用中心增量迁移应成功");
    }

    private static void insertProcessDef(Statement stmt, long id, String key, String formKey,
                                         Integer categoryId, long tenantId) throws SQLException {
        stmt.executeUpdate("INSERT INTO sw_bpm_process_def (id, process_key, name, form_key, category_id, "
                + "tenant_id, deleted, version) VALUES (" + id + ", '" + key + "', '" + key + "', '" + formKey
                + "', " + (categoryId == null ? "NULL" : categoryId) + ", " + tenantId + ", 0, 0)");
    }

    private static void insertFormDef(Statement stmt, String id, String formKey, String name,
                                      long tenantId, int deleted) throws SQLException {
        stmt.executeUpdate("INSERT INTO sw_form_def (id, form_key, name, status, form_version, "
                + "tenant_id, deleted, version) VALUES ('" + id + "', '" + formKey + "', '" + name
                + "', 'DRAFT', 1, " + tenantId + ", " + deleted + ", 0)");
    }

    private String queryString(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行: " + sql);
            return rs.getString(1);
        }
    }

    private long queryLong(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行: " + sql);
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("分类承接：每个有效分类一个应用，保留名称/排序/追溯映射，可见范围 ALL")
    void categoriesAreAdoptedAsApps() throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT tenant_id, name, sort_no, visible_scope_type, status, source, legacy_category_id "
                             + "FROM sw_form_app WHERE id = 'lc101'")) {
            assertTrue(rs.next(), "分类 101 应承接为应用 lc101");
            assertEquals(0L, rs.getLong("tenant_id"));
            assertEquals("人事", rs.getString("name"));
            assertEquals(1, rs.getInt("sort_no"));
            assertEquals("ALL", rs.getString("visible_scope_type"), "承接应用可见范围应为 ALL（保留既有访问）");
            assertEquals("ENABLED", rs.getString("status"));
            assertEquals("LEGACY_CATEGORY", rs.getString("source"));
            assertEquals(101L, rs.getLong("legacy_category_id"));
        }
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM sw_form_app WHERE id = 'lc103' AND tenant_id = 100"));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM sw_form_app WHERE source = 'LEGACY_CATEGORY' AND tenant_id = 0"));
    }

    @Test
    @DisplayName("表单归属：唯一分类归分类应用；零分类归未分类应用；已删除不承接")
    void formsAreAdoptedByDeterministicRules() throws SQLException {
        assertEquals("lc101", queryString("SELECT app_id FROM sw_form_def WHERE id = 'fa'"));
        assertEquals("lc102", queryString("SELECT app_id FROM sw_form_def WHERE id = 'fb'"));
        assertEquals("uncat-t0", queryString("SELECT app_id FROM sw_form_def WHERE id = 'fc'"));
        assertEquals("uncat-t0", queryString("SELECT app_id FROM sw_form_def WHERE id = 'fe'"));
        assertEquals("lc103", queryString("SELECT app_id FROM sw_form_def WHERE id = 'ft'"));
        // 已删除表单不承接
        assertNull(queryString("SELECT app_id FROM sw_form_def WHERE id = 'fh'"));
    }

    @Test
    @DisplayName("多归属歧义与跨租户异常：不自动转换（app_id 保持 NULL），保留原可用路径")
    void ambiguousFormsAreNotSilentlyAdopted() throws SQLException {
        // 跨分类共用表单（kd 关联两个不同分类）→ 歧义对象不转换
        assertNull(queryString("SELECT app_id FROM sw_form_def WHERE id = 'fd'"),
                "跨分类共用表单不得任选分类归属，应保持 NULL 待裁决");
        // 跨租户事项（kx 事项在租户 100）不参与租户 0 表单判定 → 归未分类
        assertEquals("uncat-t0", queryString("SELECT app_id FROM sw_form_def WHERE id = 'fx'"),
                "他租户事项不得参与本租户表单的分类判定");
    }

    @Test
    @DisplayName("未分类承接应用：仅在该租户存在待归入表单时创建，名称与来源固定")
    void uncategorizedAppCreatedOnDemand() throws SQLException {
        assertEquals("未分类", queryString("SELECT name FROM sw_form_app WHERE id = 'uncat-t0'"));
        assertEquals("LEGACY_UNCATEGORIZED", queryString("SELECT source FROM sw_form_app WHERE id = 'uncat-t0'"));
        // 租户 100 的表单有唯一分类，无需创建未分类应用
        assertFalse(queryLong("SELECT COUNT(*) FROM sw_form_app WHERE id = 'uncat-t100'") > 0,
                "租户 100 无待归入表单，不应创建未分类应用");
    }

    @Test
    @DisplayName("新建表与菜单：三张应用表、表单归属列、后台/用户端菜单与授权就位")
    void schemaAndMenusAreInPlace() throws SQLException {
        // 存量承接不创建目录/指定用户集合（新概念无存量对应）
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM sw_form_app_dir WHERE deleted = 0"));
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM sw_form_app_user"));
        // 归属列存在（查询不报错即列存在）
        queryLong("SELECT COUNT(*) FROM sw_form_def WHERE app_id IS NOT NULL OR dir_id IS NULL");
        // 后台应用管理菜单 + 用户端应用中心菜单
        assertEquals("form:app:manage", queryString("SELECT permission FROM sys_menu WHERE id = 9130"));
        assertEquals("form/views/AppManageList", queryString("SELECT component FROM sys_menu WHERE id = 9130"));
        assertEquals("form/app-center", queryString("SELECT path FROM sys_menu WHERE id = 9131"));
        // 授权：管理员 role 2 与系统管理员 role 1
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 9130 AND role_id IN (1,2)"));
        assertEquals(2L, queryLong("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 9131 AND role_id IN (1,2)"));
    }

    @Test
    @DisplayName("重复执行适用性：迁移登记后再次 migrate 零执行、validate 通过")
    void idempotentAfterUpgrade() {
        Flyway again = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2Locations())
                .load();
        MigrateResult result = again.migrate();
        assertEquals(0, result.migrationsExecuted, "重复 migrate 不应执行任何迁移");
        assertEquals("20261015190000", again.info().current().getVersion().getVersion(),
                "终点应为应用中心迁移版本");
        again.validate();
    }
}
