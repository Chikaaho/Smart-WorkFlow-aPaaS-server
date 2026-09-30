package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 分级执行 S3：后台批量真实 PostgreSQL 端到端证据（U02/U07）。
 * <p>
 * 真实链路：批量受理（同事务持久化批次/项/命令）→ 命令调度器（正式身份回查）消费
 * BATCH_INVOKE → 逐项独立事务经受控 Port 真实调用已发布动作 → 逐项持久结果
 * （部分成功/部分拒绝可定位）→ 批次结算与效果权威账本 → 批次重放返回原批次且不重做已成功项。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 S3 后台批量 PG 端到端（受理→逐项真实动作→部分失败→批次重放）")
class P62BatchInvokePgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91002L;
    private static final String FORM_KEY = "p62_batch_stock";
    private static final String BATCH_KEY = "p62-batch-e2e-01";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private FormDefService formDefService;
    private TxnActionService actionService;
    private TxnBatchService batchService;

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
        props.put("sw.security.jwt.secret", "p62-batch-e2e-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-batch-e2e-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-batch-e2e", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62BatchE2eLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        actionService = app.getBean(TxnActionService.class);
        batchService = app.getBean(TxnBatchService.class);

        // 发起人 + 管理员角色（role 2 已由 baseline/R 迁移获得 form:action:invoke 授权）
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'batch-e2e-initiator', 'seed-not-a-login-secret', '批量发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91002, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);

        seedStockForm();
        System.out.println("[P62-EV] s3.e2e boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
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

    @Test
    @DisplayName("端到端：受理→调度消费→逐项真实预占（1 成功 1 业务拒绝可定位）→批次结算→重放返回原批次不重做")
    void batchEndToEndPartialFailureAndReplay() {
        String actionId = publishAction("batch_e2e_reserve");

        // 记录 A 余额 10（可成功预占 2）；记录 B 余额 1（预占 5 业务拒绝 1604）
        String recordA = submit("BATCH-A", "10");
        String recordB = submit("BATCH-B", "1");

        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey(BATCH_KEY);
        request.setActionId(actionId);
        request.setItems(List.of(item("item-a", recordA, "2"), item("item-b", recordB, "5")));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        assertThat(accepted.isReplay()).as("首次受理非重放").isFalse();
        assertThat(accepted.getStatus()).isEqualTo("PENDING");
        assertThat(accepted.getCommandId()).isNotNull();

        // 命令调度器消费（正式身份回查）→ 逐项独立事务真实调用动作
        TxnBatchView settled = awaitBatchSettled(BATCH_KEY);
        assertThat(settled.getStatus()).as("部分失败批次终态").isEqualTo("PARTIALLY_FAILED");
        assertThat(settled.getSucceededCount()).isEqualTo(1);
        assertThat(settled.getFailedCount()).isEqualTo(1);
        TxnBatchView.Item itemA = itemOf(settled, "item-a");
        TxnBatchView.Item itemB = itemOf(settled, "item-b");
        assertThat(itemA.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(itemA.getInvocationId()).isNotBlank();
        assertThat(itemB.getStatus()).as("业务拒绝可独立定位").isEqualTo("REJECTED");
        assertThat(itemB.getErrorCode()).isEqualTo(1604);

        // 逐项业务效果：A 真实预占 2，B 无效果
        assertThat(reservedOf(recordA)).as("成功项真实预占 2").isEqualByComparingTo("2");
        assertThat(reservedOf(recordB)).as("拒绝项无业务效果").isEqualByComparingTo("0");
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ? AND invocation_key LIKE 'BATCH:%'",
                Long.class, actionId);
        assertThat(invocations).as("两项各恰一条调用记录（含拒绝）").isEqualTo(2L);

        // 命令完成 + 效果权威账本
        Map<String, Object> command = jdbc.queryForMap(
                "SELECT status, result FROM sw_bpm_command WHERE id = ?", accepted.getCommandId());
        assertThat(command.get("status")).isEqualTo("COMPLETED");
        assertThat(String.valueOf(command.get("result"))).contains("PARTIALLY_FAILED");
        Long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE command_id = ? AND biz_ref = ?",
                Long.class, accepted.getCommandId(), "BATCH:" + BATCH_KEY);
        assertThat(effects).as("批次效果权威账本恰一条").isEqualTo(1L);

        // 批次重放：同键返回原批次、不新建命令、已成功项不重做
        TxnBatchView replay = asOperator(() -> batchService.submit(request));
        assertThat(replay.isReplay()).as("批次重放返回原批次").isTrue();
        assertThat(replay.getCommandId()).isEqualTo(accepted.getCommandId());
        long commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE logical_command_id = ?",
                Long.class, "BATCH:" + BATCH_KEY);
        assertThat(commands).as("重放不新增命令").isEqualTo(1L);
        assertThat(reservedOf(recordA)).as("重放不重做已成功项").isEqualByComparingTo("2");
        assertThat(reservedOf(recordB)).isEqualByComparingTo("0");
        System.out.println("[P62-EV] s3.e2e end-to-end accepted=1 settled=PARTIALLY_FAILED"
                + " itemA=SUCCEEDED(itemB=REJECTED/1604) reservedA=2 effect=1"
                + " replay=original commands=1 no-redo=true");
    }

    // ==================== 等待与查询 ====================

    private TxnBatchView awaitBatchSettled(String batchKey) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            TxnBatchView view = asOperator(() -> batchService.get(batchKey));
            if (!"PENDING".equals(view.getStatus()) && !"PROCESSING".equals(view.getStatus())) {
                return view;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("60s 内批次未收敛: " + asOperator(() -> batchService.get(batchKey)));
    }

    private TxnBatchView.Item itemOf(TxnBatchView view, String itemKey) {
        return view.getItems().stream().filter(i -> itemKey.equals(i.getItemKey()))
                .findFirst().orElseThrow();
    }

    // ==================== 种子与通用 ====================

    private void seedStockForm() {
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P62批量库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62批量库存\",\"fields\":["
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
            String id = actionService.create(formId,
                    new TxnActionSaveRequest(key, "批量端到端预占", "RESERVE", null, cfg)).id();
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
