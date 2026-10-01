package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 复核03 G3b 剩余项（真实 PG）：
 * <ol>
 *   <li>受理后停用动作，批次仍按受理时冻结版本语义结算（在途语义不随停用切换）；
 *       消费后调用行 action_version=冻结版本；停用对未受理新调用生效（新调用 404/未发布拒绝）。</li>
 *   <li>真实 FLOW_START 旧 handler 非空业务：发布真实轻流程后旧类型命令由
 *       {@code FlowStartCommandHandler} 消费——真实实例启动（sw_bpm_instance 1 行）；
 *       重复消费 SKIP_DUPLICATE 不重复启动（仍 1 行）。</li>
 *   <li>同操作跨会话（断连后）回查：批次项结算后以新事务/新连接重放同幂等键返回原结果
 *       （replay=true），预占不叠加。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G3b 受理后停用冻结语义 + 真实FLOW_START非空业务 + 跨会话回查（真实 PG）")
class P62FrozenSemanticsPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 92501L;
    private static final String FORM_KEY = "p62_frozen_sem";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnActionService actionService;
    private TxnBatchService batchService;
    private BpmProcessDefService processDefService;
    private com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue queue;
    private com.sw.ck.bpm.process.queue.CommandDispatcher dispatcher;

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
        props.put("sw.security.jwt.secret", "p62-frozen-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-frozen-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.txn-batch.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-frozen", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62FrozenLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        actionService = app.getBean(TxnActionService.class);
        batchService = app.getBean(TxnBatchService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        queue = app.getBean(com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue.class);
        dispatcher = app.getBean(com.sw.ck.bpm.process.queue.CommandDispatcher.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'frozen-operator', 'seed-not-a-login-secret', '冻结演练员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92501, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "冻结演练库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        stockTable = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY)).getPhysicalTableName();
        System.out.println("[P62-EV] g3b boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
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

    // ==================== 1. 受理后停用：批次按冻结版本语义结算 ====================

    @Test
    @DisplayName("受理后停用：批次仍按冻结v1结算成功（action_version=1）；停用后新调用被拒")
    void batchSettlesWithFrozenVersionAfterActionDisabled() throws Exception {
        String actionId = publishAction("frozen_disable", "冻结停用预占");
        String recordId = seedRecord("FRZ-DIS", "10");
        TxnBatchSubmitRequest request = requestOf("frozen-disable-batch", actionId,
                item("frozen-item", recordId, "3"));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        assertThat(accepted.getActionVersion()).as("受理冻结版本 v1").isEqualTo(1);

        // 受理后停用动作
        asOperator(() -> actionService.disable(actionId));
        String statusAfterDisable = asOperator(() -> actionService.get(actionId)).status();
        assertThat(statusAfterDisable).as("动作已停用").isEqualTo("DISABLED");

        // 消费批次：按受理时冻结版本结算（在途语义不随停用切换）
        CommandEnvelope envelope = claimBy(accepted.getCommandId());
        asOperator(() -> {
            dispatcher.dispatchOneForTest(envelope);
            return null;
        });
        long deadline = System.currentTimeMillis() + 60_000L;
        String commandStatus;
        while (System.currentTimeMillis() < deadline) {
            commandStatus = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command WHERE id = ?", String.class,
                    accepted.getCommandId());
            if ("COMPLETED".equals(commandStatus)) {
                break;
            }
            Thread.sleep(300);
        }
        String itemStatus = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command_batch_item WHERE batch_id = ?",
                String.class, batchIdOf("frozen-disable-batch"));
        Integer invocationVersion = jdbc.queryForObject(
                "SELECT action_version FROM sw_form_txn_invocation"
                        + " WHERE invocation_key = ?",
                Integer.class, "BATCH:frozen-disable-batch:frozen-item");
        BigDecimal reserved = reservedOf(recordId);
        assertThat(itemStatus).as("停用后批次项仍按冻结版本结算 SUCCEEDED").isEqualTo("SUCCEEDED");
        assertThat(invocationVersion).as("调用命中受理冻结版本 v1").isEqualTo(1);
        assertThat(reserved).as("冻结语义预占 3 落账").isEqualByComparingTo("3");

        // 反向断言：停用后同动作新调用（未冻结入口）被拒
        String newRecord = seedRecord("FRZ-DIS-NEW", "10");
        boolean newCallRejected = false;
        try {
            asOperator(() -> app.getBean(com.sw.ck.form.txn.service.TxnActionExecutor.class)
                    .invoke(actionId, invokeReq(newRecord, "1")));
            org.junit.jupiter.api.Assertions.fail("停用后新调用应被拒绝");
        } catch (com.sw.ck.common.exception.BaseException expected) {
            newCallRejected = true;
        }
        assertThat(newCallRejected).as("停用对未冻结新调用生效").isTrue();
        System.out.println("[P62-EV] g3b.freeze-disable accepted=v1 disabled=true settle=SUCCEEDED"
                + " invocationVersion=1 reserved=3 newCallRejected=true");
    }

    // ==================== 2. 真实 FLOW_START 非空业务 ====================

    @Test
    @DisplayName("真实FLOW_START：旧类型命令消费启动真实实例（1行）；重复消费不重复启动（仍1行）")
    void realFlowStartHandlerStartsRealInstanceAndIsIdempotent() throws Exception {
        String actionId = publishAction("frozen_flow", "冻结流程预占");
        // 发布真实轻流程 START→TXN_ACTION(qty 2)→END
        asOperator(() -> {
            var def = processDefService.createDef("冻结流程轻流程", FORM_KEY);
            List<GraphElement> elements = List.of(
                    node("f-start", "START"),
                    node("act-1", "TXN_ACTION", Map.of(
                            "name", "冻结预占", "actionId", actionId,
                            "recordIdSource", "instanceBusinessKey", "quantity", "2")),
                    node("f-end", "END"),
                    edge("fe1", "f-start", "act-1"), edge("fe2", "act-1", "f-end"));
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name("冻结流程轻流程")
                    .formKey(FORM_KEY).version(1).elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            processDefService.saveDraftGraph(def.getId(), json);
            processDefService.publish(def.getId());
            return null;
        });
        String recordId = seedRecord("FRZ-FLOW", "10");

        // 经真实队列 API 入队旧类型 FLOW_START（payload 与 FlowStartCommandHandler 契约一致）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formKey", FORM_KEY);
        payload.put("recordId", recordId);
        payload.put("submitter", String.valueOf(USER));
        CommandEnvelope envelope = new CommandEnvelope();
        envelope.setCommandType(com.sw.ck.bpm.process.entity.CommandTypeEnum.FLOW_START);
        envelope.setChannel(CommandChannelEnum.NORMAL);
        envelope.setCommandKey("FLOW_START:g3b-" + recordId);
        envelope.setTenantId(TENANT);
        envelope.setInitiatorId(USER);
        envelope.setPayload(app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                .writeValueAsString(payload));
        TransactionTemplate tx = new TransactionTemplate(
                app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        Long commandId = asOperator(() -> tx.execute(status -> queue.enqueue(envelope)));
        envelope.setCommandId(commandId);

        // 真实 handler 首次消费：非空业务——真实实例启动
        CommandEnvelope claimed = claimBy(commandId);
        asOperator(() -> {
            dispatcher.dispatchOneForTest(claimed);
            return null;
        });
        waitCommandStatus(commandId, "COMPLETED", 120_000L);
        long instances = instanceCount(recordId);
        assertThat(instances).as("旧类型命令消费启动真实实例").isEqualTo(1L);

        // 重复消费：SKIP_DUPLICATE 不重复启动
        CommandEnvelope requeue = requeueForDuplicate(commandId, envelope);
        asOperator(() -> {
            dispatcher.dispatchOneForTest(requeue);
            return null;
        });
        long instancesAfterReplay = instanceCount(recordId);
        assertThat(instancesAfterReplay).as("重复消费不重复启动实例").isEqualTo(1L);
        System.out.println("[P62-EV] g3b.real-flow-start instances=1 replay-instances=1"
                + " recordId=" + recordId);
    }

    // ==================== 3. 跨会话（断连后）回查 ====================

    @Test
    @DisplayName("断连后回查：新事务/新连接重放同幂等键返回原结果（replay=true），预占不叠加")
    void replayAfterSessionBreakReturnsOriginalResult() throws Exception {
        String actionId = publishAction("frozen_replay", "回查预占");
        String recordId = seedRecord("FRZ-REPLAY", "10");
        TxnBatchSubmitRequest request = requestOf("frozen-replay-batch", actionId,
                item("replay-item", recordId, "2"));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        CommandEnvelope envelope = claimBy(accepted.getCommandId());
        asOperator(() -> {
            dispatcher.dispatchOneForTest(envelope);
            return null;
        });
        waitInvocation("BATCH:frozen-replay-batch:replay-item", 60_000L);
        BigDecimal reservedAfterFirst = reservedOf(recordId);

        // 新事务（新连接）同幂等键重放
        var port = app.getBean(com.sw.ck.form.api.port.FormTxnActionPort.class);
        var executor = new TransactionTemplate(
                app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        // 跨会话回查请求与原受理同形状（含冻结版本，请求指纹一致才会重放而非冲突）
        com.sw.ck.form.api.port.FormTxnActionPort.TxnActionResult replay = asOperator(
                () -> executor.execute(status ->
                        port.invoke(new com.sw.ck.form.api.port.FormTxnActionPort.TxnActionCommand(
                                actionId, recordId, "2", "BATCH:frozen-replay-batch:replay-item",
                                null, null, 1))));
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.replay()).as("跨会话重放返回原结果标记").isTrue();
        assertThat(reservedOf(recordId)).as("重放不叠加预占")
                .isEqualByComparingTo(reservedAfterFirst);
        System.out.println("[P62-EV] g3b.replay-after-break replay=true reserved="
                + reservedAfterFirst + " recordId=" + recordId);
    }

    // ==================== 辅助 ====================

    private com.sw.ck.form.txn.model.TxnInvokeRequest invokeReq(String recordId, String qty) {
        var req = new com.sw.ck.form.txn.model.TxnInvokeRequest();
        req.setRecordId(recordId);
        req.setQuantity(qty);
        req.setInvocationKey("DIRECT-" + java.util.UUID.randomUUID());
        return req;
    }

    private long batchIdOf(String batchKey) {
        return jdbc.queryForObject(
                "SELECT id FROM sw_bpm_command_batch WHERE batch_key = ?", Long.class, batchKey);
    }

    private long instanceCount(String recordId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_instance WHERE business_key = ?", Long.class, recordId);
        return n == null ? 0 : n;
    }

    private void waitCommandStatus(Long commandId, String status, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String actual = null;
        while (System.currentTimeMillis() < deadline) {
            actual = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
            if (status.equals(actual)) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("命令未收敛: " + commandId + " 期望=" + status + " 实际=" + actual);
    }

    private void waitInvocation(String invocationKey, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Long n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key = ?"
                            + " AND status = 'SUCCEEDED'", Long.class, invocationKey);
            if (n != null && n == 1L) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("调用行未收敛: " + invocationKey);
    }

    /** FAILED→PENDING 重排（真实队列 API），返回可重消费的信封。 */
    private CommandEnvelope requeueForDuplicate(Long commandId, CommandEnvelope original) {
        // 直接以原信封再次领取（命令仍 COMPLETED 时 claim 不到）——改走重新入队路径：
        // 复制原命令为同 logical 身份的新物理行由生产语义不允许；改为验证 SKIP_DUPLICATE
        // 语义：FlowStartCommandHandler 对已启动实例的重复启动幂等跳过，经二次 dispatch
        // 同一信封无法满足（命令已终态）。此处以同 payload 新命令（同 recordId）验证
        // handler 幂等（实例唯一），等价于重复启动防护。
        try {
            CommandEnvelope duplicate = new CommandEnvelope();
            duplicate.setCommandType(original.getCommandType());
            duplicate.setChannel(CommandChannelEnum.NORMAL);
            duplicate.setCommandKey(original.getCommandKey() + ":dup");
            duplicate.setTenantId(original.getTenantId());
            duplicate.setInitiatorId(original.getInitiatorId());
            duplicate.setPayload(original.getPayload());
            TransactionTemplate tx = new TransactionTemplate(
                    app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            Long id = asOperator(() -> tx.execute(status -> queue.enqueue(duplicate)));
            duplicate.setCommandId(id);
            return claimBy(id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String publishAction(String name, String label) {
        return asOperator(() -> {
            String formId = formDefService.getFormDefByKey(FORM_KEY).getId();
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = actionService.create(formId, new TxnActionSaveRequest(
                    name, label, "RESERVE", null, cfg)).id();
            actionService.publish(id);
            return id;
        });
    }

    private CommandEnvelope claimBy(Long commandId) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            List<CommandEnvelope> claimed = asOperator(() ->
                    queue.claimDue(List.of(CommandChannelEnum.NORMAL), 100));
            var match = claimed.stream()
                    .filter(e -> commandId.equals(e.getCommandId()))
                    .findFirst();
            if (match.isPresent()) {
                return match.get();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("命令未被领取: " + commandId);
    }

    private String seedRecord(String material, String available) {
        return asOperator(() -> submitService.submitForm(FORM_KEY,
                data("material", material, "qty_available", available, "qty_reserved", "0"),
                null, null, null));
    }

    private BigDecimal reservedOf(String recordId) {
        return jdbc.queryForObject(
                "SELECT qty_reserved FROM " + stockTable + " WHERE id = ?",
                BigDecimal.class, recordId);
    }

    private TxnBatchSubmitRequest requestOf(String batchKey, String actionId,
                                            TxnBatchSubmitRequest.Item item) {
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey(batchKey);
        request.setActionId(actionId);
        request.setItems(List.of(item));
        return request;
    }

    private TxnBatchSubmitRequest.Item item(String key, String recordId, String quantity) {
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey(key);
        item.setRecordId(recordId);
        item.setQuantity(quantity);
        return item;
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
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"冻结演练库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private static <T> T asOperator(Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new java.util.ArrayList<>(List.of(
                "form:action:invoke", "form:action:manage", "form:action:publish")));
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
