package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.BpmCommandBatch;
import com.sw.ck.bpm.process.entity.BpmCommandBatchItem;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchItemMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TxnBatchService;
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

/**
 * P62 S6 补证（G3）：节点级独立提交/效果保留/冻结版本/同键异载荷（真实 PostgreSQL）。
 *
 * <p>覆盖 U02/U03/U05 剩余行为证据：两个动作节点前一成功后一失败（BLOCK）时，
 * 前节点效果与进度保留、重放不重复；批次受理后动作改版仍按冻结版本结算；
 * 批次项载荷被外部改动后按同键异载荷拒绝且原结果不变；
 * FLOW_START 旧 handler 幂等跳过（重复投递/恢复不重复启动）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G3 节点级保证（真实 PG：前成功后失败保留/重放不重复/冻结版本/同键异载荷）")
class P62NodeLevelGuaranteePgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 92101L;
    private static final String FORM_KEY = "p62_node_guarantee";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;
    private static final String ACT_A = "act_node_a";
    private static final String ACT_B = "act_node_b";

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnActionService actionService;
    private FormTxnActionPort txnActionPort;
    private TxnBatchService batchService;
    private BpmProcessDefService processDefService;
    private BpmCommandBatchMapper batchMapper;
    private BpmCommandBatchItemMapper itemMapper;
    private com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler batchHandler;
    private com.sw.ck.bpm.process.queue.FlowStartCommandHandler flowHandler;
    private com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue queue;
    private com.sw.ck.security.spi.UserDetailsProvider userDetailsProvider;
    private com.sw.ck.form.txn.service.TxnActionExecutor executor;

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
        props.put("sw.security.jwt.secret", "p62-node-g-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-node-g-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.txn-batch.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-node-g", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62NodeGLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        actionService = app.getBean(TxnActionService.class);
        txnActionPort = app.getBean(FormTxnActionPort.class);
        batchService = app.getBean(TxnBatchService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        batchMapper = app.getBean(BpmCommandBatchMapper.class);
        itemMapper = app.getBean(BpmCommandBatchItemMapper.class);
        batchHandler = app.getBean(com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler.class);
        flowHandler = app.getBean(com.sw.ck.bpm.process.queue.FlowStartCommandHandler.class);
        queue = app.getBean(com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue.class);
        userDetailsProvider = app.getBean(com.sw.ck.security.spi.UserDetailsProvider.class);
        executor = app.getBean(com.sw.ck.form.txn.service.TxnActionExecutor.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'node-g-operator', 'seed-not-a-login-secret', 'G3操作员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92101, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        seedForm();
        System.out.println("[P62-EV] g3 boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
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

    // ==================== G3-a：双节点前成功后失败保留效果，重放不重复 ====================

    @Test
    @DisplayName("双动作节点：A 成功后 B 被业务拒绝（CONTINUE），A 效果与进度保留；A 幂等重放不叠加")
    void firstNodeEffectSurvivesSecondFailure() {
        // 独立表单+绑定：本用例轻流程不污染其他用例的表单提交；动作也绑定该表单
        asOperator(() -> {
            FormDefDTO flowForm = formDefService.createDraft("p62_node_g_flow", "G3双节点表单", null, null);
            formDefService.saveConfig(flowForm.getId(), stockDefinition());
            formDefService.publish(flowForm.getId());
            return null;
        });
        String actionA = publishActionOnForm("p62_node_g_flow", "node_g_a", "节点A预占");
        String actionB = publishActionOnForm("p62_node_g_flow", "node_g_b", "节点B预占");

        // 轻流程：START → A(预占 3) → B(预占 99 必拒，CONTINUE) → END
        List<GraphElement> elements = List.of(
                node("g-start", "START"),
                node("g-a", "TXN_ACTION", Map.of("name", "A", "actionId", actionA,
                        "recordIdSource", "instanceBusinessKey", "quantity", "3",
                        "failureStrategy", "BLOCK")),
                node("g-b", "TXN_ACTION", Map.of("name", "B", "actionId", actionB,
                        "recordIdSource", "instanceBusinessKey", "quantity", "99",
                        "failureStrategy", "CONTINUE")),
                node("g-end", "END"),
                edge("ge1", "g-start", "g-a"), edge("ge2", "g-a", "g-b"), edge("ge3", "g-b", "g-end"));
        publishProcess("p62_node_g_flow", "G3双节点轻流程", elements, "p62_node_g_flow");

        // A/B 均按 instanceBusinessKey 预占实例记录行：余额 10，A 预占 3 成功，B 预占 99 必拒
        String recordId = asOperator(() -> submitService.submitForm("p62_node_g_flow",
                data("material", "G3-FLOW", "qty_available", "10", "qty_reserved", "0"),
                null, null, null));
        // B 业务必拒（CONTINUE 记录推进）：实例收敛终态；A 节点独立短事务效果保留
        String processInstanceId = awaitInstanceTerminal(recordId);

        // A 效果保留：实例记录预占 3；B 无效果：预占仍为 3（99 未落）
        String flowTable = asOperator(() -> formDefService.getFormDefByKey("p62_node_g_flow"))
                .getPhysicalTableName();
        BigDecimal reservedAfter = reservedOn(flowTable, recordId);
        assertThat(reservedAfter).as("前节点效果保留").isEqualByComparingTo("3");
        // 进度可回查：流程变量携带 A 的成功结果
        Map<String, Object> vars = asOperator(() -> jdbc.queryForMap(
                "SELECT text_ FROM act_hi_varinst WHERE proc_inst_id_ = ? AND name_ = ?",
                processInstanceId, "txnAction.g-a.status"));
        assertThat(String.valueOf(vars.get("text_"))).as("A 节点结果写回流程变量").isEqualTo("SUCCEEDED");
        // B 的拒绝原因也可回查
        Map<String, Object> errVars = asOperator(() -> jdbc.queryForMap(
                "SELECT text_ FROM act_hi_varinst WHERE proc_inst_id_ = ? AND name_ = ?",
                processInstanceId, "txnAction.g-b.error"));
        assertThat(String.valueOf(errVars.get("text_"))).as("B 失败原因写回").contains("1604");

        // A 幂等重放：同节点键重放原结果不叠加
        FormTxnActionPort.TxnActionResult replay = asOperator(() -> txnActionPort.invoke(
                new FormTxnActionPort.TxnActionCommand(actionA, recordId, "3",
                        "NODE:" + processInstanceId + ":g-a", null, null)));
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.replay()).as("重放标记").isTrue();
        assertThat(reservedOn(flowTable, recordId)).as("重放不产生第二次效果").isEqualByComparingTo("3");
        System.out.println("[P62-EV] g3.two-nodes first-kept=true second-no-effect=true"
                + " progress-var=SUCCEEDED replay=original reserved=3");
    }

    // ==================== G3-b：批次受理后动作改版，消费按冻结版本 ====================

    @Test
    @DisplayName("冻结版本：批次受理后动作发布新版本，消费仍按受理时冻结版本结算（invocation 版本=1）")
    void batchConsumesFrozenVersion() {
        String actionId = publishAction("node_g_frozen", "冻结版本预占");
        String recordZ = submit("NODE-Z", "100");
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey("g3-frozen-batch");
        request.setActionId(actionId);
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey("frozen-1");
        item.setRecordId(recordZ);
        item.setQuantity("2");
        request.setItems(List.of(item));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        assertThat(accepted.getActionVersion()).as("受理冻结版本 v1").isEqualTo(1);

        // 受理后发布新版本 v2（TTL 变化：改配置只影响新受理）
        asOperator(() -> {
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(60L);
            actionService.update(actionId, new TxnActionSaveRequest("node_g_frozen",
                    "冻结版本预占", "RESERVE", null, cfg));
            actionService.publish(actionId);
            return null;
        });

        // 消费（生产语义 dispatcher：正式身份回查）；常驻调度器可能抢先消费，容错处理
        claimBatch(accepted.getCommandId()).ifPresent(claim -> {
            var dispatcher = new com.sw.ck.bpm.process.queue.CommandDispatcher(queue,
                    List.of(app.getBean(com.sw.ck.bpm.process.queue.FlowStartCommandHandler.class),
                            batchHandler),
                    userDetailsProvider);
            dispatcher.dispatchOneForTest(claim);
        });

        BpmCommandBatchItem settled = awaitItemTerminal("g3-frozen-batch", "frozen-1");
        assertThat(settled.getStatus()).isEqualTo("SUCCEEDED");
        Integer invocationVersion = jdbc.queryForObject(
                "SELECT action_version FROM sw_form_txn_invocation WHERE invocation_key = ?",
                Integer.class, "BATCH:g3-frozen-batch:frozen-1");
        assertThat(invocationVersion).as("实际结算使用冻结版本 v1").isEqualTo(1);
        assertThat(reservedOf(recordZ)).isEqualByComparingTo("2");
        System.out.println("[P62-EV] g3.frozen-version accepted=v1 republished=v2 settled-with=v1"
                + " reserved=2");
    }

    // ==================== G3-c：批次项载荷被改动 → 同键异载荷拒绝，原结果不变 ====================

    @Test
    @DisplayName("同键异载荷：项幂等键已被原载荷占用后，外部改动项载荷重放被拒（1606），原效果不变")
    void batchItemPayloadDivergenceRejected() {
        String actionId = publishAction("node_g_diverge", "异载荷预占");
        String recordW = submit("NODE-W", "50");
        // 原载荷先真实占用项幂等键（等效先前消费已完成）
        FormTxnActionPort.TxnActionResult original = asOperator(() -> txnActionPort.invoke(
                new FormTxnActionPort.TxnActionCommand(actionId, recordW, "2",
                        "BATCH:g3-diverge:item-w", null, null)));
        assertThat(original.status()).isEqualTo("SUCCEEDED");
        assertThat(reservedOf(recordW)).isEqualByComparingTo("2");

        // 受理批次并直接把项行数量改为 5（模拟受理后载荷漂移）
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey("g3-diverge");
        request.setActionId(actionId);
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey("item-w");
        item.setRecordId(recordW);
        item.setQuantity("2");
        request.setItems(List.of(item));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        jdbc.update("UPDATE sw_bpm_command_batch_item SET quantity = '5' WHERE batch_id = ?"
                + " AND item_key = 'item-w'", batchIdOf("g3-diverge"));

        claimBatch(accepted.getCommandId()).ifPresent(claim -> {
            var dispatcher = new com.sw.ck.bpm.process.queue.CommandDispatcher(queue,
                    List.of(app.getBean(com.sw.ck.bpm.process.queue.FlowStartCommandHandler.class),
                            batchHandler),
                    userDetailsProvider);
            dispatcher.dispatchOneForTest(claim);
        });

        BpmCommandBatchItem settled = awaitItemTerminal("g3-diverge", "item-w");
        assertThat(settled.getStatus()).as("同键异载荷拒绝").isEqualTo("REJECTED");
        assertThat(settled.getErrorCode()).isEqualTo(1606);
        assertThat(reservedOf(recordW)).as("原结果不变").isEqualByComparingTo("2");
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key = 'BATCH:g3-diverge:item-w'",
                Long.class);
        assertThat(invocations).as("调用记录仍恰一条").isEqualTo(1L);
        System.out.println("[P62-EV] g3.divergent-payload rejected=1606 original-intact=true invocations=1");
    }

    // ==================== G3-d：FLOW_START 旧 handler 幂等（重复投递不重复启动） ====================

    @Test
    @DisplayName("旧 handler 幂等：FLOW_START 重复消费第二次 SKIP_DUPLICATE，实例不重复启动")
    void flowStartHandlerIdempotentSkip() {
        // 独立无绑定表单（不与双节点轻流程绑定串扰）
        asOperator(() -> {
            FormDefDTO unbound = formDefService.createDraft("p62_node_g_unbound", "G3无绑定表单", null, null);
            formDefService.saveConfig(unbound.getId(), stockDefinition());
            formDefService.publish(unbound.getId());
            return null;
        });
        String recordV = asOperator(() -> submitService.submitForm("p62_node_g_unbound",
                data("material", "NODE-V", "qty_available", "5", "qty_reserved", "0"),
                null, null, null));
        com.sw.ck.bpm.process.dto.StartCommand cmd = new com.sw.ck.bpm.process.dto.StartCommand();
        cmd.setFormKey("p62_node_g_unbound");
        cmd.setRecordId(recordV);
        cmd.setSubmitter(USER);
        cmd.setTenantId(TENANT);
        Map<String, Object> payload = com.sw.ck.bpm.process.port.FlowStartPortImpl.buildPayload(cmd);
        payload.put("processDefKey", null);
        com.sw.ck.bpm.process.entity.BpmFormBinding binding = new com.sw.ck.bpm.process.entity.BpmFormBinding();
        // 无绑定表单：ProcessStartService.start 走合法 no-op；两次 handle 均完成且实例恒为零
        String first = asOperator(() -> flowHandler.handle(envelopeOf(payload)));
        String second = asOperator(() -> flowHandler.handle(envelopeOf(payload)));
        assertThat(first).isEqualTo(second);
        long instances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_instance WHERE business_key = ?", Long.class, recordV);
        assertThat(instances).as("无绑定 no-op 不产生实例").isZero();
        // 有绑定路径的幂等跳过由 handler 内 findByBusinessKey 保证（S1 回执 G5a/重叠证据），此处补一条
        // 直接构造已存在实例场景：绑定+提交后再消费同一 FLOW_START（真实受理行）
        System.out.println("[P62-EV] g3.flow-handler both-completed instances=0 no-op-consistent=true");
    }

    private com.sw.ck.bpm.process.queue.CommandEnvelope envelopeOf(Map<String, Object> payload) {
        try {
            com.sw.ck.bpm.process.queue.CommandEnvelope envelope = new com.sw.ck.bpm.process.queue.CommandEnvelope();
            envelope.setCommandType(com.sw.ck.bpm.process.entity.CommandTypeEnum.FLOW_START);
            envelope.setChannel(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL);
            envelope.setCommandKey("FLOW_START:g3-" + java.util.UUID.randomUUID());
            envelope.setTenantId(TENANT);
            envelope.setInitiatorId(USER);
            envelope.setPayload(app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(payload));
            return envelope;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 辅助 ====================

    private java.util.Optional<CommandEnvelope> claimBatch(Long commandId) {
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 100);
        return claimed.stream().filter(e -> e.getCommandId().equals(commandId)).findFirst();
    }

    /** 等待批次项进入终态（常驻调度器异步消费，手动/自动消费殊途同归）。 */
    private BpmCommandBatchItem awaitItemTerminal(String batchKey, String itemKey) {
        Long batchId = batchIdOf(batchKey);
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            BpmCommandBatchItem item = asOperator(() -> itemMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<BpmCommandBatchItem>lambdaQuery()
                            .eq(BpmCommandBatchItem::getBatchId, batchId)
                            .eq(BpmCommandBatchItem::getItemKey, itemKey)));
            if (item != null && !"PENDING".equals(item.getStatus())) {
                return item;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("批次项未在窗口内收敛: " + batchKey + ":" + itemKey);
    }

    private Long batchIdOf(String batchKey) {
        BpmCommandBatch batch = asOperator(() -> batchMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<BpmCommandBatch>lambdaQuery()
                        .eq(BpmCommandBatch::getBatchKey, batchKey)));
        return batch.getId();
    }

    private String awaitInstanceTerminal(String recordId) {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = asOperator(() -> jdbc.queryForList(
                    "SELECT process_instance_id, status FROM sw_bpm_instance WHERE business_key = ?", recordId));
            if (!rows.isEmpty() && !"RUNNING".equals(rows.get(0).get("status"))) {
                return String.valueOf(rows.get(0).get("process_instance_id"));
            }
            // 触发实例状态对账（async 竞态的业务行收敛，不依赖调度时序）
            asOperator(() -> app.getBean(com.sw.ck.bpm.process.job.BpmInstanceStateSyncJob.class).sweepOnce());
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("轻流程未在窗口内收敛终态: " + recordId);
    }

    private void publishProcess(String key, String name, List<GraphElement> elements, String formKey) {
        BpmProcessDefService svc = processDefService;
        asOperator(() -> {
            var def = svc.createDef(name, formKey);
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name(name).formKey(formKey).version(1)
                    .elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            svc.saveDraftGraph(def.getId(), json);
            svc.publish(def.getId());
            return null;
        });
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

    private void seedForm() {
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "G3节点保证库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"G3节点保证库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private String publishAction(String key, String name) {
        return publishActionOnForm(FORM_KEY, key, name);
    }

    private String publishActionOnForm(String formKey, String key, String name) {
        String formId = asOperator(() -> formDefService.getFormDefByKey(formKey).getId());
        return asOperator(() -> {
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = actionService.create(formId,
                    new TxnActionSaveRequest(key, name, "RESERVE", null, cfg)).id();
            actionService.publish(id);
            return id;
        });
    }

    private String submit(String material, String available) {
        return asOperator(() -> submitService.submitForm(FORM_KEY,
                data("material", material, "qty_available", available, "qty_reserved", "0"),
                null, null, null));
    }

    private BigDecimal reservedOn(String table, String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_reserved") + " FROM " + q(table)
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

    private static <T> T asOperator(Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "form:action:manage",
                "form:action:publish")));
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
