package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.port.FormTxnActionPort;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 S2 收尾：生产轻流程真实 PostgreSQL 端到端证据。
 * <p>
 * 覆盖：轻流程发布门（合法图发布成功 / 混人工等待图发布被拒 2421）；
 * 表单提交 → FLOW_START 持久受理 → 命令调度消费（正式身份回查）→ Flowable 实例 →
 * TXN_ACTION 节点经受控 Port 真实调用已发布动作（同一事务预占落库）→ 实例直达终态；
 * 节点稳定幂等键（NODE:{instanceId}:{activityId}）重放返回原结果且无第二次业务效果。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 S2 生产轻流程 PG 端到端（发布门/提交→受理→节点真实动作→幂等重放）")
class P62LightProcessE2ePgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91001L;
    private static final String FORM_KEY = "p62_lp_stock";
    private static final String ACTIVITY_ID = "act_reserve";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnActionService actionService;
    private BpmProcessDefService processDefService;
    private FormTxnActionPort txnActionPort;
    private BpmCommandMapper commandMapper;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-lp-e2e-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-lp-e2e-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-lp-e2e", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62LpE2eLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        actionService = app.getBean(TxnActionService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        txnActionPort = app.getBean(FormTxnActionPort.class);
        commandMapper = app.getBean(BpmCommandMapper.class);

        // 发起人 + 管理员角色（baseline 已种 role 2，R 迁移已把 form:action:invoke 授权给 role 2）。
        // id 取 P62 测试高位段，ON CONFLICT 幂等，防与既有种子/重跑冲突。
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'lp-e2e-initiator', 'seed-not-a-login-secret', '轻流程发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91001, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);

        seedStockForm();
        System.out.println("[P62-EV] s2.e2e boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        try {
            if (pg != null) {
                pg.close();
            }
        } catch (Exception ignored) {
            // 关闭容错
        }
        LoginUserHolder.clear();
    }

    // ==================== 发布门：反向 ====================

    @Test
    @DisplayName("发布门反向：轻流程混入人工审批节点发布被拒（2421），Flowable 未部署")
    void mixedApprovalNodeRejectedAtPublish() {
        String actionId = publishAction("lp_e2e_rej_reserve", "拒绝场景预占", "2");
        List<GraphElement> mixed = List.of(
                node("start", "START"),
                node(ACTIVITY_ID, "TXN_ACTION", actionConfig(actionId, "2")),
                node("approver", "APPROVAL", Map.of("name", "人工审批",
                        "approver", Map.of("type", "DESIGNATED", "value", "91001"))),
                node("end", "END"),
                edge("e1", "start", ACTIVITY_ID),
                edge("e2", ACTIVITY_ID, "approver"),
                edge("e3", "approver", "end"));
        BpmProcessDef def = createDefWithGraph("p62_lp_mixed_rej", "轻流程混入拒绝", mixed);

        assertThatThrownBy(() -> asOperator(() -> processDefService.publish(def.getId())))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode())
                                .isEqualTo(BpmErrorCode.LIGHT_PROCESS_NODE_NOT_ALLOWED.getCode()));
        // 发布失败：部署不落、绑定不激活
        BpmProcessDef after = asOperator(() -> processDefService.findById(def.getId()));
        assertThat(after.getStatus()).isEqualTo("DRAFT");
        assertThat(after.getProcessDefinitionId()).isNull();
        long bindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE process_def_key = ? AND active = true",
                Long.class, after.getProcessKey());
        assertThat(bindings).as("被拒流程不得激活表单绑定").isZero();
        System.out.println("[P62-EV] s2.e2e publish-gate rejected=2421 draft-unchanged bindings=0");
    }

    // ==================== 端到端：发布 → 提交 → 受理 → 消费 → 节点动作 → 终态 ====================

    @Test
    @DisplayName("端到端：合法轻流程发布成功；表单提交持久受理，命令消费后节点经 Port 真实预占，实例直达终态")
    void lightProcessEndToEndReservesStock() {
        String actionId = publishAction("lp_e2e_reserve", "端到端预占", "3");
        List<GraphElement> light = List.of(
                node("start", "START"),
                node(ACTIVITY_ID, "TXN_ACTION", actionConfig(actionId, "3")),
                node("end", "END"),
                edge("e1", "start", ACTIVITY_ID),
                edge("e2", ACTIVITY_ID, "end"));
        BpmProcessDef def = createDefWithGraph("p62_lp_e2e_ok", "轻流程端到端", light);
        BpmProcessDef published = asOperator(() -> processDefService.publish(def.getId()));
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getProcessDefinitionId()).as("Flowable 定义已部署").isNotBlank();

        String recordId = asOperator(() -> submitService.submitForm(FORM_KEY,
                data("material", "LP-E2E-A", "qty_available", "50", "qty_reserved", "0"),
                null, null, null));
        assertThat(recordId).isNotBlank();

        // 同事务持久受理：FLOW_START 命令行已落库
        BpmCommand accepted = findFlowStartCommand(recordId);
        assertThat(accepted).as("表单提交事务内已受理 FLOW_START").isNotNull();
        assertThat(accepted.getTenantId()).isEqualTo(TENANT);

        // 命令调度器（正式身份回查后消费）→ 流程实例 → TXN_ACTION 同步执行 → 终态
        String processInstanceId = awaitInstanceCompleted(recordId);
        BigDecimal reserved = reservedOf(recordId);
        assertThat(reserved).as("动作节点真实预占 3").isEqualByComparingTo("3");
        assertThat(balanceOf(recordId)).as("预占不动余额").isEqualByComparingTo("50");

        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ? AND invocation_key = ?",
                Long.class, actionId, invocationKey(processInstanceId));
        assertThat(invocations).as("节点稳定幂等键恰一条调用记录").isEqualTo(1L);
        String invocationStatus = jdbc.queryForObject(
                "SELECT status FROM sw_form_txn_invocation WHERE action_id = ? AND invocation_key = ?",
                String.class, actionId, invocationKey(processInstanceId));
        assertThat(invocationStatus).isEqualTo("SUCCEEDED");

        BpmCommand completed = findFlowStartCommand(recordId);
        assertThat(completed.getStatus()).as("FLOW_START 命令消费完成").isEqualTo("COMPLETED");
        System.out.println("[P62-EV] s2.e2e end-to-end published=true accepted=1 instanceDone=true"
                + " reserved=3 invocationKey=" + invocationKey(processInstanceId)
                + " invocation=SUCCEEDED command=COMPLETED");
    }

    // ==================== 幂等重放：同键重放原结果、无第二次效果 ====================

    @Test
    @DisplayName("幂等重放：以节点稳定幂等键重放返回原结果（replay=true），预占不叠加")
    void nodeKeyReplayKeepsSingleEffect() {
        String actionId = publishAction("lp_e2e_replay_reserve", "重放场景预占", "2");
        List<GraphElement> light = List.of(
                node("start", "START"),
                node(ACTIVITY_ID, "TXN_ACTION", actionConfig(actionId, "2")),
                node("end", "END"),
                edge("e1", "start", ACTIVITY_ID),
                edge("e2", ACTIVITY_ID, "end"));
        BpmProcessDef def = createDefWithGraph("p62_lp_e2e_replay", "轻流程重放", light);
        asOperator(() -> processDefService.publish(def.getId()));

        String recordId = asOperator(() -> submitService.submitForm(FORM_KEY,
                data("material", "LP-E2E-B", "qty_available", "40", "qty_reserved", "0"),
                null, null, null));
        String processInstanceId = awaitInstanceCompleted(recordId);
        assertThat(reservedOf(recordId)).isEqualByComparingTo("2");

        // 引擎重试/恢复视角：同一节点稳定幂等键重放（同键同载荷）
        TxnInvokeRequest replay = new TxnInvokeRequest();
        replay.setRecordId(recordId);
        replay.setQuantity("2");
        replay.setInvocationKey(invocationKey(processInstanceId));
        FormTxnActionPort.TxnActionResult result = asOperator(() -> txnActionPort.invoke(
                new FormTxnActionPort.TxnActionCommand(actionId, recordId, "2",
                        invocationKey(processInstanceId), null, null)));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.replay()).as("重放返回原结果标记").isTrue();
        assertThat(result.reservationId()).isNotBlank();
        assertThat(reservedOf(recordId)).as("重放不产生第二次业务效果").isEqualByComparingTo("2");
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ? AND invocation_key = ?",
                Long.class, actionId, invocationKey(processInstanceId));
        assertThat(invocations).as("重放后调用记录仍恰一条").isEqualTo(1L);

        // 同键不同载荷：明确冲突（1606），原结果不变
        TxnInvokeRequest divergent = new TxnInvokeRequest();
        divergent.setRecordId(recordId);
        divergent.setQuantity("5");
        divergent.setInvocationKey(invocationKey(processInstanceId));
        assertThatThrownBy(() -> asOperator(() -> txnActionPort.invoke(
                new FormTxnActionPort.TxnActionCommand(actionId, recordId, "5",
                        invocationKey(processInstanceId), null, null))))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(
                                com.sw.ck.form.api.exception.FormErrorCode.ACTION_IDEMPOTENCY_CONFLICT.getCode()));
        assertThat(reservedOf(recordId)).as("冲突路径原结果不变").isEqualByComparingTo("2");
        System.out.println("[P62-EV] s2.e2e replay replay=true effect-once=true same-key-diff-payload=REJECTED"
                + " reserved=2 invocations=1");
    }

    // ==================== 轻流程图构造与发布辅助 ====================

    private BpmProcessDef createDefWithGraph(String processKey, String name, List<GraphElement> elements) {
        BpmProcessDef def = asOperator(() -> processDefService.createDef(name, FORM_KEY));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey(def.getProcessKey())
                .name(name)
                .formKey(FORM_KEY)
                .version(1)
                .elements(elements)
                .build();
        asOperator(() -> {
            processDefService.saveDraftGraph(def.getId(), toJson(graph));
            return null;
        });
        return def;
    }

    private Map<String, Object> actionConfig(String actionId, String quantity) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "库存预占");
        config.put("actionId", actionId);
        config.put("recordIdSource", "instanceBusinessKey");
        config.put("quantity", quantity);
        config.put("failureStrategy", "BLOCK");
        return config;
    }

    private static GraphElement node(String id, String type) {
        return node(id, type, Map.of());
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type)
                .config(config == null ? Map.of() : config).style(Map.of()).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of()).build();
    }

    private String toJson(ProcessGraph graph) {
        try {
            return app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(graph);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 等待与查询辅助 ====================

    private BpmCommand findFlowStartCommand(String recordId) {
        return asOperator(() -> commandMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BpmCommand>()
                        .eq(BpmCommand::getCommandKey, "FLOW_START:" + recordId)
                        .eq(BpmCommand::getTenantId, TENANT)
                        .orderByDesc(BpmCommand::getId)
                        .last("LIMIT 1"))).stream().findFirst().orElse(null);
    }

    /** 有界等待命令调度器消费完成并返回流程实例 id（真实异步链，不做无条件空等）。 */
    private String awaitInstanceCompleted(String recordId) {
        long deadline = System.currentTimeMillis() + 180_000L; // async 节点+对账安静期(1min)后的收敛窗口
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT process_instance_id, status FROM sw_bpm_instance WHERE business_key = ?", recordId);
            if (!rows.isEmpty() && "APPROVED".equals(rows.get(0).get("status"))) {
                return String.valueOf(rows.get(0).get("process_instance_id"));
            }
            // TXN_ACTION async 独立短事务：触发实例状态对账收敛（不依赖调度时序）
            asOperator(() -> app.getBean(com.sw.ck.bpm.process.job.BpmInstanceStateSyncJob.class).sweepOnce());
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        List<Map<String, Object>> finalRows = jdbc.queryForList(
                "SELECT process_instance_id, status FROM sw_bpm_instance WHERE business_key = ?", recordId);
        List<Map<String, Object>> commandRows = jdbc.queryForList(
                "SELECT id AS command_id, status, retry_count, failure_reason FROM sw_bpm_command WHERE command_key = ?",
                "FLOW_START:" + recordId);
        throw new AssertionError("60s 内轻流程未收敛到终态: instance=" + finalRows
                + ", command=" + commandRows);
    }

    private String invocationKey(String processInstanceId) {
        return "NODE:" + processInstanceId + ":" + ACTIVITY_ID;
    }

    // ==================== 种子与通用辅助 ====================

    private void seedStockForm() {
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P62轻流程库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62轻流程库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private String publishAction(String key, String name, String quantity) {
        String formId = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY).getId());
        return asOperator(() -> {
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = actionService.create(formId, new TxnActionSaveRequest(key, name, "RESERVE", null, cfg)).id();
            actionService.publish(id);
            return id;
        });
    }

    private BigDecimal balanceOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_available") + " FROM " + q(stockTable)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private BigDecimal reservedOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_reserved") + " FROM " + q(stockTable)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 带调用权限的操作身份（模拟真实会话；dispatcher 消费链走正式 UserDetailsProvider）。 */
    private static <T> T asOperator(Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke")));
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

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
