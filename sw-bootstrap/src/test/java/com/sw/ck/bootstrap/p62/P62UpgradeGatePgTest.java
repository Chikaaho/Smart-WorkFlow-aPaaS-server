package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.CommandTypeEnum;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.queue.BpmCommandHandler;
import com.sw.ck.bpm.process.queue.CommandDispatcher;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 S6 补证（G5）：新能力默认关闭门禁与旧消费者退出核清后的协调启用（真实 PostgreSQL）。
 *
 * <p>覆盖 U06：无 form:action:invoke 授权时批量新能力默认不可达（默认关门禁）；
 * 授权开启即受理可用（可执行门禁而非口头约定）；旧消费者对新类型 PENDING 不误动
 * （保持 PENDING 而非凭枚举抛错烧掉重试预算的误处理路径之外的额外证明：旧消费者
 * 每轮 FAILED 后命令保留、状态机不洗掉）；旧四态类型（FLOW_START）在升级后由新
 * 版本消费者按原语义消费；升级前后历史查询连续。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G5 升级门禁演练（真实 PG：默认关闭/授权启用/旧类型续消费/历史连续）")
class P62UpgradeGatePgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 92201L;

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;
    private static String GATE_ACTION_ID;
    private static final String FORM_KEY = "p62_gate_stock";

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnBatchService batchService;
    private BpmCommandMapper commandMapper;
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
        props.put("sw.security.jwt.secret", "p62-gate-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-gate-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.txn-batch.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-gate", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62GateLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        batchService = app.getBean(TxnBatchService.class);
        commandMapper = app.getBean(BpmCommandMapper.class);
        queue = app.getBean(PersistentBpmCommandQueue.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'gate-operator', 'seed-not-a-login-secret', '门禁演练员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92201, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        asOperator(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "G5门禁库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        stockTable = asOperator(() -> formDefService.getFormDefByKey(FORM_KEY)).getPhysicalTableName();
        // 真实已发布动作（门禁开用例的受理目标；受理按动作 UUID 绑定）
        GATE_ACTION_ID = asOperator(() -> {
            String formId = formDefService.getFormDefByKey(FORM_KEY).getId();
            com.sw.ck.form.txn.model.TxnActionConfig cfg = new com.sw.ck.form.txn.model.TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = app.getBean(com.sw.ck.form.txn.service.TxnActionService.class)
                    .create(formId, new com.sw.ck.form.txn.model.TxnActionSaveRequest(
                            "gate_reserve", "门禁演练预占", "RESERVE", null, cfg)).id();
            app.getBean(com.sw.ck.form.txn.service.TxnActionService.class).publish(id);
            return id;
        });
        System.out.println("[P62-EV] g5 boot ok pgPort=" + pg.getPort() + " table=" + stockTable);
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
    @DisplayName("默认关闭门禁：无 form:action:invoke 授权受理被拒；授权开启即受理可用")
    void newCapabilityDefaultOffThenGateOn() {
        // 门禁关：普通用户（无 invoke 权限）不可达
        LoginUser noPerm = new LoginUser();
        noPerm.setUserId(USER);
        noPerm.setTenantId(TENANT);
        LoginUserHolder.set(noPerm);
        TxnBatchSubmitRequest request = requestOf("gate-batch-01", GATE_ACTION_ID,
                item("it-1", seedRecord("GATE-A", "10"), "1"));
        assertThatThrownBy(() -> batchService.submit(request))
                .isInstanceOfSatisfying(BaseException.class, e ->
                        assertThat(e.getCode()).isEqualTo(403));
        long batches = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch", Long.class);
        assertThat(batches).as("门禁关：零受理").isZero();

        // 门禁开：授权开启（role 2 的 R 迁移授权=生产门禁形式）后受理可用
        asOperator(() -> batchService.submit(request));
        batches = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch", Long.class);
        assertThat(batches).as("门禁开：受理落库").isEqualTo(1L);
        System.out.println("[P62-EV] g5.default-off denied=403 batches=0; gate-on batches=1");
    }

    @Test
    @DisplayName("升级演练：旧类型 FLOW_START 由新版本消费者按原语义消费；新类型 FAILED 保留不洗；历史连续")
    void upgradeConsumesLegacyTypesKeepsHistory() {
        // 新类型 FAILED 保留（旧消费者拒绝产生）：状态机不洗掉
        TxnBatchSubmitRequest request = requestOf("gate-batch-02", GATE_ACTION_ID,
                item("it-1", seedRecord("GATE-B", "10"), "1"));
        TxnBatchView accepted = asOperator(() -> batchService.submit(request));
        CommandEnvelope legacyClaim = claimBy(accepted.getCommandId());
        new CommandDispatcher(queue, List.of(new LegacyOnlyFlowStartHandler()))
                .dispatchOneForTest(legacyClaim);
        assertThat(asOperator(() -> commandMapper.selectById(accepted.getCommandId())).getStatus())
                .as("新类型 FAILED 保留").isEqualTo("FAILED");

        // 旧类型 PENDING（FLOW_START，无绑定 no-op 语义）：升级后由新版本消费者按原语义消费完成
        String record = seedRecord("GATE-C", "5");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formKey", FORM_KEY);
        payload.put("recordId", record);
        payload.put("submitter", String.valueOf(USER));
        CommandEnvelope legacyPending = enqueueLegacyFlowStart(record, payload);
        assertThat(asOperator(() -> commandMapper.selectById(legacyPending.getCommandId())).getStatus())
                .isEqualTo("PENDING");

        // 升级后（新版本消费者=全量处理器+正式身份回查）消费旧类型
        new CommandDispatcher(queue,
                List.of(app.getBean(com.sw.ck.bpm.process.queue.FlowStartCommandHandler.class),
                        app.getBean(com.sw.ck.bpm.process.queue.BatchInvokeCommandHandler.class)),
                app.getBean(com.sw.ck.security.spi.UserDetailsProvider.class))
                .dispatchOneForTest(claimBy(legacyPending.getCommandId()));
        assertThat(asOperator(() -> commandMapper.selectById(legacyPending.getCommandId())).getStatus())
                .as("旧类型由新版本消费者按原语义消费").isEqualTo("COMPLETED");

        // 历史连续：新类型 FAILED 行与旧类型 COMPLETED 行同表可查（升级前后历史不丢）
        Long history = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE id IN (?, ?)"
                        + " AND status IN ('FAILED','COMPLETED')",
                Long.class, accepted.getCommandId(), legacyPending.getCommandId());
        assertThat(history).isEqualTo(2L);
        System.out.println("[P62-EV] g5.upgrade new-type=FAILED-kept legacy-type=COMPLETED history=2");
    }

    // ==================== 辅助 ====================

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

    private CommandEnvelope enqueueLegacyFlowStart(String record, Map<String, Object> payload) {
        try {
            CommandEnvelope envelope = new CommandEnvelope();
            envelope.setCommandType(CommandTypeEnum.FLOW_START);
            envelope.setChannel(CommandChannelEnum.NORMAL);
            envelope.setCommandKey("FLOW_START:" + record);
            envelope.setTenantId(TENANT);
            envelope.setInitiatorId(USER);
            envelope.setPayload(app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(payload));
            TransactionTemplate tx = app.getBean(TransactionTemplate.class);
            return asOperator(() -> {
                Long id = tx.execute(status -> queue.enqueue(envelope));
                envelope.setCommandId(id);
                return envelope;
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CommandEnvelope claimBy(Long commandId) {
        List<CommandEnvelope> claimed = queue.claimDue(List.of(CommandChannelEnum.NORMAL), 100);
        return claimed.stream().filter(e -> e.getCommandId().equals(commandId)).findFirst()
                .orElseThrow(() -> new AssertionError("命令未被领取: " + commandId));
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

    private String seedRecord(String material, String available) {
        return asOperator(() -> submitService.submitForm(FORM_KEY,
                data("material", material, "qty_available", available, "qty_reserved", "0"),
                null, null, null));
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"G5门禁库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
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
