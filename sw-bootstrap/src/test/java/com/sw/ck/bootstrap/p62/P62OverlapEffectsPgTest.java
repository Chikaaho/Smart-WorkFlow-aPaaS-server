package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.queue.CommandDispatcher;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.port.FormTxnActionPort;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 复核02 G3a：默认 BLOCK 双节点失败保留 + 旧/新执行者真实重叠效果至多一次（真实 PG）。
 *
 * <p>覆盖 U03 剩余行为证据：双动作节点 A 成功后 B 被业务拒绝时按<b>默认 BLOCK</b> 策略
 * 终止（失败节点无成功效果、A 效果与进度保留可回查）；动作提交窗口与命令消费层的
 * 旧/新执行者<b>并发重叠</b>（两线程同一稳定幂等键同时进入真实执行路径，非顺序重放）
 * 实际业务效果至多一次；全部断言附真 PG 时序读回（调用行/效果行/预占台账/命令行
 * 同对象时间戳）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G3a 默认BLOCK双节点保留 + 执行者并发重叠效果至多一次（真实 PG）")
class P62OverlapEffectsPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 92401L;

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnActionService actionService;
    private FormTxnActionPort txnActionPort;
    private TxnBatchService batchService;
    private BpmProcessDefService processDefService;
    private PersistentBpmCommandQueue queue;
    private CommandDispatcher dispatcher;

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
        props.put("sw.security.jwt.secret", "p62-overlap-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-overlap-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.txn-batch.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-overlap", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62OverlapLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        actionService = app.getBean(TxnActionService.class);
        txnActionPort = app.getBean(FormTxnActionPort.class);
        batchService = app.getBean(TxnBatchService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        queue = app.getBean(PersistentBpmCommandQueue.class);
        dispatcher = app.getBean(CommandDispatcher.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'overlap-operator', 'seed-not-a-login-secret', '重叠演练员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92401, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        // 进程绑定表单（BLOCK 用例）与无绑定表单（并发重叠用例：不产生流程噪声）
        asOperator(() -> {
            seedForm("p62_overlap_flow");
            seedForm("p62_overlap_plain");
            return null;
        });
        System.out.println("[P62-EV] g3a overlap boot ok pgPort=" + pg.getPort());
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

    // ==================== G3a-1：默认 BLOCK 双节点，A 保留、B 无效果 ====================

    @Test
    @DisplayName("默认BLOCK：A成功(3)→B业务拒绝(99)→实例终止于B；A效果与进度保留，B无成功效果")
    void blockStrategyKeepsFirstNodeEffect() throws Exception {
        String actionA = publishActionOnForm("p62_overlap_flow", "overlap_a", "A预占");
        String actionB = publishActionOnForm("p62_overlap_flow", "overlap_b", "B预占");

        List<GraphElement> elements = List.of(
                node("o-start", "START"),
                node("o-a", "TXN_ACTION", Map.of("name", "A", "actionId", actionA,
                        "recordIdSource", "instanceBusinessKey", "quantity", "3")),
                node("o-b", "TXN_ACTION", Map.of("name", "B", "actionId", actionB,
                        "recordIdSource", "instanceBusinessKey", "quantity", "99")),
                node("o-end", "END"),
                edge("oe1", "o-start", "o-a"), edge("oe2", "o-a", "o-b"),
                edge("oe3", "o-b", "o-end"));
        BpmProcessDef def = asOperator(() -> processDefService.createDef("G3a默认BLOCK轻流程",
                "p62_overlap_flow"));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey(def.getProcessKey()).name("G3a默认BLOCK轻流程")
                .formKey("p62_overlap_flow").version(1).elements(elements).build();
        asOperator(() -> {
            processDefService.saveDraftGraph(def.getId(),
                    app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                            .writeValueAsString(graph));
            return null;
        });
        asOperator(() -> processDefService.publish(def.getId()));

        long submitAt = System.currentTimeMillis();
        String recordId = asOperator(() -> submitService.submitForm("p62_overlap_flow",
                data("material", "OVL-FLOW", "qty_available", "10", "qty_reserved", "0"),
                null, null, null));
        String flowTable = asOperator(() -> formDefService.getFormDefByKey("p62_overlap_flow"))
                .getPhysicalTableName();

        // B 按默认 BLOCK 终止：实例不收敛 APPROVED（终止于 B），A 效果保留
        long deadline = System.currentTimeMillis() + 120_000L;
        String instanceStatus = null;
        String processInstanceId = null;
        boolean bRejectedCommitted = false;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT process_instance_id, status FROM sw_bpm_instance WHERE business_key = ?",
                    recordId);
            if (!rows.isEmpty()) {
                processInstanceId = String.valueOf(rows.get(0).get("process_instance_id"));
                instanceStatus = String.valueOf(rows.get(0).get("status"));
                // A 进度可回查（独立短事务已提交）且 B 的拒绝记录（独立事务）已落
                if ("SUCCEEDED".equals(nodeVar(processInstanceId, "txnAction.o-a.status"))) {
                    Long rejectedRow = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key = ?"
                                    + " AND status = 'REJECTED' AND error_code = 1604",
                            Long.class, "NODE:" + processInstanceId + ":o-b");
                    bRejectedCommitted = rejectedRow != null && rejectedRow == 1L;
                    if (bRejectedCommitted) {
                        break;
                    }
                }
            }
            Thread.sleep(300);
        }
        assertThat(processInstanceId).as("流程实例存在").isNotBlank();
        String nodeAVar = nodeVar(processInstanceId, "txnAction.o-a.status");
        assertThat(nodeAVar).as("A 节点成功写回进度").isEqualTo("SUCCEEDED");
        assertThat(bRejectedCommitted).as("B 拒绝经独立短事务落账（1604 可回查）").isTrue();
        assertThat(instanceStatus).as("默认BLOCK：实例不伪报整体成功").isNotEqualTo("APPROVED");

        BigDecimal reserved = reservedOn(flowTable, recordId);
        assertThat(reserved).as("A 效果保留=3；B 无成功效果（99 未落）").isEqualByComparingTo("3");

        // 时序读回（真 PG 时钟）：调用行/命令行同对象时间戳
        String invocationTs = jdbc.queryForObject(
                "SELECT to_char(update_time,'YYYY-MM-DD HH24:MI:SS.MS') || '|' || status"
                        + " FROM sw_form_txn_invocation WHERE biz_record_id=?"
                        + " AND invocation_key LIKE 'NODE:%' ORDER BY update_time LIMIT 1",
                String.class, recordId);
        String commandTs = jdbc.queryForObject(
                "SELECT to_char(claimed_at,'YYYY-MM-DD HH24:MI:SS.MS') || '->'"
                        + " || to_char(finished_at,'YYYY-MM-DD HH24:MI:SS.MS') || '|' || status"
                        + " FROM sw_bpm_command WHERE command_key=?",
                String.class, "FLOW_START:" + recordId);
        String timing = "g3a.block recordId=" + recordId
                + " submitAt=" + submitAt
                + " instance=" + processInstanceId + " instanceStatus=" + instanceStatus
                + " nodeA=" + nodeAVar
                + " nodeB=REJECTED(committed,1604)"
                + " reserved=" + reserved
                + " invocationTime|status=" + invocationTs
                + " commandClaimed->Finished|status=" + commandTs;
        System.out.println("[P62-EV] " + timing);
        String evidenceDir = System.getProperty("p62.evidence.dir");
        Path timingFile = evidenceDir != null
                ? Path.of(evidenceDir, "g3a-block-timing.txt")
                : Path.of("target", "g3a-block-timing-"
                        + System.getProperty("p62.runId", String.valueOf(System.nanoTime())) + ".txt");
        Files.writeString(timingFile, timing + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private String nodeVar(String processInstanceId, String name) {
        try {
            return String.valueOf(jdbc.queryForObject(
                    "SELECT text_ FROM act_hi_varinst WHERE proc_inst_id_ = ? AND name_ = ?"
                            + " ORDER BY id_ DESC LIMIT 1", String.class, processInstanceId, name));
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== G3a-2：执行者并发重叠（节点键），效果至多一次 ====================

    @Test
    @DisplayName("并发重叠（动作提交层）：两执行者同节点键同时进入真实执行路径，业务效果恰一次")
    void concurrentNodeExecutorsProduceAtMostOneEffect() throws Exception {
        String actionId = publishActionOnForm("p62_overlap_plain", "overlap_c", "并发节点预占");
        String recordId = asOperator(() -> submitService.submitForm("p62_overlap_plain",
                data("material", "OVL-C", "qty_available", "10", "qty_reserved", "0"),
                null, null, null));
        String flowTable = asOperator(() -> formDefService.getFormDefByKey("p62_overlap_plain"))
                .getPhysicalTableName();
        String nodeKey = "NODE:overlap-concurrent:executor";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<FormTxnActionPort.TxnActionResult> invoker = () -> {
            start.await();
            return asOperator(() -> txnActionPort.invoke(
                    new FormTxnActionPort.TxnActionCommand(actionId, recordId, "3", nodeKey, null, null)))
                    .orElseThrow();
        };
        Future<FormTxnActionPort.TxnActionResult> f1 = pool.submit(invoker);
        // 两个执行者几乎同时进入（并发重叠，非顺序重放）
        Future<FormTxnActionPort.TxnActionResult> f2 = pool.submit(() -> {
            start.await();
            Thread.sleep(5);
            return asOperator(() -> txnActionPort.invoke(
                    new FormTxnActionPort.TxnActionCommand(actionId, recordId, "3", nodeKey, null, null)))
                    .orElseThrow();
        });
        Thread.sleep(50);
        start.countDown();
        FormTxnActionPort.TxnActionResult r1 = f1.get(60, TimeUnit.SECONDS);
        FormTxnActionPort.TxnActionResult r2 = f2.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // 效果至多一次：预占恰 3（不叠加）、调用行恰 1、且结果自洽（一方原执行/一方重放或冲突）
        BigDecimal reserved = reservedOn(flowTable, recordId);
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key=?", Long.class, nodeKey);
        assertThat(reserved).as("重叠下业务效果至多一次：预占=3").isEqualByComparingTo("3");
        assertThat(invocations).as("稳定幂等键恰一条调用记录").isEqualTo(1L);
        assertThat(List.of(r1.status(), r2.status())).as("两执行者均合法收敛")
                .doesNotContain("FAILED");
        boolean effectOnce = reserved.compareTo(BigDecimal.valueOf(3)) == 0 && invocations == 1L;
        String timing = "g3a.overlap-node recordId=" + recordId + " nodeKey=" + nodeKey
                + " r1=" + r1.status() + (r1.replay() ? "(replay)" : "(original)")
                + " r2=" + r2.status() + (r2.replay() ? "(replay)" : "(original)")
                + " reserved=" + reserved + " invocations=" + invocations
                + " invocationTs=" + jdbc.queryForObject(
                        "SELECT to_char(create_time,'YYYY-MM-DD HH24:MI:SS.MS') || '->'"
                                + " || to_char(update_time,'YYYY-MM-DD HH24:MI:SS.MS')"
                                + " FROM sw_form_txn_invocation WHERE invocation_key=?",
                        String.class, nodeKey)
                + " effectOnce=" + effectOnce;
        System.out.println("[P62-EV] " + timing);
        assertThat(effectOnce).as("重叠效果至多一次").isTrue();
    }

    // ==================== G3a-3：执行者并发重叠（命令消费层），效果至多一次 ====================

    @Test
    @DisplayName("并发重叠（命令消费层）：旧执行者与新领取者同批次命令并发消费，项效果恰一次")
    void concurrentCommandExecutorsProduceAtMostOneEffect() throws Exception {
        String actionId = publishActionOnForm("p62_overlap_plain", "overlap_d", "并发批次预占");
        String recordId = asOperator(() -> submitService.submitForm("p62_overlap_plain",
                data("material", "OVL-D", "qty_available", "10", "qty_reserved", "0"),
                null, null, null));
        String flowTable = asOperator(() -> formDefService.getFormDefByKey("p62_overlap_plain"))
                .getPhysicalTableName();
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey("overlap-batch-" + System.currentTimeMillis());
        request.setActionId(actionId);
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey("overlap-item-1");
        item.setRecordId(recordId);
        item.setQuantity("2");
        request.setItems(List.of(item));
        TxnBatchView view = asOperator(() -> batchService.submit(request));

        // 真实领取同一批次命令：两执行者持有同一 envelope 并发进入消费路径
        long deadline = System.currentTimeMillis() + 60_000L;
        CommandEnvelope envelope = null;
        while (System.currentTimeMillis() < deadline && envelope == null) {
            List<CommandEnvelope> claimed = asOperator(() ->
                    queue.claimDue(List.of(CommandChannelEnum.NORMAL), 10));
            envelope = claimed.stream()
                    .filter(e -> view.getCommandId().equals(e.getCommandId()))
                    .findFirst().orElse(null);
        }
        assertThat(envelope).as("批次命令已领取").isNotNull();
        final CommandEnvelope claimedEnvelope = envelope;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> oldExecutor = () -> {
            start.await();
            asOperator(() -> {
                dispatcher.dispatchOneForTest(claimedEnvelope);
                return null;
            });
            return null;
        };
        Callable<Void> newClaimant = () -> {
            start.await();
            Thread.sleep(10);
            asOperator(() -> {
                dispatcher.dispatchOneForTest(claimedEnvelope);
                return null;
            });
            return null;
        };
        Future<Void> f1 = pool.submit(oldExecutor);
        Future<Void> f2 = pool.submit(newClaimant);
        Thread.sleep(50);
        start.countDown();
        f1.get(60, TimeUnit.SECONDS);
        f2.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        // 重叠期间 claim-token 保护会拒绝失效执行者的命令结算（见运行日志"命令执行权已失效"），
        // 命令经退避重试收敛；项效果在首个 REQUIRES_NEW 内已恰好一次提交。
        long convergeDeadline = System.currentTimeMillis() + 60_000L;
        String commandStatus = null;
        while (System.currentTimeMillis() < convergeDeadline) {
            commandStatus = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command WHERE id=?", String.class, view.getCommandId());
            if ("COMPLETED".equals(commandStatus)) {
                break;
            }
            Thread.sleep(500);
        }

        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:"
                        + request.getBatchKey() + ":%'", Long.class);
        long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref = ?",
                Long.class, "BATCH:" + request.getBatchKey());
        BigDecimal reserved = reservedOn(flowTable, recordId);
        assertThat(commandStatus).as("命令经重试收敛 COMPLETED").isEqualTo("COMPLETED");
        assertThat(reserved).as("重叠消费下预占=2（不叠加）").isEqualByComparingTo("2");
        assertThat(invocations).as("项调用记录恰 1").isEqualTo(1L);
        assertThat(effects).as("命令效果权威恰 1").isEqualTo(1L);
        String timing = "g3a.overlap-command batchKey=" + request.getBatchKey()
                + " commandStatus=" + commandStatus
                + " invocations=" + invocations + " effects=" + effects + " reserved=" + reserved
                + " commandTs=" + jdbc.queryForObject(
                        "SELECT to_char(claimed_at,'YYYY-MM-DD HH24:MI:SS.MS') || '->'"
                                + " || to_char(finished_at,'YYYY-MM-DD HH24:MI:SS.MS') || '|' || status"
                                + " FROM sw_bpm_command WHERE command_key=?",
                        String.class, "BATCH:" + request.getBatchKey());
        System.out.println("[P62-EV] " + timing);
    }

    // ==================== 辅助 ====================

    private void seedForm(String formKey) {
        if (formDefService.getFormDefByKey(formKey) != null) {
            return;
        }
        FormDefDTO draft = formDefService.createDraft(formKey, "重叠演练表单", null, null);
        formDefService.saveConfig(draft.getId(), stockDefinition());
        formDefService.publish(draft.getId());
    }

    private String publishActionOnForm(String formKey, String name, String label) {
        return asOperator(() -> {
            String formId = formDefService.getFormDefByKey(formKey).getId();
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

    private BigDecimal reservedOn(String table, String recordId) {
        return jdbc.queryForObject(
                "SELECT qty_reserved FROM " + table + " WHERE id = ?", BigDecimal.class, recordId);
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

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"重叠演练库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private static <T> T asOperator(Callable<T> action) {
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
