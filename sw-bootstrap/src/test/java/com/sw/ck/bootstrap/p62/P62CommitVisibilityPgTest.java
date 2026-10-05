package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.service.TxnActionExecutor;
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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 提交后独立读取可见性与批项等待口径（复核08 RA02b1/RA02b2 最小有界行为证据）。
 *
 * <p>RA02b1：同一效果提交后对独立读取可见——用独立 JDBC 连接（不经应用连接池）轮询
 * sw_form_txn_invocation 至 SUCCEEDED 首次可见，记录“受理（调用进入）到该次观察”作为上界，
 * 采样误差=轮询间隔。有限新样本只证明该样本范围，不重证既有窗口 P99。</p>
 *
 * <p>RA02b2：批项最大等待起点=该项随批次被持久受理（submit 事务提交完成时刻），终点=项
 * 效果对独立读取首次可见；命令队列排队完整计入（不排除），命令领取/完成时点（DB 时钟）另列对照。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 提交后可见性独立读取与批项等待口径（真实 PG 端到端）")
class P62CommitVisibilityPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91003L;
    private static final String FORM_KEY = "p62_commit_vis_stock";
    private static final int POLL_INTERVAL_MS = 25;
    private static final int VISIBILITY_TIMEOUT_MS = 60_000;

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String pgUrl;
    private static String stockTable;

    private FormDefService formDefService;
    private TxnActionExecutor actionExecutor;
    private TxnBatchService batchService;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-commit-vis-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-commit-vis-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.txn-batch.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-commit-vis", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62CommitVisLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        actionExecutor = app.getBean(TxnActionExecutor.class);
        batchService = app.getBean(TxnBatchService.class);

        // 发起人 + 管理员角色（role 2 已由 baseline/R 迁移获得 form:action:invoke 授权）
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'commit-vis-initiator', 'seed-not-a-login-secret', '提交可见性发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91003, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);

        seedStockForm();
        System.out.println("[P62-EV] commit-vis boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
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

    /** RA02b1：动作调用的受理进入→效果提交后独立读取首次可见上界（n=6，采样误差≤轮询间隔）。 */
    @Test
    @DisplayName("RA02b1 提交后可见性：6 次动作调用各自独立连接首次可见上界（零丢失）")
    void singleInvokeCommitVisibilityByIndependentRead() throws Exception {
        String actionId = publishAction("commit_vis_reserve");
        List<String> records = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            records.add(submit("VIS-" + i, "10"));
        }
        List<String> keys = new ArrayList<>();
        List<Long> enteredAt = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            TxnInvokeRequest req = new TxnInvokeRequest();
            String key = "VIS:commit-vis-01:item-" + (i + 1);
            req.setInvocationKey(key);
            req.setRecordId(records.get(i));
            req.setQuantity("2");
            keys.add(key);
            long entered = System.currentTimeMillis();
            TxnInvokeResult result = asOperator(() -> actionExecutor.invoke(actionId, req));
            assertThat(result.status()).isEqualTo("SUCCEEDED");
            enteredAt.add(entered);
        }
        List<Long> upperBounds = new ArrayList<>();
        try (Connection reader = independentReader()) {
            for (int i = 0; i < keys.size(); i++) {
                long visibleAt = awaitVisible(reader, keys.get(i));
                upperBounds.add(visibleAt - enteredAt.get(i));
            }
        }
        long max = upperBounds.stream().max(Long::compare).orElseThrow();
        System.out.println("[P62-EV] commit-vis single n=" + keys.size()
                + " upper_bounds_ms=" + upperBounds + " max=" + max + "ms"
                + " poll_interval=" + POLL_INTERVAL_MS + "ms"
                + " scope=有限新样本（本次6例），不重证既有窗口P99");
        assertThat(upperBounds).hasSize(6);
        assertThat(max).as("该样本范围内受理进入→提交后独立可见上界").isLessThan(30_000L);
    }

    /** RA02b2：批项等待口径（起点=随批次持久受理，终点=项效果独立首次可见；命令队列排队完整计入）。 */
    @Test
    @DisplayName("RA02b2 批项等待：两并存批次 25 项受理→独立可见完整等待（零丢失/零重复）")
    void batchItemWaitIncludesFullQueueing() throws Exception {
        String actionId = publishAction("batch_wait_reserve");
        List<String> recordsA = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            recordsA.add(submit("WAITA-" + i, "1"));
        }
        List<String> recordsB = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            recordsB.add(submit("WAITB-" + i, "1"));
        }
        // 受理时点（wall-clock）=submit 事务提交完成时刻；批B紧随批A受理，命令同队列竞争（并存合法工作）
        long acceptedA = System.currentTimeMillis();
        asOperator(() -> batchService.submit(batchRequest("commit-vis-batch-a", actionId, recordsA, "wa")));
        long acceptedB = System.currentTimeMillis();
        asOperator(() -> batchService.submit(batchRequest("commit-vis-batch-b", actionId, recordsB, "wb")));

        Map<String, Long> acceptedByKey = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String key = "BATCH:commit-vis-batch-a:wa-" + i;
            keys.add(key);
            acceptedByKey.put(key, acceptedA);
        }
        for (int i = 1; i <= 5; i++) {
            String key = "BATCH:commit-vis-batch-b:wb-" + i;
            keys.add(key);
            acceptedByKey.put(key, acceptedB);
        }
        List<Long> waits = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try (Connection reader = independentReader()) {
            long deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT_MS;
            while (seen.size() < keys.size() && System.currentTimeMillis() < deadline) {
                for (String key : keys) {
                    if (seen.contains(key)) {
                        continue;
                    }
                    if (isVisible(reader, key)) {
                        seen.add(key);
                        waits.add(System.currentTimeMillis() - acceptedByKey.get(key));
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        assertThat(seen).as("25 项效果全部对独立读取可见（零丢失）").hasSize(keys.size());
        Long distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT invocation_key) FROM sw_form_txn_invocation "
                        + "WHERE invocation_key LIKE 'BATCH:commit-vis-batch-%'",
                Long.class);
        assertThat(distinct).as("invocation_key 零重复").isEqualTo(25L);
        Long succeeded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation "
                        + "WHERE invocation_key LIKE 'BATCH:commit-vis-batch-%' AND status = 'SUCCEEDED'",
                Long.class);
        assertThat(succeeded).as("25 项全部成功结算（结果不丢失）").isEqualTo(25L);
        long max = waits.stream().max(Long::compare).orElseThrow();
        List<Long> sorted = new ArrayList<>(waits);
        sorted.sort(Long::compare);
        long median = sorted.get(sorted.size() / 2);
        // 命令排队/执行分段对照（DB 时钟，不参与上界计算）
        Map<String, Object> cmdA = jdbc.queryForMap(
                "SELECT create_time, claimed_at, finished_at FROM sw_bpm_command WHERE logical_command_id = ?",
                "BATCH:commit-vis-batch-a");
        Map<String, Object> cmdB = jdbc.queryForMap(
                "SELECT create_time, claimed_at, finished_at FROM sw_bpm_command WHERE logical_command_id = ?",
                "BATCH:commit-vis-batch-b");
        System.out.println("[P62-EV] commit-vis batch items=" + keys.size()
                + " wait_ms max=" + max + " median=" + median
                + " poll_interval=" + POLL_INTERVAL_MS + "ms"
                + " queueing_included=true (起点=随批次持久受理；终点=项效果独立首次可见)"
                + " batchA_cmd_create=" + cmdA.get("create_time") + " claimed=" + cmdA.get("claimed_at")
                + " finished=" + cmdA.get("finished_at")
                + " batchB_cmd_create=" + cmdB.get("create_time") + " claimed=" + cmdB.get("claimed_at")
                + " finished=" + cmdB.get("finished_at") + " (排队/执行分段对照，DB时钟)");
        assertThat(max).as("该样本范围内批项受理→执行可见完整等待（含排队）").isLessThan(30_000L);
    }

    // ==================== 独立读取与种子 ====================

    /** 独立 JDBC 连接：不经应用连接池，验证提交后对其他会话可见。 */
    private Connection independentReader() throws Exception {
        return DriverManager.getConnection(pgUrl, "postgres", "postgres");
    }

    private boolean isVisible(Connection reader, String invocationKey) throws Exception {
        try (PreparedStatement ps = reader.prepareStatement(
                "SELECT status FROM sw_form_txn_invocation WHERE invocation_key = ? AND tenant_id = ? AND deleted = 0")) {
            ps.setString(1, invocationKey);
            ps.setLong(2, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "SUCCEEDED".equals(rs.getString(1));
            }
        }
    }

    /** 轮询至该效果首次对独立读取可见（SUCCEEDED），返回观察 wall-clock。 */
    private long awaitVisible(Connection reader, String invocationKey) throws Exception {
        long deadline = System.currentTimeMillis() + VISIBILITY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (isVisible(reader, invocationKey)) {
                return System.currentTimeMillis();
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("可见性超时: " + invocationKey);
    }

    private TxnBatchSubmitRequest batchRequest(String batchKey, String actionId,
                                               List<String> records, String itemPrefix) {
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey(batchKey);
        request.setActionId(actionId);
        List<TxnBatchSubmitRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            items.add(item(itemPrefix + "-" + (i + 1), records.get(i), "1"));
        }
        request.setItems(items);
        return request;
    }

    private void seedStockForm() {
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P62提交可见性库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62提交可见性库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private String publishAction(String key) {
        String formId = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY).getId());
        return asOperator(() -> {
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            com.sw.ck.form.txn.service.TxnActionService actionService =
                    app.getBean(com.sw.ck.form.txn.service.TxnActionService.class);
            String id = actionService.create(formId,
                    new TxnActionSaveRequest(key, "提交可见性预占", "RESERVE", null, cfg)).id();
            actionService.publish(id);
            return id;
        });
    }

    private String submit(String material, String available) {
        return asOperator(() -> {
            com.sw.ck.form.service.FormSubmitService submitService =
                    app.getBean(com.sw.ck.form.service.FormSubmitService.class);
            return submitService.submitForm(FORM_KEY,
                    data("material", material, "qty_available", available, "qty_reserved", "0"),
                    null, null, null);
        });
    }

    private TxnBatchSubmitRequest.Item item(String key, String recordId, String quantity) {
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey(key);
        item.setRecordId(recordId);
        item.setQuantity(quantity);
        return item;
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
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke")));
        try {
            LoginUserHolder.set(user);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            LoginUserHolder.set(previous);
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
