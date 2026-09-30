package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.queue.BpmCommandHandler;
import com.sw.ck.bpm.process.queue.CommandDispatcher;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandBatchMapper;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.port.FormTxnActionPort;
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
 * P62 分级执行 S6：隔离升级/回退演练（U06 兼容合同，真实 PostgreSQL）。
 * <p>
 * 合同：本阶段不支持旧/新消费者同时处理新类型——旧消费者（不含 BATCH_INVOKE
 * 处理器的调度器）对新类型命令显式失败保留（不静默吞、不误执行、历史可查）；
 * 停止旧消费者后协调升级（全部消费者含新处理器）再消费成功；中断回退时
 * 已产生效果保留、无重复效果，恢复升级后只补剩余项。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 S6 U06 隔离升级/回退演练（真实 PG：旧消费者显式失败/协调升级/回退无重复效果）")
class P62CompatRollbackPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91004L;
    private static final String FORM_KEY = "p62_compat_stock";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private FormDefService formDefService;
    private TxnActionService actionService;
    private TxnBatchService batchService;
    private BpmCommandMapper commandMapper;
    private BpmCommandBatchMapper batchMapper;
    private FormTxnActionPort txnActionPort;
    private PersistentBpmCommandQueue queue;

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
        props.put("sw.security.jwt.secret", "p62-compat-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-compat-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-compat", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62CompatLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        actionService = app.getBean(TxnActionService.class);
        batchService = app.getBean(TxnBatchService.class);
        commandMapper = app.getBean(BpmCommandMapper.class);
        batchMapper = app.getBean(BpmCommandBatchMapper.class);
        txnActionPort = app.getBean(FormTxnActionPort.class);
        queue = app.getBean(PersistentBpmCommandQueue.class);

        // 管理员操作身份（role 2 已含 form:action:invoke）
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'compat-operator', 'seed-not-a-login-secret', '兼容演练操作员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91004, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);

        seedStockForm();
        System.out.println("[P62-EV] s6.compat boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
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
    @DisplayName("演练：旧消费者对新类型显式失败保留→协调升级消费成功→回退无重复效果→恢复补剩余")
    void legacyConsumerIsolationAndCoordinatedUpgrade() {
        String actionId = publishAction("compat_reserve", "兼容演练预占");
        String recordA = submit("COMPAT-A", "50");
        String recordB = submit("COMPAT-B", "50");

        // —— 受理 2 项批次（新类型 BATCH_INVOKE 命令）——
        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey("compat-batch-01");
        request.setActionId(actionId);
        request.setItems(List.of(item("item-a", recordA, "3"), item("item-b", recordB, "4")));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        assertThat(accepted.isReplay()).isFalse();
        Long commandId = accepted.getCommandId();
        assertThat(commandId).isNotNull();

        // —— 旧消费者（仅注册 FLOW_START 处理器，等效旧版本调度器）：新类型显式失败保留 ——
        CommandEnvelope legacyClaimed = claimBy(commandId);
        com.sw.ck.bpm.process.queue.CommandDispatcher legacyDispatcher =
                new CommandDispatcher(queue, List.of(new LegacyOnlyFlowStartHandler()));
        legacyDispatcher.dispatchOneForTest(legacyClaimed);
        BpmCommand legacyFailed = asOperator(() -> commandMapper.selectById(commandId));
        assertThat(legacyFailed.getStatus()).as("旧消费者对新类型显式失败保留（不静默吞）")
                .isEqualTo("FAILED");
        assertThat(legacyFailed.getFailureReason()).contains("无命令处理器");
        assertThat(reservedOf(recordA)).as("旧消费者不误执行：无业务效果").isEqualByComparingTo("0");
        assertThat(reservedOf(recordB)).isEqualByComparingTo("0");
        Long effectsAfterLegacy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE command_id = ?", Long.class, commandId);
        assertThat(effectsAfterLegacy).as("旧消费者不产生效果权威").isZero();
        // 历史可查：批次/项/命令受理事实保留
        TxnBatchView stillThere = asOperator(() -> batchService.get("compat-batch-01"));
        assertThat(stillThere.getStatus()).isEqualTo("PENDING");
        assertThat(stillThere.getItems()).hasSize(2);

        // —— 协调升级（旧消费者停止后，全量处理器的新调度器）：FAILED 命令重排入队后消费成功 ——
        CommandEnvelope requeue = new CommandEnvelope();
        requeue.setCommandId(commandId);
        requeue.setTenantId(TENANT);
        requeue.setCommandKey("BATCH:compat-batch-01");
        asOperator(() -> app.getBean(org.springframework.transaction.support.TransactionTemplate.class)
                .execute(status -> queue.requeueFailed(requeue)));
        assertThat(asOperator(() -> commandMapper.selectById(commandId)).getStatus()).isEqualTo("PENDING");
        CommandEnvelope upgradedClaim = claimBy(commandId);
        // 协调升级后的新消费者按生产语义装配：正式身份 SPI 回查（回查最新权限，不沿用受理快照）
        com.sw.ck.bpm.process.queue.CommandDispatcher upgradedDispatcher =
                new com.sw.ck.bpm.process.queue.CommandDispatcher(queue, upgradedHandlers(),
                        app.getBean(com.sw.ck.security.spi.UserDetailsProvider.class));
        upgradedDispatcher.dispatchOneForTest(upgradedClaim);
        assertThat(asOperator(() -> commandMapper.selectById(commandId)).getStatus())
                .as("升级后消费完成").isEqualTo("COMPLETED");
        assertThat(reservedOf(recordA)).as("item-a 真实预占 3").isEqualByComparingTo("3");
        assertThat(reservedOf(recordB)).as("item-b 真实预占 4").isEqualByComparingTo("4");
        TxnBatchView settled = asOperator(() -> batchService.get("compat-batch-01"));
        assertThat(settled.getStatus()).isEqualTo("COMPLETED");

        // —— 回退演练：批次重放（等效回退后重复受理/重放）返回原批次，无重复效果 ——
        TxnBatchView replay = asOperator(() -> batchService.submit(request));
        assertThat(replay.isReplay()).as("回退后重放返回原批次").isTrue();
        assertThat(reservedOf(recordA)).as("回退无重复效果").isEqualByComparingTo("3");
        assertThat(reservedOf(recordB)).isEqualByComparingTo("4");
        long commandsForKey = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE logical_command_id = 'BATCH:compat-batch-01'",
                Long.class);
        assertThat(commandsForKey).as("历史唯一：不新增命令").isEqualTo(1L);
        System.out.println("[P62-EV] s6.compat legacy=FAILED-kept no-effect=true upgrade=COMPLETED"
                + " reservedA=3/reservedB=4 replay=original commands=1 history=queryable");
    }

    @Test
    @DisplayName("演练：升级窗口内已成功的项（真实效果已发生），恢复升级重入只补剩余项不重做")
    void interruptedUpgradeThenResumeOnlyPending() {
        String actionId = publishAction("compat_reserve2", "兼容演练预占二");
        String recordC = submit("COMPAT-C", "20");
        String recordD = submit("COMPAT-D", "20");

        TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
        request.setBatchKey("compat-batch-02");
        request.setActionId(actionId);
        request.setItems(List.of(item("item-c", recordC, "2"), item("item-d", recordD, "2")));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        Long commandId = accepted.getCommandId();

        // 模拟升级窗口中断前 item-d 已产生真实业务效果（稳定项幂等键已占用），
        // 但项结果行尚未写终态（中断点）
        asOperator(() -> txnActionPort.invoke(new com.sw.ck.form.api.port.FormTxnActionPort.TxnActionCommand(
                actionId, recordD, "2", "BATCH:compat-batch-02:item-d", null, null, 1)));
        assertThat(reservedOf(recordD)).isEqualByComparingTo("2");

        // 恢复升级：重入消费只处理 PENDING 项（item-c）；item-d 经动作内核幂等重放原结果，不叠加
        CommandEnvelope claim = claimBy(commandId);
        com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler handler =
                app.getBean(com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler.class);
        asOperator(() -> {
            handler.handle(claim);
            return null;
        });

        TxnBatchView view = asOperator(() -> batchService.get("compat-batch-02"));
        assertThat(view.getStatus()).isEqualTo("COMPLETED");
        assertThat(view.getSucceededCount()).as("已成功项保留且补齐剩余").isEqualTo(2);
        assertThat(reservedOf(recordC)).as("剩余项补齐").isEqualByComparingTo("2");
        assertThat(reservedOf(recordD)).as("已发生效果不重复").isEqualByComparingTo("2");
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ?"
                        + " AND invocation_key IN ('BATCH:compat-batch-02:item-c','BATCH:compat-batch-02:item-d')",
                Long.class, actionId);
        assertThat(invocations).as("两项各恰一条调用记录").isEqualTo(2L);
        System.out.println("[P62-EV] s6.compat interrupt-resume succeeded-kept=true no-double-effect=true");
    }

    // ==================== 辅助 ====================

    /** 按命令 ID 领取（跳过通道扫描）。 */
    private CommandEnvelope claimBy(Long commandId) {
        BpmCommand command = asOperator(() -> commandMapper.selectById(commandId));
        assertThat(command).isNotNull();
        // 直接构造已领取信封（等价 claimDue 结果），token 用真实领取保证守卫一致性
        List<CommandEnvelope> claimed = queue.claimDue(
                List.of(CommandChannelEnum.NORMAL), 100);
        return claimed.stream().filter(e -> e.getCommandId().equals(commandId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("命令未被领取: " + commandId));
    }

    /** 新版全量处理器集合（含 BATCH_INVOKE）。 */
    private List<BpmCommandHandler> upgradedHandlers() {
        return List.of(
                app.getBean(com.sw.ck.bpm.process.queue.FlowStartCommandHandler.class),
                app.getBean(com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler.class));
    }

    /** 旧版本消费者等效：只识别 FLOW_START，不认识 BATCH_INVOKE。 */
    static class LegacyOnlyFlowStartHandler implements BpmCommandHandler {
        @Override
        public java.util.Set<CommandTypeEnum> types() {
            return java.util.Set.of(CommandTypeEnum.FLOW_START);
        }

        @Override
        public String handle(CommandEnvelope envelope) {
            return "{\"status\":\"STARTED\"}";
        }
    }

    private TxnBatchSubmitRequest.Item item(String key, String recordId, String quantity) {
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey(key);
        item.setRecordId(recordId);
        item.setQuantity(quantity);
        return item;
    }

    private void seedStockForm() {
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P62兼容库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62兼容库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private String publishAction(String key, String name) {
        String formId = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY).getId());
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
        return asOperator(() -> {
            com.sw.ck.form.service.FormSubmitService submitService =
                    app.getBean(com.sw.ck.form.service.FormSubmitService.class);
            return submitService.submitForm(FORM_KEY,
                    data("material", material, "qty_available", available, "qty_reserved", "0"),
                    null, null, null);
        });
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
