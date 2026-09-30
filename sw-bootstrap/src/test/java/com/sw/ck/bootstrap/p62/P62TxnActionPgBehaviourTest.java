package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.C1PolicyModel;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.form.txn.service.TxnActionTxOperations;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 首事务阶段真实 PostgreSQL 行为证据：
 * T01 并发预占不超分配；T04 同键不同输入冲突、确认与过期释放竞争仅一次合法结算；
 * T03 表单写入与事务动作同事务同成同败（真实提交与真实回滚）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 本地事务动作 PG 行为证据（T01/T03/T04）")
class P62TxnActionPgBehaviourTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 1L;
    private static final String FORM_KEY = "p62_stock";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private com.sw.ck.form.service.FormDataUpdateService updateService;
    private com.sw.ck.form.service.FormDataDeleteService deleteService;
    private com.sw.ck.form.txn.service.C1PolicyService c1PolicyService;
    private TxnActionService actionService;
    private TxnActionExecutor executor;
    private TxnActionTxOperations txOps;
    private TransactionTemplate txTemplate;

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
        props.put("sw.security.jwt.secret", "p62-pg-test-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-pg-test-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-pg-test", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62PgTestLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        updateService = app.getBean(com.sw.ck.form.service.FormDataUpdateService.class);
        deleteService = app.getBean(com.sw.ck.form.service.FormDataDeleteService.class);
        c1PolicyService = app.getBean(com.sw.ck.form.txn.service.C1PolicyService.class);
        actionService = app.getBean(TxnActionService.class);
        executor = app.getBean(TxnActionExecutor.class);
        txOps = app.getBean(TxnActionTxOperations.class);
        txTemplate = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        seedStockForm();
        System.out.println("[P62-PG] 真实 PostgreSQL 完整启动成功 pgPort=" + pg.getPort() + " table=" + stockTable);
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

    // ==================== T01：并发预占不超分配 ====================

    @Test
    @DisplayName("T01 并发争抢：100 可用 × 20 路各预占 10，仅 10 路成功且不超分配，台账勾稽一致")
    void concurrentReserveNeverOversells() throws Exception {
        String recordId = submit("T01-STOCK", "100");
        String reserveId = publishAction("t01_reserve", "T01预占", "RESERVE", reserveCfg());

        int workers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TxnInvokeResult>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return asTenant(TENANT, USER, () ->
                        executor.invoke(reserveId, reserveReq("T01-K-" + idx, recordId, "10")));
            }));
        }
        start.countDown();
        int succeeded = 0;
        int rejected = 0;
        for (Future<TxnInvokeResult> f : futures) {
            TxnInvokeResult r = f.get(60, TimeUnit.SECONDS);
            if ("SUCCEEDED".equals(r.status())) {
                succeeded++;
            } else {
                rejected++;
                assertThat(r.errorCode()).isEqualTo(1604);
            }
        }
        pool.shutdown();

        assertThat(succeeded).as("仅 10 路可成功（100/10）").isEqualTo(10);
        assertThat(rejected).isEqualTo(10);
        assertThat(reservedOf(recordId)).as("预占恰为 100，不超分配").isEqualByComparingTo("100");
        assertThat(balanceOf(recordId)).isEqualByComparingTo("100");
        assertThat(availableOf(recordId)).as("可用量非负").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        BigDecimal ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM sw_form_txn_ledger WHERE action_id = ? AND entry_type = 'RESERVE'",
                BigDecimal.class, reserveId);
        assertThat(ledgerSum).as("成功台账合计=有效预占").isEqualByComparingTo("100");
        System.out.println("[P62-EV] t01.concurrent succeeded=" + succeeded + " rejected=" + rejected
                + " reserved=100 oversell=false ledgerSum=100");
    }

    // ==================== T04：同键不同输入并发冲突 ====================

    @Test
    @DisplayName("T04 同键不同输入（并发）：一路成功、另一路明确冲突，副作用仅一次")
    void sameKeyDifferentInputConcurrent() throws Exception {
        String recordId = submit("T04-DUP", "50");
        String reserveId = publishAction("t04_reserve", "T04预占", "RESERVE", reserveCfg());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> a = () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return asTenant(TENANT, USER, () -> executor.invoke(reserveId, reserveReq("T04-DUP-K", recordId, "3")));
            } catch (BaseException e) {
                return e;
            }
        };
        Callable<Object> b = () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                return asTenant(TENANT, USER, () -> executor.invoke(reserveId, reserveReq("T04-DUP-K", recordId, "4")));
            } catch (BaseException e) {
                return e;
            }
        };
        Future<Object> fa = pool.submit(a);
        Future<Object> fb = pool.submit(b);
        start.countDown();
        Object ra = fa.get(60, TimeUnit.SECONDS);
        Object rb = fb.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        int success = (ra instanceof TxnInvokeResult r1 && "SUCCEEDED".equals(r1.status()) ? 1 : 0)
                + (rb instanceof TxnInvokeResult r2 && "SUCCEEDED".equals(r2.status()) ? 1 : 0);
        int conflicts = (ra instanceof BaseException ? 1 : 0) + (rb instanceof BaseException ? 1 : 0);
        assertThat(success).as("仅一路成功").isEqualTo(1);
        assertThat(conflicts).as("另一路为明确冲突").isEqualTo(1);
        BigDecimal reserved = reservedOf(recordId);
        assertThat(reserved.compareTo(new BigDecimal("3")) == 0 || reserved.compareTo(new BigDecimal("4")) == 0)
                .as("预占等于成功那一路的数量").isTrue();
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ? AND invocation_key = 'T04-DUP-K'",
                Long.class, reserveId);
        assertThat(invocations).as("同键仅一条调用记录").isEqualTo(1L);
        System.out.println("[P62-EV] t04.dup-key success=1 conflict=1 reserved=" + reserved);
    }

    // ==================== T04：确认与过期释放竞争 ====================

    @Test
    @DisplayName("T04 确认×过期释放竞争：仅一次合法结算，另路明确失败")
    void confirmVsExpiryRaceSingleSettlement() throws Exception {
        String recordId = submit("T04-RACE", "30");
        String reserveId = publishAction("t04_race_reserve", "T04竞争预占", "RESERVE", reserveCfg());
        String confirmId = publishAction("t04_confirm", "T04确认", "CONFIRM",
                cfg("qty_available", "qty_reserved", null));

        TxnInvokeResult reserved = asTenant(TENANT, USER, () ->
                executor.invoke(reserveId, reserveReq("T04-RACE-R", recordId, "6")));
        assertThat(reserved.status()).isEqualTo("SUCCEEDED");
        String reservationId = reserved.reservationId();
        // 强制过期（不改状态，仅时间越界）
        jdbc.update("UPDATE sw_form_txn_reservation SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), reservationId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<TxnInvokeResult> confirmFuture = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setReservationId(reservationId);
            req.setInvocationKey("T04-RACE-C");
            return asTenant(TENANT, USER, () -> executor.invoke(confirmId, req));
        });
        Future<Boolean> sweepFuture = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            com.sw.ck.form.txn.entity.TxnReservationEntity res = new com.sw.ck.form.txn.entity.TxnReservationEntity();
            res.setId(reservationId);
            res.setTenantId(TENANT);
            res.setActionId(reserveId);
            res.setActionVersion(1);
            res.setFormId(jdbc.queryForObject(
                    "SELECT form_id FROM sw_form_txn_reservation WHERE id = ?", String.class, reservationId));
            res.setRecordId(recordId);
            res.setQuantity(new BigDecimal("6"));
            return asTenant(TENANT, USER, () ->
                    txOps.settleExpired(res, java.util.UUID.randomUUID().toString(), "EXPIRE:" + reservationId,
                            sha256("EXPIRE|" + reservationId), USER));
        });
        start.countDown();
        TxnInvokeResult confirmResult = confirmFuture.get(60, TimeUnit.SECONDS);
        boolean sweepSettled = Boolean.TRUE.equals(sweepFuture.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        boolean confirmSettled = "SUCCEEDED".equals(confirmResult.status());
        assertThat(confirmSettled ^ sweepSettled).as("恰好一路结算成功（确认 XOR 过期释放）").isTrue();
        String status = jdbc.queryForObject(
                "SELECT status FROM sw_form_txn_reservation WHERE id = ?", String.class, reservationId);
        if (confirmSettled) {
            assertThat(status).isEqualTo("CONFIRMED");
            assertThat(reservedOf(recordId)).isEqualByComparingTo("0");
            assertThat(balanceOf(recordId)).isEqualByComparingTo("24");
        } else {
            assertThat(status).isEqualTo("EXPIRED");
            assertThat(confirmResult.status()).isEqualTo("REJECTED");
            assertThat(confirmResult.errorCode()).isIn(1609, 1610);
            assertThat(reservedOf(recordId)).isEqualByComparingTo("0");
            assertThat(balanceOf(recordId)).isEqualByComparingTo("30");
        }
        long settleEntries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_ledger WHERE reservation_id = ? AND entry_type IN ('CONFIRM','EXPIRE')",
                Long.class, reservationId);
        assertThat(settleEntries).as("结算台账恰一条").isEqualTo(1L);
        System.out.println("[P62-EV] t04.race confirmSettled=" + confirmSettled + " sweepSettled=" + sweepSettled
                + " status=" + status + " settleEntries=1");
    }

    // ==================== T03：同事务同成同败 ====================

    @Test
    @DisplayName("T03 表单写入×事务动作同事务：提交则同成，回滚则同败（无半成品副作用）")
    void sameTransactionCompositeCommitAndRollback() {
        String reserveId = publishAction("t03_reserve", "T03预占", "RESERVE", reserveCfg());

        // 提交路径：表单记录 + 预占动作同一外层事务，正常提交 → 两者同成
        String committedRecord = txTemplate.execute(status -> {
            String rid = asTenant(TENANT, USER, () -> submitService.submitForm(FORM_KEY,
                    data("material", "T03-COMMIT", "qty_available", "50", "qty_reserved", "0"),
                    null, null, null));
            TxnInvokeResult r = asTenant(TENANT, USER, () ->
                    executor.invoke(reserveId, reserveReq("T03-COMMIT-K", rid, "5")));
            assertThat(r.status()).isEqualTo("SUCCEEDED");
            return rid;
        });
        assertThat(committedRecord).isNotBlank();
        assertThat(reservedOf(committedRecord)).isEqualByComparingTo("5");
        assertThat(countInvocations(reserveId, "T03-COMMIT-K")).isEqualTo(1L);

        // 回滚路径：表单记录 + 预占动作同事务，抛错回滚 → 两者同灭
        try {
            txTemplate.execute(status -> {
                String rid = asTenant(TENANT, USER, () -> submitService.submitForm(FORM_KEY,
                        data("material", "T03-ROLLBACK", "qty_available", "30", "qty_reserved", "0"),
                        null, null, null));
                asTenant(TENANT, USER, () ->
                        executor.invoke(reserveId, reserveReq("T03-ROLLBACK-K", rid, "5")));
                throw new IllegalStateException("force-rollback");
            });
            throw new AssertionError("外层事务应回滚");
        } catch (IllegalStateException expected) {
            // 预期回滚
        }
        assertThat(countStockByMaterial("T03-ROLLBACK")).as("回滚后表单记录不存在").isZero();
        assertThat(countInvocations(reserveId, "T03-ROLLBACK-K")).as("回滚后无调用记录").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_ledger WHERE invocation_id IN "
                        + "(SELECT id FROM sw_form_txn_invocation WHERE invocation_key = 'T03-ROLLBACK-K')",
                Long.class)).isZero();
        System.out.println("[P62-EV] t03.same-tx commit=both-present rollback=both-absent");
    }

    // ==================== T02：C1 全写入口一致（真实 PG + 真实表单写链路） ====================

    @Test
    @DisplayName("T02 C1 保护：受保护模型提交/更新/删除一律拒绝且无副作用；未保护模型原路径可用；跨租户不可达")
    void c1ProtectsAllWritePaths() {
        String c1Key = "p62_stock_c1";
        String c1FormId = asTenant(TENANT, USER, () -> {
            FormDefDTO draft = formDefService.createDraft(c1Key, "P62库存表C1", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return draft.getId();
        });
        String c1Table = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(c1Key).getPhysicalTableName());

        // 保护前：普通写入口原路径可用（含值建档）
        String recordId = asTenant(TENANT, USER, () -> submitService.submitForm(c1Key,
                data("material", "C1-A", "qty_available", "7", "qty_reserved", "0"), null, null, null));
        assertThat(recordId).isNotBlank();

        // 空值闸门：未填余额的记录（保护前允许存在）使启用被拒绝
        String blankId = asTenant(TENANT, USER, () -> submitService.submitForm(c1Key,
                data("material", "C1-BLANK"), null, null, null));
        assertThat(blankId).isNotBlank();
        assertRejected(() -> asTenant(TENANT, USER, () -> c1PolicyService.save(c1FormId, enabledC1Policy())),
                FormErrorCode.C1_POLICY_INVALID.getCode());

        // 保护前普通更新原路径可用：补值后启用成功
        Long blankVersion = jdbc.queryForObject("SELECT " + q("version") + " FROM " + q(c1Table)
                + " WHERE " + q("id") + " = ?", Long.class, blankId);
        asTenant(TENANT, USER, () -> {
            com.sw.ck.form.api.dto.FormDataUpdateRequest req = new com.sw.ck.form.api.dto.FormDataUpdateRequest();
            req.setVersion(blankVersion);
            req.setData(data("material", "C1-BLANK", "qty_available", "0", "qty_reserved", "0"));
            updateService.updateRecord(c1Key, blankId, req);
            return null;
        });
        assertThat(asTenant(TENANT, USER, () -> c1PolicyService.save(c1FormId, enabledC1Policy()).enabled()))
                .isTrue();

        int protectedCode = FormErrorCode.C1_WRITE_PROTECTED.getCode();
        // 保护后提交：带受保护值 / 仅未保护字段，一律拒绝（INSERT 写全列）
        assertRejected(() -> asTenant(TENANT, USER, () -> submitService.submitForm(c1Key,
                data("material", "C1-X", "qty_available", "5"), null, null, null)), protectedCode);
        assertRejected(() -> asTenant(TENANT, USER, () -> submitService.submitForm(c1Key,
                data("material", "C1-Y"), null, null, null)), protectedCode);

        // 保护后更新：带受保护值 / 省略受保护字段，一律拒绝（整量覆盖写全列）
        Long version = jdbc.queryForObject("SELECT " + q("version") + " FROM " + q(c1Table)
                + " WHERE " + q("id") + " = ?", Long.class, recordId);
        assertRejected(() -> asTenant(TENANT, USER, () -> {
            com.sw.ck.form.api.dto.FormDataUpdateRequest req = new com.sw.ck.form.api.dto.FormDataUpdateRequest();
            req.setVersion(version);
            req.setData(data("material", "C1-A", "qty_available", "999", "qty_reserved", "0"));
            updateService.updateRecord(c1Key, recordId, req);
            return null;
        }), protectedCode);
        assertRejected(() -> asTenant(TENANT, USER, () -> {
            com.sw.ck.form.api.dto.FormDataUpdateRequest req = new com.sw.ck.form.api.dto.FormDataUpdateRequest();
            req.setVersion(version);
            req.setData(data("material", "C1-A2"));
            updateService.updateRecord(c1Key, recordId, req);
            return null;
        }), protectedCode);

        // 保护后删除：拒绝（受保护模型须经受控动作结算）
        assertRejected(() -> asTenant(TENANT, USER, () -> {
            deleteService.deleteRecord(c1Key, recordId);
            return null;
        }), protectedCode);

        // 拒绝无副作用：余额与未保护字段均未被改写
        assertThat(jdbc.queryForObject("SELECT " + q("qty_available") + " FROM " + q(c1Table)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT " + q("material") + " FROM " + q(c1Table)
                + " WHERE " + q("id") + " = ?", String.class, recordId)).isEqualTo("C1-A");

        // 未保护模型（p62_stock 未启用 C1）原路径可用
        assertThat(submit("T02-PLAIN", "3")).isNotBlank();

        // 跨租户：租户 100 看不到租户 0 的动作（列表为空），调用被拒绝
        String reserveId = publishAction("t02_reserve", "T02预占", "RESERVE", reserveCfg());
        String plainFormId = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(FORM_KEY).getId());
        assertThat(asTenant(100L, USER, () -> actionService.listByForm(plainFormId))).isEmpty();
        assertRejected(() -> asTenant(100L, USER, () ->
                executor.invoke(reserveId, reserveReq("T02-CROSS", recordId, "1"))),
                FormErrorCode.ACTION_NOT_FOUND.getCode());

        System.out.println("[P62-EV] t02.c1 submit/update/delete=rejected plain=ok blank-enable=rejected"
                + " cross-tenant=blocked");
    }

    // ==================== 种子与工具 ====================

    private void seedStockForm() {
        asTenant(TENANT, USER, () -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P62库存表", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        FormDefDTO def = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(FORM_KEY));
        stockTable = def.getPhysicalTableName();
        assertThat(stockTable).isNotBlank();
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62库存表\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private String submit(String material, String available) {
        return asTenant(TENANT, USER, () -> submitService.submitForm(FORM_KEY,
                data("material", material, "qty_available", available, "qty_reserved", "0"),
                null, null, null));
    }

    private TxnActionConfig reserveCfg() {
        return cfg("qty_available", "qty_reserved", 600L);
    }

    private TxnActionConfig cfg(String balance, String reserved, Long ttl) {
        TxnActionConfig c = new TxnActionConfig();
        c.setBalanceField(balance);
        c.setReservedField(reserved);
        c.setExpiresInSeconds(ttl);
        return c;
    }

    private String publishAction(String key, String name, String type, TxnActionConfig cfg) {
        String formId = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(FORM_KEY).getId());
        return asTenant(TENANT, USER, () -> {
            String id = actionService.create(formId, new TxnActionSaveRequest(key, name, type, null, cfg)).id();
            actionService.publish(id);
            return id;
        });
    }

    private TxnInvokeRequest reserveReq(String key, String recordId, String qty) {
        TxnInvokeRequest req = new TxnInvokeRequest();
        req.setInvocationKey(key);
        req.setRecordId(recordId);
        req.setQuantity(qty);
        return req;
    }

    /** 启用态 C1 策略（余额=可用量、预占=预占量，强制非负）。 */
    private C1PolicyModel enabledC1Policy() {
        C1PolicyModel model = new C1PolicyModel();
        model.setEnabled(true);
        model.setProtectedFields(List.of("qty_available", "qty_reserved"));
        model.setBalanceField("qty_available");
        model.setReservedField("qty_reserved");
        model.setNonNegativeAvailable(true);
        return model;
    }

    /** 断言调用被闸门拒绝且业务错误码匹配（C1 保护 / 跨租户不可达）。 */
    private void assertRejected(ThrowingCallable call, int expectedCode) {
        try {
            call.call();
            throw new AssertionError("应被拒绝但调用成功");
        } catch (BaseException e) {
            assertThat(e.getCode()).as("拒绝错误码").isEqualTo(expectedCode);
        } catch (Throwable t) {
            throw new AssertionError("应抛 BaseException，实际：" + t, t);
        }
    }

    private String q(String identifier) {
        return "\"" + identifier + "\"";
    }

    private BigDecimal availableOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_available") + " - " + q("qty_reserved")
                + " FROM " + q(stockTable) + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private BigDecimal balanceOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_available") + " FROM " + q(stockTable)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private BigDecimal reservedOf(String recordId) {
        return jdbc.queryForObject("SELECT " + q("qty_reserved") + " FROM " + q(stockTable)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private long countStockByMaterial(String material) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + q(stockTable) + " WHERE " + q("material")
                + " = ? AND " + q("deleted") + " = 0", Long.class, material);
    }

    private long countInvocations(String actionId, String invocationKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_invocation WHERE action_id = ?"
                + " AND invocation_key = ?", Long.class, actionId, invocationKey);
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static <T> T asTenant(Long tenantId, Long userId, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
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

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
