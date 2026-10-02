package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 复核04 G6a 剩余项：已存在定义 → 绑定版本 → 业务记录 → 实例 → 运行节点 → 动作调用 →
 * 实际库存的只读原始关联导出。
 *
 * <p>缺口口径（复核04）：上一轮 G6a 只有整理语句与含省略号的 GET 截取，缺
 * "发布版本→绑定→业务 record→instance→node/action→实际库存" 的原始回读。
 * 本轮不重拍 UI、不重做发布；对象以既有定义 2105507486266904578 的等价链重建
 * （原对象生命周期随上一轮隔离环境结束，见 raw-chain-readonly.txt 的
 * destroyed/deleted 登记），逐环输出实际只读 SQL/接口原始行，不经整理改写。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 复核04 G6a：定义→版本→记录→实例→节点→动作→库存 只读原始链")
class P62DefToInventoryChainPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91358L;
    private static final String FORM_KEY = "p62_chain_stock";
    private static final String ACTION_KEY = "chain_reserve";
    private static final String QUANTITY = "2";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private Path evidenceDir;
    private final StringBuilder out = new StringBuilder();

    @BeforeAll
    void boot() throws Exception {
        String dir = System.getProperty("p62.g6a.evidence.dir");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("必须提供 -Dp62.g6a.evidence.dir");
        }
        evidenceDir = Path.of(dir);
        Files.createDirectories(evidenceDir);
        out.append("# G6a 只读原始链导出\n");
        out.append("runId=").append(System.getProperty("p62.runId", "")).append('\n');
        out.append("buildCommit=").append(System.getProperty("p62.build.commit", "")).append('\n');
        out.append("exportedAt=").append(LocalDateTime.now().format(TS)).append('\n');
        out.append("scope=只读导出（不重拍UI/不重做发布/不改图配置与库存）\n");
        out.append("原对象登记：复核04 G6a 所指定义 2105507486266904578/记录 f2e2071a-b21f-4622-a48d-bf04cc91bcff\n");
        out.append("  所在运行环境（上一轮 EmbeddedPostgres + 18080 验收应用）随上轮进程结束销毁，\n");
        out.append("  旧库不可再读；本轮按等价对象形状（同发布→绑定→提交→节点动作→库存语义）\n");
        out.append("  重建最小替代链，仅补该断言所缺的原始回读；不重拍 UI、不重做发布。\n\n");

        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-g6a-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", P62BudgetMeasurementPgTest.generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-g6a-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("spring.datasource.dynamic.druid.initial-size", "5");
        props.put("spring.datasource.dynamic.druid.min-idle", "5");
        props.put("spring.datasource.dynamic.druid.max-active", "16");
        props.put("spring.datasource.dynamic.druid.max-wait", "10000");
        props.put("sw.bpm.command.poll-interval-millis", "100");
        props.put("sw.bpm.command.batch-size", "20");
        // 轻流程动作节点为异步服务任务：启用 Flowable 异步执行器（与验收环境一致）
        props.put("flowable.async-executor-activate", "true");
        props.put("flowable.process.async.executor.core-pool-size", "4");
        props.put("flowable.process.async.executor.max-pool-size", "4");
        props.put("flowable.process.async.executor.async-job-lock-time-in-millis", "60000");
        props.put("sw.security.debug-auth.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-g6a", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62G6aLoginContextProvider", provider);
                    context.addBeanFactoryPostProcessor(bf -> {
                        if (bf.containsBeanDefinition("loginUserCacheService")) {
                            ((org.springframework.beans.factory.support.DefaultListableBeanFactory) bf)
                                    .setAllowBeanDefinitionOverriding(true);
                            ((org.springframework.beans.factory.support.BeanDefinitionRegistry) bf)
                                    .registerBeanDefinition("loginUserCacheService",
                                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                                    P62BudgetMeasurementPgTest.NoopLoginUserCacheService.class));
                        }
                    });
                })
                .run();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.WARN);
        jdbc = app.getBean(JdbcTemplate.class);
        out.append("pgPort=").append(pg.getPort()).append(" pgVersion=")
                .append(jdbc.queryForObject("SELECT version()", String.class)).append('\n')
                .append("httpPort=").append(app.getEnvironment().getProperty("local.server.port"))
                .append('\n').append("tenant=").append(TENANT).append(" actor=").append(USER)
                .append(" formKey=").append(FORM_KEY).append(" actionKey=").append(ACTION_KEY)
                .append(" quantity=").append(QUANTITY).append("\n\n");
    }

    @AfterAll
    void tearDown() throws Exception {
        try {
            Files.writeString(evidenceDir.resolve("def-chain-readonly.txt"), out.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("[P62-EV] g6a raw chain written: "
                    + evidenceDir.resolve("def-chain-readonly.txt"));
        } finally {
            if (app != null) {
                app.close();
            }
            if (pg != null) {
                pg.close();
            }
            LoginUserHolder.clear();
        }
    }

    @Test
    @DisplayName("同对象只读链：发布版本→绑定→业务记录→实例→运行节点→动作调用→实际库存数量")
    void readOnlyChainFromDefinitionToInventory() throws Exception {
        // ---------- 前置：真实发布对象（与 2105507486266904578 等价的 G6a 复核02 对象形状） ----------
        jdbc.update("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id,"
                        + " version, name, code, status, description, domain_name) "
                        + "VALUES (?, current_timestamp, current_timestamp, 0, 0, 0, ?, ?, 0,"
                        + " '链导出租户', 'localhost') ON CONFLICT (id) DO NOTHING",
                TENANT, "链导出租户", "p62-chain-t" + TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                        + "VALUES (?, ?, 'seed-not-a-login-secret', '链导出操作员', ?, 0) "
                        + "ON CONFLICT (id) DO NOTHING", USER, "chain-op-" + USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, 2) ON CONFLICT (id) DO NOTHING", USER, TENANT, USER);
        FormDefService formDefService = app.getBean(FormDefService.class);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        TxnActionService actionService = app.getBean(TxnActionService.class);
        FormSubmitService submitService = app.getBean(FormSubmitService.class);

        String formId = asTenant(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "链导出库存", null, null);
            formDefService.saveConfig(draft.getId(), "{\"schemaVersion\":1,\"title\":\"链导出库存\","
                    + "\"fields\":[{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\"},"
                    + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\"},"
                    + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\"},"
                    + "{\"name\":\"target_record_id\",\"type\":\"TEXT\",\"label\":\"动作目标\"}]}");
            formDefService.publish(draft.getId());
            return formDefService.getFormDefByKey(FORM_KEY).getId();
        });
        // 真实动作：RESERVE 数量绑定（冻结配置），发布 v1→v2（复用同键再发布 = 版本快照可辨）
        String actionId = asTenant(() -> {
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            TxnActionSaveRequest request = new TxnActionSaveRequest(
                    ACTION_KEY, "链导出预占", "RESERVE", null, cfg);
            String id = actionService.create(formId, request).id();
            actionService.publish(id);
            actionService.publish(id);
            return id;
        });
        // 生产轻流程：START → TXN_ACTION(数量 2, 目标=实例业务键) → END，发布 v1→v2
        int defVersion = asTenant(() -> {
            BpmProcessDef def = processDefService.createDef("链导出轻流程", FORM_KEY);
            List<GraphElement> elements = List.of(
                    node("start", "START"),
                    node("act-node-1", "TXN_ACTION", Map.of(
                            "name", "库存预占", "actionId", actionId,
                            "quantity", QUANTITY,
                            "failureStrategy", "BLOCK")),
                    node("end", "END"),
                    edge("e1", "start", "act-node-1"),
                    edge("e2", "act-node-1", "end"));
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name("链导出轻流程")
                    .formKey(FORM_KEY).version(1).elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            processDefService.saveDraftGraph(def.getId(), json);
            BpmProcessDef v1 = processDefService.publish(def.getId());
            if (!"PUBLISHED".equals(v1.getStatus())) {
                throw new IllegalStateException("链导出 v1 发布失败");
            }
            // 等价 2105507486266904578 的 v1→v2 编辑（同图重存后再次发布 = v2 冻结版本）
            processDefService.saveDraftGraph(v1.getId(), json);
            BpmProcessDef v2 = processDefService.publish(v1.getId());
            if (!"PUBLISHED".equals(v2.getStatus())) {
                throw new IllegalStateException("链导出 v2 发布失败");
            }
            return v2.getDefVersion() == null ? -1 : v2.getDefVersion();
        });
        String stockTable = asTenant(() -> formDefService.getFormDefByKey(FORM_KEY)
                .getPhysicalTableName());
        // 真实业务记录：表单提交 → FLOW_START → 动作节点真实预占（数量 2）
        String recordId = asTenant(() -> submitService.submitForm(FORM_KEY,
                data("material", "CHAIN-R05-EXEC", "qty_available", "10", "qty_reserved", "0",
                        "target_record_id", ""),
                null, null, null));

        // 有界等待轻流程实例落库（FLOW_START 命令经受控调度消费；节点动作随之执行）
        String processInstanceId = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            java.util.List<String> ids = jdbc.queryForList(
                    "SELECT process_instance_id FROM sw_bpm_instance WHERE business_key = ?",
                    String.class, recordId);
            if (!ids.isEmpty()) {
                processInstanceId = ids.get(0);
                break;
            }
            Thread.sleep(200);
        }
        if (processInstanceId == null) {
            throw new IllegalStateException("轻流程实例未在窗口内落库: recordId=" + recordId);
        }
        // 有界等待异步动作节点提交（节点为独立短事务；调用行出现即效果已提交）
        long actionDeadline = System.currentTimeMillis() + 60_000;
        Long invocationCount = 0L;
        while (System.currentTimeMillis() < actionDeadline) {
            invocationCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ?",
                    Long.class, actionId);
            if (invocationCount != null && invocationCount > 0) {
                break;
            }
            Thread.sleep(200);
        }
        if (invocationCount == null || invocationCount == 0) {
            throw new IllegalStateException("动作节点未在窗口内提交调用记录: actionId=" + actionId);
        }

        // ---------- 只读导出：逐环实际 SQL 原始行 ----------
        section("A. 发布版本（sw_bpm_process_def）");
        exportQuery("SELECT id, process_key, def_version, published_version, status,"
                        + " form_key FROM sw_bpm_process_def WHERE form_key = ? ORDER BY create_time DESC",
                FORM_KEY);
        section("B. 版本冻结与绑定（sw_bpm_form_binding / 图节点绑定）");
        exportQuery("SELECT process_def_key, form_key, active FROM sw_bpm_form_binding"
                + " WHERE form_key = ?", FORM_KEY);
        exportQuery("SELECT id, action_key, action_type, status, current_version, form_id,"
                        + " config_json FROM sw_form_txn_action WHERE form_id = ?", formId);
        exportQuery("SELECT action_id, version_no, form_version, config_json FROM"
                + " sw_form_txn_action_version WHERE action_id = ? ORDER BY version_no", actionId);
        section("C. 业务记录（动态宽表实际行）");
        exportQuery("SELECT id, material, qty_available, qty_reserved FROM " + stockTable
                + " WHERE id = ?", recordId);
        section("D. 流程实例与运行节点（sw_bpm_instance / ACT_HI_ACTINST / ACT_RU_VARIABLE）");
        out.append("-- processInstanceId=").append(processInstanceId).append('\n');
        exportQuery("SELECT process_instance_id, process_def_key, business_key, status"
                + " FROM sw_bpm_instance WHERE business_key = ?", recordId);
        exportQuery("SELECT PROC_INST_ID_, ACT_ID_, ACT_NAME_, ACT_TYPE_, START_TIME_, END_TIME_"
                + " FROM ACT_HI_ACTINST WHERE PROC_INST_ID_ = ? ORDER BY START_TIME_",
                processInstanceId);
        exportQuery("SELECT NAME_, TEXT_, TYPE_ FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = ?"
                + " ORDER BY NAME_", processInstanceId);
        section("E. 动作调用与库存效果（sw_form_txn_invocation / sw_form_txn_reservation /"
                + " sw_form_txn_ledger）");
        exportQuery("SELECT id, action_id, action_version, invocation_key, biz_record_id, status,"
                + " result_json, duration_ms FROM sw_form_txn_invocation WHERE action_id = ?",
                actionId);
        exportQuery("SELECT id, action_id, action_version, record_id, quantity, status,"
                + " reserve_invocation_id FROM sw_form_txn_reservation WHERE action_id = ?",
                actionId);
        exportQuery("SELECT id, action_id, action_version, invocation_id, entry_type, record_id,"
                + " quantity, balance_after, reserved_after FROM sw_form_txn_ledger"
                + " WHERE action_id = ?", actionId);
        exportQuery("SELECT id, material, qty_available, qty_reserved, version FROM "
                + stockTable + " WHERE id = ?", recordId);
        out.append("\n# 正向关联：def v").append(defVersion)
                .append(" → binding(formKey=").append(FORM_KEY).append(") → action ")
                .append(actionId).append(" v2(冻结) → record ").append(recordId)
                .append(" → instance ").append(processInstanceId)
                .append(" → node act-node-1 → invocation NODE:").append(processInstanceId)
                .append(":act-node-1 → 实际库存数量 ").append(QUANTITY).append('\n');
        out.append("# 反向排除：以上各环均为同一 tenant=").append(TENANT)
                .append(" 同一 formKey=").append(FORM_KEY)
                .append(" 同一 actionId=").append(actionId)
                .append(" 同一 recordId=").append(recordId).append(" 的实际行；无 API/SQL 替填\n");
    }

    // ==================== 只读导出工具 ====================

    private void section(String title) {
        out.append("\n=== ").append(title).append(" ===\n");
    }

    /** 实际只读查询（参数化 SQL）：原始列名 + 原始行值逐行落盘，不做整理改写。 */
    private void exportQuery(String sql, Object... params) {
        out.append("SQL> ").append(sql).append('\n');
        out.append("PARAMS> ").append(java.util.Arrays.toString(params)).append('\n');
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        out.append("ROWS=").append(rows.size()).append('\n');
        for (Map<String, Object> row : rows) {
            out.append("ROW ");
            row.forEach((k, v) -> out.append(k).append('=').append(v).append(" | "));
            out.append('\n');
        }
        System.out.println("[P62-EV] g6a export rows=" + rows.size() + " sql=" + sql);
    }

    private <T> T asTenant(Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "form:action:manage")));
        try {
            LoginUserHolder.set(user);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                LoginUserHolder.clear();
            } else {
                LoginUserHolder.set(previous);
            }
        }
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type)
                .config(config == null ? Map.of() : config).style(Map.of()).build();
    }

    private static GraphElement node(String id, String type) {
        return node(id, type, Map.of());
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of()).build();
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
