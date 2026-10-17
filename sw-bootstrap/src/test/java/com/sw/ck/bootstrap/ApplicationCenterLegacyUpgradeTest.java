package com.sw.ck.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * 应用中心（表单分类统一）存量承接迁移验证（0.1.6→0.1.7 升级通道口径）。
 * <p>
 * 用同一个 H2 内存库先迁移到 0.1.5 边界（升级通道 legacy 资源 + target，不含应用中心增量），
 * 插入代表性存量夹具（分类 / 流程事项 / 表单，含跨租户与多归属歧义），再执行
 * 0.1.6 存量升级脚本 {@code upgrade-to-0.1.6-from-0.1.5.h2.sql}（应用中心增量 DDL/承接
 * 逐字节嵌入 + 历史衔接）与 0.1.7 存量升级脚本
 * {@code upgrade-to-0.1.7-from-0.1.6.h2.sql}（0.1.7 期增量 + 历史衔接），
 * 最后以主链（V0.1.7 种子 + R__）验证零 versioned 重放与 validate 通过。逐对象断言承接规则：
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
@DisplayName("应用中心存量承接（0.1.6→0.1.7 升级通道，H2）")
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

    /** 基线阶段位置：主链位置 + 0.1.5 边界来源（升级通道 legacy 资源，仅基线阶段使用）。 */
    private static String[] h2BaseLocations() {
        String[] main = h2Locations();
        String[] all = Arrays.copyOf(main, main.length + 1);
        all[main.length] = "classpath:db/upgrade/0.1.6/legacy-0.1.5/h2";
        return all;
    }

    private static String[] h2Locations() {
        return Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "h2"))
                .toArray(String[]::new);
    }

    @BeforeAll
    static void prepareLegacyAndMigrate() throws SQLException {
        // —— 1. 迁移到 0.1.5 边界（target=0.1.5：只应用遗留 V0.1.5 种子，排除增量与 0.1.6/0.1.7 种子）——
        Flyway baseline = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2BaseLocations())
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

        // —— 3. 执行 0.1.6 存量升级脚本（应用中心增量 DDL/承接 + 历史衔接，发布资产原文）——
        execScript(classpathResource(UPGRADE_SCRIPT));

        // —— 3.0 执行 0.1.7 存量升级脚本（0.1.7 期增量 + 历史衔接，发布资产原文）——
        execScript(classpathResource(UPGRADE_017_SCRIPT));

        // —— 3.1 主链衔接验证：零 versioned 重放、0.1.7 登记校验和与发布种子一致（validate 通过）——
        Flyway upgrade = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2Locations())
                .load();
        MigrateResult result = upgrade.migrate();
        assertTrue(result.success, "主链衔接验证应成功");
        assertEquals(0, result.migrationsExecuted,
                "升级脚本后主链不应重复执行任何迁移（R__ 已在基线阶段应用）");
        upgrade.validate();
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

    /** 现行 12 个 R__（复制到临时目录，构造不含主链 V0.1.7 种子的存量基线位置）。 */
    private static final String[] REPEATABLES = {
            "R__0.1.7_catalog_menu_first.sql", "R__0.1.7_org_demo_seed.sql",
            "R__application_center_menu.sql", "R__bf20261013_catalog_admin_menu.sql",
            "R__i6_notify_menu_reconciliation.sql", "R__p62_form_txn_action_menu.sql",
            "R__p62_iot_command_verify_menu.sql", "R__p62_resource_ops_menu.sql",
            "R__p62_txn_batch_menu.sql", "R__p63_iot_reservation_menu.sql",
            "R__p64_approval_form_menu.sql", "R__p64_position_delegate_menu.sql"};

    @Test
    @DisplayName("已应用增量形态衔接：0.1.5 + 应用中心增量库 → 0.1.6 历史衔接脚本 → 主链零重放/validate 通过")
    void appliedIncrementHistoryTransitionTo016() throws Exception {
        String url = "jdbc:h2:mem:app_center_hist_016;DB_CLOSE_DELAY=-1";

        // 1) 0.1.5 + 应用中心增量形态（模拟已执行时间戳 20261015190000 的库：
        //    legacy 版本化资源 + 现行 R__ 临时目录，不含主链 V0.1.7 种子）
        Path rDir = Files.createTempDirectory("ac016-repeatables-");
        for (String r : REPEATABLES) {
            try (var in = ApplicationCenterLegacyUpgradeTest.class.getClassLoader()
                    .getResourceAsStream("db/migration/h2/" + r)) {
                assertTrue(in != null, "缺失可重复迁移: " + r);
                Files.write(rDir.resolve(r), in.readAllBytes());
            }
        }
        MigrateResult applied = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations("classpath:db/upgrade/0.1.6/legacy-0.1.5/h2",
                        "filesystem:" + rDir.toAbsolutePath())
                .load().migrate();
        assertTrue(applied.success, "0.1.5+增量形态迁移应成功");

        // 2) 0.1.6 历史衔接脚本（发布资产原文）
        execScriptOn(url, classpathResource(TRANSITION_016_SCRIPT));

        // 2.1) 0.1.7 存量升级脚本（0.1.7 期增量 + 历史衔接，发布资产原文）
        execScriptOn(url, classpathResource(UPGRADE_017_SCRIPT));

        // 3) 主链零重放 + validate 通过 + 终点 0.1.7
        Flyway after = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(h2Locations())
                .load();
        after.validate();
        MigrateResult again = after.migrate();
        assertEquals(0, again.migrationsExecuted, "衔接后主链不应重复执行任何迁移");
        assertEquals("0.1.7", after.info().current().getVersion().getVersion(), "终点应为 0.1.7");
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
        assertEquals("0.1.7", again.info().current().getVersion().getVersion(),
                "终点应为 0.1.7 种子版本");
        again.validate();
    }

    // ==================== 0.1.6 升级通道脚本执行 ====================

    /** 0.1.6 存量升级脚本（发布资产原文，classpath 读取，测试即验证交付物本身）。 */
    private static final String UPGRADE_SCRIPT = "db/upgrade/0.1.6/upgrade-to-0.1.6-from-0.1.5.h2.sql";

    /** 0.1.6 历史衔接脚本（已应用增量形态，发布资产原文）。 */
    private static final String TRANSITION_016_SCRIPT = "db/upgrade/0.1.6/transition-history-to-0.1.6.h2.sql";

    /** 0.1.7 存量升级脚本（发布资产原文，classpath 读取，测试即验证交付物本身）。 */
    private static final String UPGRADE_017_SCRIPT = "db/upgrade/0.1.7/upgrade-to-0.1.7-from-0.1.6.h2.sql";

    private static String classpathResource(String resource) throws SQLException {
        try (var in = ApplicationCenterLegacyUpgradeTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("发布资产缺失: " + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new SQLException("读取发布资产失败: " + resource, e);
        }
    }

    private static void execScript(String script) throws SQLException {
        execScriptOn(URL, script);
    }

    private static void execScriptOn(String url, String script) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            for (String raw : script.split(";")) {
                String sql = stripComments(raw);
                if (!sql.isBlank()) {
                    stmt.execute(sql);
                }
            }
        }
    }

    private static String stripComments(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (!line.strip().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
