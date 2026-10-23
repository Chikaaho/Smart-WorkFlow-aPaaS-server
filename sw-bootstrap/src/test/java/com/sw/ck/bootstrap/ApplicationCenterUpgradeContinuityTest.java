package com.sw.ck.bootstrap;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.task.api.Task;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升级连续性验证（H2 全链 + 真实 Flowable 引擎，0.1.6→0.1.7 升级通道口径）：把“升级前建立的记录、
 * 冻结版本与运行任务”放在真实引擎运行态中，验证 0.1.6 存量升级脚本
 * （{@code upgrade-to-0.1.6-from-0.1.5.h2.sql}，应用中心增量 + 历史衔接）与 0.1.7 存量升级脚本
 * （{@code upgrade-to-0.1.7-from-0.1.6.h2.sql}，0.1.7 期增量 + 历史衔接）后：
 * <ol>
 *   <li>存量记录仍可读、字段值未变，迁移不新增记录/实例（零副作用回读）；</li>
 *   <li>已发布版本冻结：{@code sw_form_def.form_version} 与 {@code sw_form_snapshot} 内容逐字节未变，
 *       不因承接换分类产生新版本；</li>
 *   <li>运行实例与待办可定位，且应用停用后原办理人仍可办理原任务（实例正常结束）；</li>
 *   <li>唯一分类表单承接为分类应用；跨分类共用表单（多归属歧义）不错误改绑，保持 NULL；</li>
 *   <li>事项→表单绑定与分类关联保持，未被静默改写。</li>
 * </ol>
 * <p>
 * 存量夹具以受支持升级入口（0.1.5 边界）建立：先以升级通道 legacy 资源 + target=0.1.5
 * 迁移到 0.1.5 边界，插入存量对象，用真实引擎发起流程并留下待办，再依次执行 0.1.6 与 0.1.7
 * 升级脚本并验证主链零重放；全部断言在同一个库、同一组对象上完成。
 * </p>
 */
@DisplayName("应用中心升级连续性（H2 全链 + 真实引擎运行任务）")
class ApplicationCenterUpgradeContinuityTest {

    private static final String URL = "jdbc:h2:mem:app_center_continuity;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private static final String SHARED_TABLE = "sw_form_cont_expense";
    private static final String FORM_A = "lc_expense";
    private static final String FORM_SHARED = "lc_shared";
    private static final String RECORD_ID = "rec-continuity-1";
    private static final String SNAPSHOT_V1 = "{\"title\":\"title\",\"fields\":1}";

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

    private static ProcessEngine engine;
    private static TaskService taskService;
    private static RuntimeService runtimeService;
    private static HistoryService historyService;

    private static String preUpgradeTaskId;
    private static String preUpgradeInstanceId;

    @BeforeAll
    static void establishPreUpgradeStateAndUpgrade() throws SQLException {
        // —— 1. 受支持升级入口：迁移到 0.1.5 边界（target=0.1.5：只应用遗留 V0.1.5 种子）——
        MigrateResult base = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2BaseLocations())
                .target(MigrationVersion.fromVersion("0.1.5"))
                .load()
                .migrate();
        assertTrue(base.success, "0.1.5 基线迁移应成功");

        // —— 2. 存量夹具：分类 / 事项 / 表单 / 已发布版本 / 表单记录 ——
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sw_bpm_category (id, tenant_id, name, sort_no, deleted, version) "
                    + "VALUES (201, 0, '报销', 1, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_bpm_category (id, tenant_id, name, sort_no, deleted, version) "
                    + "VALUES (202, 0, '差旅', 2, 0, 0)");
            // 唯一分类事项：表单 lc_expense → 分类 201
            stmt.executeUpdate("INSERT INTO sw_bpm_process_def (id, process_key, name, form_key, category_id, "
                    + "tenant_id, deleted, version) VALUES (2101, 'pk_expense', '报销审批', '" + FORM_A
                    + "', 201, 0, 0, 0)");
            // 跨分类共用表单：lc_shared 同时挂在 201 与 202（多归属歧义）
            stmt.executeUpdate("INSERT INTO sw_bpm_process_def (id, process_key, name, form_key, category_id, "
                    + "tenant_id, deleted, version) VALUES (2102, 'pk_shared_a', '共用A', '" + FORM_SHARED
                    + "', 201, 0, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_bpm_process_def (id, process_key, name, form_key, category_id, "
                    + "tenant_id, deleted, version) VALUES (2103, 'pk_shared_b', '共用B', '" + FORM_SHARED
                    + "', 202, 0, 0, 0)");
            // 表单（升级前 schema：无 app_id/dir_id 列）
            insertForm(stmt, "form-expense", FORM_A, "报销单", "PUBLISHED", SHARED_TABLE, 1);
            insertForm(stmt, "form-shared", FORM_SHARED, "共用表单", "PUBLISHED",
                    "sw_form_cont_shared", 1);
            // 已发布版本冻结内容（config + snapshot 逐字节比对基准）
            stmt.executeUpdate("INSERT INTO sw_form_config (id, form_id, definition, tenant_id, deleted, version) "
                    + "VALUES ('cfg-expense', 'form-expense', "
                    + "'{\"fields\":[{\"name\":\"title\",\"type\":\"TEXT\",\"label\":\"标题\"}]}', 0, 0, 0)");
            stmt.executeUpdate("INSERT INTO sw_form_snapshot (id, form_id, form_version, definition, tenant_id, "
                    + "deleted, version) VALUES ('snap-expense-v1', 'form-expense', 1, '"
                    + SNAPSHOT_V1 + "', 0, 0, 0)");
            // 表单数据物理表 + 存量记录（升级前写入）
            stmt.executeUpdate("CREATE TABLE \"" + SHARED_TABLE + "\" ("
                    + "\"id\" VARCHAR(36) NOT NULL, \"tenant_id\" BIGINT NOT NULL DEFAULT 0, "
                    + "\"deleted\" SMALLINT NOT NULL DEFAULT 0, "
                    + "\"create_time\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, \"create_by\" BIGINT, "
                    + "\"update_time\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, \"update_by\" BIGINT, "
                    + "\"version\" BIGINT NOT NULL DEFAULT 0, \"title\" VARCHAR(1000), PRIMARY KEY (\"id\"))");
            stmt.executeUpdate("INSERT INTO \"" + SHARED_TABLE + "\" (\"id\", \"tenant_id\", \"deleted\", "
                    + "\"create_by\", \"version\", \"title\") VALUES ('" + RECORD_ID + "', 0, 0, 11, 0, '升级前提交的报销单')");
        }

        // —— 3. 真实引擎：发起流程并留下运行待办（升级前运行态）——
        ProcessEngineConfigurationImpl config =
                (ProcessEngineConfigurationImpl) ProcessEngineConfiguration
                        .createStandaloneProcessEngineConfiguration();
        config.setJdbcUrl(URL);
        config.setJdbcDriver("org.h2.Driver");
        config.setJdbcUsername(USER);
        config.setJdbcPassword(PASSWORD);
        config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        engine = config.buildEngine();
        taskService = engine.getTaskService();
        runtimeService = engine.getRuntimeService();
        historyService = engine.getHistoryService();
        RepositoryService repositoryService = engine.getRepositoryService();

        repositoryService.createDeployment()
                .name("g3-continuity")
                .addString("g3_continuity.bpmn20.xml", bpmn())
                .deploy();
        preUpgradeInstanceId = runtimeService.startProcessInstanceByKey("g3ContinuityFlow", RECORD_ID,
                Map.of("formKey", FORM_A, "approver", "11")).getId();
        Task task = taskService.createTaskQuery().processInstanceBusinessKey(RECORD_ID).singleResult();
        assertNotNull(task, "升级前应存在运行待办");
        preUpgradeTaskId = task.getId();

        // 3.1 应用侧实例台账（升级前运行态）：与引擎运行实例同标识
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sw_bpm_instance (id, process_instance_id, process_def_key, "
                    + "business_key, form_key, initiator_id, status, tenant_id, deleted, version) "
                    + "VALUES (2100001, '" + preUpgradeInstanceId + "', 'g3ContinuityFlow', '" + RECORD_ID
                    + "', '" + FORM_A + "', 11, 'RUNNING', 0, 0, 0)");
        }
        System.out.println("[G3-continuity] 升级前：instance=" + preUpgradeInstanceId
                + ", task=" + preUpgradeTaskId + ", assignee=" + task.getAssignee());

        // —— 4. 执行 0.1.6 存量升级脚本（应用中心增量 + 历史衔接，发布资产原文）——
        execScript(classpathResource(UPGRADE_SCRIPT));

        // —— 4.0 执行 0.1.7 存量升级脚本（0.1.7 期增量 + 历史衔接，发布资产原文）——
        execScript(classpathResource(UPGRADE_017_SCRIPT));

        // —— 4.1 主链衔接验证：零 versioned 重放、validate 通过 ——
        MigrateResult upgrade = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2Locations())
                .load()
                .migrate();
        assertTrue(upgrade.success, "主链衔接验证应成功");
        assertEquals(3, upgrade.migrationsExecuted,
                "升级脚本后只应执行 V0.1.8、V0.1.9 与 V0.1.10 增量，不重放历史迁移与 R__");
        Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations(h2Locations())
                .load()
                .validate();
    }

    private static String bpmn() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n"
                + "             xmlns:flowable=\"http://flowable.org/bpmn\"\n"
                + "             targetNamespace=\"http://sw.ck/g3\">\n"
                + "  <process id=\"g3ContinuityFlow\" name=\"G3 连续性流程\" isExecutable=\"true\">\n"
                + "    <startEvent id=\"start\" />\n"
                + "    <sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"approve\" />\n"
                + "    <userTask id=\"approve\" name=\"审批\" flowable:assignee=\"${approver}\" />\n"
                + "    <sequenceFlow id=\"f2\" sourceRef=\"approve\" targetRef=\"end\" />\n"
                + "    <endEvent id=\"end\" />\n"
                + "  </process>\n"
                + "</definitions>\n";
    }

    private static void insertForm(Statement stmt, String id, String formKey, String name,
                                   String status, String physicalTable, int formVersion) throws SQLException {
        stmt.executeUpdate("INSERT INTO sw_form_def (id, form_key, name, status, form_version, "
                + "physical_table_name, tenant_id, deleted, version) VALUES ('" + id + "', '" + formKey + "', '"
                + name + "', '" + status + "', " + formVersion + ", '" + physicalTable + "', 0, 0, 0)");
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

    private void exec(String sql) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        }
    }

    @Test
    @DisplayName("存量记录与已发布版本：升级后字段值与版本冻结内容逐项未变，迁移零新增记录/实例")
    void legacyRecordAndFrozenVersionSurviveUpgrade() throws SQLException {
        assertEquals("升级前提交的报销单",
                queryString("SELECT \"title\" FROM \"" + SHARED_TABLE + "\" WHERE \"id\" = '" + RECORD_ID + "'"),
                "升级后存量记录字段值必须保持");
        assertEquals(0L, queryLong("SELECT \"deleted\" FROM \"" + SHARED_TABLE + "\" WHERE \"id\" = '"
                + RECORD_ID + "'"), "升级不得逻辑删除存量记录");
        // 版本冻结：form_version 与快照内容未被迁移改写
        assertEquals(1L, queryLong("SELECT form_version FROM sw_form_def WHERE id = 'form-expense'"));
        assertEquals(SNAPSHOT_V1.replace(" ", ""),
                queryString("SELECT definition FROM sw_form_snapshot WHERE id = 'snap-expense-v1'")
                        .replace(" ", ""),
                "已发布版本的快照内容不得因承接换分类被改写");
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM sw_form_snapshot WHERE form_id = 'form-expense'"),
                "迁移不得为该表单生成新版本快照");
        // 迁移零副作用：记录数与实例数不变
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM \"" + SHARED_TABLE + "\" WHERE \"deleted\" = 0"));
        assertEquals(1L, queryLong("SELECT COUNT(*) FROM sw_bpm_instance WHERE business_key = '" + RECORD_ID + "'"));
    }

    @Test
    @DisplayName("事项→表单绑定与分类关联保持，未被静默改写")
    void formBindingAndCategoryLinksUntouched() throws SQLException {
        assertEquals(FORM_A, queryString("SELECT form_key FROM sw_bpm_process_def WHERE id = 2101"));
        assertEquals(201L, queryLong("SELECT category_id FROM sw_bpm_process_def WHERE id = 2101"));
        assertEquals(FORM_SHARED, queryString("SELECT form_key FROM sw_bpm_process_def WHERE id = 2102"));
        assertEquals(202L, queryLong("SELECT category_id FROM sw_bpm_process_def WHERE id = 2103"));
    }

    @Test
    @DisplayName("承接规则：唯一分类表单归分类应用；跨分类共用表单保持 NULL（歧义不自动转换）")
    void carryOverAssignsOnlyUnambiguousForms() throws SQLException {
        assertEquals("lc201", queryString("SELECT app_id FROM sw_form_def WHERE id = 'form-expense'"));
        assertNull(queryString("SELECT app_id FROM sw_form_def WHERE id = 'form-shared'"),
                "跨分类共用表单不得任选归属，应保持 NULL 交裁决");
        assertEquals("ALL", queryString("SELECT visible_scope_type FROM sw_form_app WHERE id = 'lc201'"));
        assertEquals("LEGACY_CATEGORY", queryString("SELECT source FROM sw_form_app WHERE id = 'lc201'"));
    }

    @Test
    @DisplayName("运行任务连续性：停用承接应用后，原办理人仍可定位并办理原任务，实例正常结束")
    void runningTaskCompletesAfterUpgradeAndAppDisable() throws SQLException {
        // 升级后待办仍可定位（同一实例/任务标识）
        Task afterUpgrade = taskService.createTaskQuery()
                .processInstanceBusinessKey(RECORD_ID).singleResult();
        assertNotNull(afterUpgrade, "升级后原运行待办必须仍可定位");
        assertEquals(preUpgradeTaskId, afterUpgrade.getId(), "升级不应重建或替换任务标识");
        assertEquals(preUpgradeInstanceId, afterUpgrade.getProcessInstanceId());
        assertEquals("11", afterUpgrade.getAssignee(), "原办理人保持不变");
        System.out.println("[G3-continuity] 升级后定位到同一任务：" + afterUpgrade.getId()
                + "，所属实例=" + afterUpgrade.getProcessInstanceId());

        // 停用承接应用（收窄/停用的极端形态）：不得等同取消运行实例
        exec("UPDATE sw_form_app SET status = 'DISABLED' WHERE id = 'lc201'");
        assertEquals(0L, queryLong("SELECT COUNT(*) FROM sw_form_app WHERE id = 'lc201' AND status = 'ENABLED'"));
        assertNotNull(taskService.createTaskQuery().processInstanceBusinessKey(RECORD_ID).singleResult(),
                "停用应用不得使运行待办消失");

        // 原办理人办理原任务：任务完成、实例结束，存量记录不丢
        taskService.complete(afterUpgrade.getId(), Map.of("approved", true));
        assertNull(taskService.createTaskQuery().processInstanceBusinessKey(RECORD_ID).singleResult(),
                "办理后不应再有待办");
        assertEquals(0L, runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(RECORD_ID).count(), "办理后实例应结束");
        assertNotNull(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(RECORD_ID).finished().singleResult(),
                "历史实例应保留且标记结束");
        assertEquals("升级前提交的报销单",
                queryString("SELECT \"title\" FROM \"" + SHARED_TABLE + "\" WHERE \"id\" = '" + RECORD_ID + "'"),
                "办理过程中存量记录不得丢失");
        System.out.println("[G3-continuity] 停用应用后原任务办理完成：task=" + afterUpgrade.getId()
                + "，实例历史保留=" + (historyService.createHistoricProcessInstanceQuery()
                        .processInstanceBusinessKey(RECORD_ID).finished().count() == 1));
    }

    // ==================== 0.1.6 / 0.1.7 升级通道脚本执行 ====================

    /** 0.1.6 存量升级脚本（发布资产原文，classpath 读取，测试即验证交付物本身）。 */
    private static final String UPGRADE_SCRIPT = "db/upgrade/0.1.6/upgrade-to-0.1.6-from-0.1.5.h2.sql";

    /** 0.1.7 存量升级脚本（发布资产原文，classpath 读取，测试即验证交付物本身）。 */
    private static final String UPGRADE_017_SCRIPT = "db/upgrade/0.1.7/upgrade-to-0.1.7-from-0.1.6.h2.sql";

    private static String classpathResource(String resource) throws SQLException {
        try (var in = ApplicationCenterUpgradeContinuityTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("发布资产缺失: " + resource);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new SQLException("读取发布资产失败: " + resource, e);
        }
    }

    private static void execScript(String script) throws SQLException {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASSWORD);
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
