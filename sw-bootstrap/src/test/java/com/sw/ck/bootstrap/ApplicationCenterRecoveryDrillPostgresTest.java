package com.sw.ck.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * G4 恢复边界演练（真实 PostgreSQL）：按已声明恢复路径
 * “升级前 {@code pg_dump} 备份 → 升级 → 失败即从备份恢复”，在隔离库上实际走到声明边界，
 * 并验证恢复后数据不丢、读取链与写入链可用。
 * <p>
 * 步骤与断言：
 * <ol>
 *   <li>隔离库迁移到 0.1.5 边界（升级通道 legacy 资源 + target=0.1.5），写入存量业务对象；</li>
 *   <li>{@code pg_dump -Fc} 全库备份（升级前快照）；</li>
 *   <li>执行 0.1.6 存量升级脚本（应用中心增量 + 历史衔接）与 0.1.7 存量升级脚本（0.1.7 期增量
 *       + 历史衔接）→ 新表/新列/菜单就位、主链零重放；</li>
 *   <li>模拟失败恢复：删库 → {@code pg_restore} 恢复备份 → 断言回到 0.1.5 边界：
 *       增量表不存在、存量对象逐项在、值未变；</li>
 *   <li>恢复后可用性：既有读取（查询存量记录）与写入（插入新记录/更新值）均成功。</li>
 * </ol>
 * 本演练不触碰 V94 旧链库与 UAT；数据库为本次演练新建的隔离库，用完即删。
 * 未就绪环境（无本机 PostgreSQL / 无 pg_dump、pg_restore）以 skip 处理并说明。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("应用中心恢复边界演练（真实 PG：备份→升级→恢复→读写可用）")
class ApplicationCenterRecoveryDrillPostgresTest {

    private static final String DB_NAME = "ac_g4_recovery_drill";
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
    private static String[] pgBaseLocations() {
        String[] main = pgLocations();
        String[] all = Arrays.copyOf(main, main.length + 1);
        all[main.length] = "classpath:db/upgrade/0.1.6/legacy-0.1.5/postgresql";
        return all;
    }

    /** 0.1.6 存量升级脚本（发布资产原文，classpath 读取）。 */
    private static final String UPGRADE_SCRIPT = "db/upgrade/0.1.6/upgrade-to-0.1.6-from-0.1.5.postgresql.sql";

    /** 0.1.7 存量升级脚本（发布资产原文，classpath 读取）。 */
    private static final String UPGRADE_017_SCRIPT = "db/upgrade/0.1.7/upgrade-to-0.1.7-from-0.1.6.postgresql.sql";

    private static final String RECORD_TABLE = "sw_form_g4_drill";
    private static final String RECORD_ID = "rec-g4-1";

    private String serverUrl;
    private String dbUser;
    private String dbPassword;
    private String dbUrl;

    private static String[] pgLocations() {
        return Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "postgresql"))
                .toArray(String[]::new);
    }

    @Test
    @DisplayName("备份→升级→恢复到已声明边界：增量表消失、存量对象逐项在、读写链可用")
    void backupUpgradeRestoreToDeclaredBoundary() throws Exception {
        probeConnection();
        requireTooling();
        recreateDatabase();

        // —— 1. 隔离库 → 0.1.5 边界（target=0.1.5：只应用遗留 V0.1.5 种子）+ 存量对象 ——
        MigrateResult base = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(pgBaseLocations())
                .target(MigrationVersion.fromVersion("0.1.5"))
                .load().migrate();
        assertTrue(base.success, "0.1.5 基线迁移应成功");
        seedLegacyObjects();

        long formsBefore = count("SELECT COUNT(*) FROM sw_form_def");
        long menusBefore = count("SELECT COUNT(*) FROM sys_menu");
        System.out.println("[G4-drill] 升级前：forms=" + formsBefore + ", menus=" + menusBefore
                + ", record=" + count("SELECT COUNT(*) FROM \"" + RECORD_TABLE + "\" WHERE \"deleted\" = 0"));

        // —— 2. 升级前备份（已声明恢复路径的输入）——
        Path dumpFile = Files.createTempFile("ac-g4-drill-", ".dump");
        runTool(List.of("pg_dump", "-Fc", "-h", host(), "-p", port(), "-U", dbUser, "-d", DB_NAME,
                "-f", dumpFile.toString()));
        long dumpBytes = Files.size(dumpFile);
        assertTrue(dumpBytes > 0, "备份文件不应为空");
        System.out.println("[G4-drill] 备份完成：" + dumpFile + " (" + dumpBytes + " bytes)");

        // —— 3. 执行 0.1.6/0.1.7 存量升级脚本（增量 + 历史衔接，发布资产原文）→ 主链零重放 ——
        execScript(classpathResource(UPGRADE_SCRIPT));
        execScript(classpathResource(UPGRADE_017_SCRIPT));
        MigrateResult upgrade = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(pgLocations())
                .load().migrate();
        assertTrue(upgrade.success, "主链衔接验证应成功");
        assertEquals(0, upgrade.migrationsExecuted, "升级脚本后主链不应重复执行任何迁移（R__ 已在基线阶段应用）");
        assertEquals("0.1.7", currentVersion(), "升级后版本应为 0.1.7 种子登记");
        assertTrue(incrementalTablesPresent(), "升级后应存在应用中心增量表");
        assertTrue(count("SELECT COUNT(*) FROM sw_form_app WHERE id = 'lc201'") == 1L,
                "升级后应有承接应用");
        // 菜单脚本为可重复迁移（不受 target 约束，0.1.5 全新库亦已应用）：升级不得重复插入
        assertEquals(2L, count("SELECT COUNT(*) FROM sys_menu WHERE id IN (9130, 9131)"),
                "应用中心菜单应就位");
        assertEquals(menusBefore, count("SELECT COUNT(*) FROM sys_menu"), "升级不得重复插入菜单行");

        // —— 4. 恢复到已声明边界：全库恢复要求空库（先验证就地覆盖被拒绝，再按声明路径重建后恢复）——
        Path inPlaceLog = Files.createTempFile("ac-g4-inplace-", ".log");
        int inPlaceExit = runToolCapturing(List.of("pg_restore", "-h", host(), "-p", port(), "-U", dbUser,
                "-d", DB_NAME, "--no-owner", dumpFile.toString()), inPlaceLog);
        System.out.println("[G4-drill] 就地覆盖恢复 exit=" + inPlaceExit + "（非 0 = 明确拒绝，符合‘恢复需空库’）");
        assertTrue(inPlaceExit != 0, "就地覆盖到已升级库应明确失败（恢复路径前置条件：空库）");

        dropDatabase();
        createDatabase();
        int restoreExit = runTool(List.of("pg_restore", "-h", host(), "-p", port(), "-U", dbUser,
                "-d", DB_NAME, "--no-owner", dumpFile.toString()));
        assertEquals(0, restoreExit, "从备份恢复到空库应成功");
        System.out.println("[G4-drill] 已从备份恢复到 " + DB_NAME);

        // 恢复到 0.1.5 边界：增量表/列消失
        assertFalse(incrementalTablesPresent(), "恢复后不应存在应用中心增量表（回到 0.1.5 边界）");
        assertEquals("0.1.5", currentVersion(), "恢复后版本应回到升级前基线");
        // 存量对象逐项在、值未变
        assertEquals(formsBefore, count("SELECT COUNT(*) FROM sw_form_def"), "恢复后表单数应一致");
        assertEquals(menusBefore, count("SELECT COUNT(*) FROM sys_menu"), "恢复后菜单数应一致");
        assertEquals("升级前提交的报销单", queryString(
                "SELECT \"title\" FROM \"" + RECORD_TABLE + "\" WHERE \"id\" = '" + RECORD_ID + "'"),
                "恢复后存量记录字段值应保持");
        assertEquals(1L, count("SELECT COUNT(*) FROM sw_form_snapshot WHERE id = 'snap-g4-v1'"),
                "恢复后冻结版本快照应保留");

        // —— 5. 恢复后读写链可用 ——
        exec("UPDATE \"" + RECORD_TABLE + "\" SET \"title\" = '恢复后更新', \"version\" = 1, "
                + "\"update_time\" = CURRENT_TIMESTAMP WHERE \"id\" = '" + RECORD_ID + "'");
        assertEquals("恢复后更新", queryString(
                "SELECT \"title\" FROM \"" + RECORD_TABLE + "\" WHERE \"id\" = '" + RECORD_ID + "'"),
                "恢复后写入链应可用");
        exec("INSERT INTO \"" + RECORD_TABLE + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_by\", "
                + "\"version\", \"title\") VALUES ('rec-g4-2', 0, 0, 11, 0, '恢复后新增')");
        assertEquals(2L, count("SELECT COUNT(*) FROM \"" + RECORD_TABLE + "\" WHERE \"deleted\" = 0"),
                "恢复后新增记录应成功");
        // 恢复后仍可继续升级（再次执行 0.1.6 与 0.1.7 存量升级脚本）
        execScript(classpathResource(UPGRADE_SCRIPT));
        execScript(classpathResource(UPGRADE_017_SCRIPT));
        MigrateResult reUpgrade = Flyway.configure().dataSource(dbUrl, dbUser, dbPassword)
                .locations(pgLocations()).load().migrate();
        assertTrue(reUpgrade.success, "恢复后再次升级应成功");
        assertEquals(0, reUpgrade.migrationsExecuted, "恢复后再次升级不应重复执行主链迁移");
        assertEquals("0.1.7", currentVersion(), "恢复后再次升级应到达 0.1.7");
        assertTrue(incrementalTablesPresent(), "再次升级后增量表应重新就位");
        System.out.println("[G4-drill] 恢复后读写可用且可再次升级到 " + currentVersion());

        // 清理演练对象（仅本轮精确对象）
        dropDatabase();
        Files.deleteIfExists(dumpFile);
        System.out.println("[G4-drill] 演练库与备份文件已清理");
    }

    // ==================== 基础设施 ====================

    private void probeConnection() {
        String osUser = System.getProperty("user.name", "postgres");
        String[][] candidates = System.getenv("AC_G4_PG_URL") == null || System.getenv("AC_G4_PG_URL").isEmpty()
                ? new String[][]{
                        {"jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "123456"},
                        {"jdbc:postgresql://127.0.0.1:5432/postgres", osUser, ""}}
                : new String[][]{{System.getenv("AC_G4_PG_URL"), osUser, ""}};
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
        assumeTrue(false, "本机 PostgreSQL 不可用（已尝试 postgres/123456 与本机默认身份）：恢复演练跳过");
    }

    private void requireTooling() {
        for (String tool : List.of("pg_dump", "pg_restore")) {
            boolean available;
            try {
                available = runTool(List.of(tool, "--version")) == 0;
            } catch (Exception e) {
                available = false;
            }
            assumeTrue(available, tool + " 不可用：恢复演练跳过");
        }
    }

    private int runTool(List<String> command) throws IOException, InterruptedException {
        return runToolCapturing(command, Files.createTempFile("ac-g4-tool-", ".log"));
    }

    /** 执行外部工具并返回退出码；输出写入日志文件（失败时打印尾部，便于定位）。 */
    private int runToolCapturing(List<String> command, Path log) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(300, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("工具执行超时: " + command.get(0));
        }
        int exit = process.exitValue();
        if (exit != 0 && !"pg_dump".equals(command.get(0))) {
            List<String> lines = Files.readAllLines(log);
            String tail = String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
            System.out.println("[G4-drill] " + command.get(0) + " exit=" + exit + " 尾部输出=" + tail);
        }
        return exit;
    }

    private String host() {
        return serverUrl.replace("jdbc:postgresql://", "").split(":")[0];
    }

    private String port() {
        return serverUrl.replace("jdbc:postgresql://", "").split("/")[0].split(":")[1];
    }

    private void recreateDatabase() throws SQLException {
        dropDatabase();
        createDatabase();
    }

    private void dropDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DROP DATABASE IF EXISTS " + DB_NAME + " WITH (FORCE)");
        }
    }

    private void createDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(serverUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE DATABASE " + DB_NAME);
        }
    }

    private void migrateOnly() {
        Flyway.configure().dataSource(dbUrl, dbUser, dbPassword).locations(pgLocations()).load().migrate();
    }

    private void seedLegacyObjects() throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sw_bpm_category (id, tenant_id, name, sort_no, deleted, version) "
                    + "VALUES (201, 0, '报销', 1, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_bpm_process_def (id, process_key, name, form_key, category_id, "
                    + "tenant_id, deleted, version) VALUES (2101, 'pk_g4', 'G4事项', 'g4_form', 201, 0, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_form_def (id, form_key, name, status, form_version, "
                    + "physical_table_name, tenant_id, deleted, version) VALUES ('form-g4', 'g4_form', 'G4表单', "
                    + "'PUBLISHED', 1, '" + RECORD_TABLE + "', 0, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_form_snapshot (id, form_id, form_version, definition, tenant_id, "
                    + "deleted, version) VALUES ('snap-g4-v1', 'form-g4', 1, '{\"f\":1}', 0, 0, 0)");
            stmt.executeUpdate("CREATE TABLE \"" + RECORD_TABLE + "\" ("
                    + "\"id\" VARCHAR(36) NOT NULL, \"tenant_id\" BIGINT NOT NULL DEFAULT 0, "
                    + "\"deleted\" SMALLINT NOT NULL DEFAULT 0, "
                    + "\"create_time\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, \"create_by\" BIGINT, "
                    + "\"update_time\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, \"update_by\" BIGINT, "
                    + "\"version\" BIGINT NOT NULL DEFAULT 0, \"title\" VARCHAR(1000), PRIMARY KEY (\"id\"))");
            stmt.executeUpdate("INSERT INTO \"" + RECORD_TABLE + "\" (\"id\", \"tenant_id\", \"deleted\", "
                    + "\"create_by\", \"version\", \"title\") VALUES ('" + RECORD_ID + "', 0, 0, 11, 0, "
                    + "'升级前提交的报销单')");
            stmt.executeUpdate("INSERT INTO sw_bpm_instance (id, process_instance_id, process_def_key, "
                    + "business_key, form_key, initiator_id, status, tenant_id, deleted, version) "
                    + "VALUES (2100001, 'g4-inst-1', 'pk_g4', '" + RECORD_ID + "', 'g4_form', 11, "
                    + "'RUNNING', 0, 0, 0)");
        }
    }

    private boolean incrementalTablesPresent() throws SQLException {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('sw_form_app', 'sw_form_app_dir', 'sw_form_app_user')") == 3L;
    }

    private String currentVersion() throws SQLException {
        return queryString("SELECT version FROM flyway_schema_history WHERE success = TRUE "
                + "AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1");
    }

    private long count(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "计数查询应返回一行: " + sql);
            return rs.getLong(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "查询应返回一行: " + sql);
            String value = rs.getString(1);
            assertNotNull(value, "查询值不应为空: " + sql);
            return value;
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    // ==================== 0.1.6 / 0.1.7 升级通道脚本执行 ====================

    private static String classpathResource(String resource) throws Exception {
        try (var in = ApplicationCenterRecoveryDrillPostgresTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("发布资产缺失: " + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void execScript(String script) throws SQLException {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
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
