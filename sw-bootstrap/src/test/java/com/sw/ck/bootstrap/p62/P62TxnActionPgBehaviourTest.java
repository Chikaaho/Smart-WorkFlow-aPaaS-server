package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.C1PolicyModel;
import com.sw.ck.form.txn.model.C1PolicyView;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private com.sw.ck.form.service.FormImportExportService importExportService;
    private com.sw.ck.form.txn.service.C1PolicyService c1PolicyService;
    private TxnActionService actionService;
    private TxnActionExecutor executor;
    private TxnActionTxOperations txOps;
    private TransactionTemplate txTemplate;
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
        importExportService = app.getBean(com.sw.ck.form.service.FormImportExportService.class);
        c1PolicyService = app.getBean(com.sw.ck.form.txn.service.C1PolicyService.class);
        actionService = app.getBean(TxnActionService.class);
        executor = app.getBean(TxnActionExecutor.class);
        txOps = app.getBean(TxnActionTxOperations.class);
        objectMapper = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
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

        // 回滚路径：表单记录 + 预占动作同事务，抛错回滚 → 表单行/调用记录/凭据/台账同灭
        final String[] rollbackRecord = new String[1];
        try {
            txTemplate.execute(status -> {
                String rid = asTenant(TENANT, USER, () -> submitService.submitForm(FORM_KEY,
                        data("material", "T03-ROLLBACK", "qty_available", "30", "qty_reserved", "0"),
                        null, null, null));
                rollbackRecord[0] = rid;
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
        assertThat(countReservationsByRecord(rollbackRecord[0])).as("回滚后无预占凭据").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_ledger WHERE invocation_id IN "
                        + "(SELECT id FROM sw_form_txn_invocation WHERE invocation_key = 'T03-ROLLBACK-K')",
                Long.class)).isZero();
        System.out.println("[P62-EV] t03.same-tx commit=both-present rollback=both-absent"
                + " (record/invocation/reservation/ledger all-absent)");
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

    // ==================== LT02：剩余写入口（导入）与声明边界 ====================

    @Test
    @DisplayName("LT02 C1 覆盖导入入口：受保护表单导入整批拒绝零写入；同一导入在未保护表单受控落库")
    void c1CoversImportEntry() throws Exception {
        String protectedKey = "p62_stock_c1imp";
        String formId = asTenant(TENANT, USER, () -> {
            FormDefDTO draft = formDefService.createDraft(protectedKey, "P62库存导入C1", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return draft.getId();
        });
        String table = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(protectedKey).getPhysicalTableName());
        assertThat(asTenant(TENANT, USER, () -> c1PolicyService.save(formId, enabledC1Policy()).enabled())).isTrue();

        byte[] protectedRows = fillTemplate(
                asTenant(TENANT, USER, () -> importExportService.generateTemplate(protectedKey)),
                new Object[][]{{"LT02-IMP-A", 10, 0}, {"LT02-IMP-B", 20, 0}});
        var protectedResult = asTenant(TENANT, USER, () ->
                importExportService.importData(protectedKey, new java.io.ByteArrayInputStream(protectedRows)));
        assertThat(protectedResult.successCount()).as("受保护表单导入整批拒绝：零成功").isZero();
        assertThat(protectedResult.errorCount()).isEqualTo(2);
        assertThat(protectedResult.errors()).hasSize(2);
        for (var rowError : protectedResult.errors()) {
            assertThat(rowError.message()).as("行级错误反馈 C1 保护原因").contains("C1");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + q(table), Long.class))
                .as("受保护表单导入被拒后零写入").isZero();

        byte[] plainRows = fillTemplate(
                asTenant(TENANT, USER, () -> importExportService.generateTemplate(FORM_KEY)),
                new Object[][]{{"LT02-IMP-A", 10, 0}, {"LT02-IMP-B", 20, 0}});
        var result = asTenant(TENANT, USER, () ->
                importExportService.importData(FORM_KEY, new java.io.ByteArrayInputStream(plainRows)));
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.errorCount()).isZero();
        assertThat(countStockByMaterial("LT02-IMP-A")).isEqualTo(1L);
        assertThat(countStockByMaterial("LT02-IMP-B")).isEqualTo(1L);
        System.out.println("[P62-EV] lt02.import protected=rejected/zero-write plain=2-rows-committed");
    }

    // ==================== LT02：声明业务键与精度边界 ====================

    @Test
    @DisplayName("LT02 声明业务键隔离：键不匹配/未声明被拒；不同键值记录互不影响，凭据冻结键值快照")
    void businessKeysDeclaredIsolationHolds() {
        String recordA = submit("LT02-KEY-A", "50");
        String recordB = submit("LT02-KEY-B", "50");
        TxnActionConfig keyCfg = cfg("qty_available", "qty_reserved", 600L);
        keyCfg.setKeyFields(List.of("material"));
        String reserveId = publishAction("lt02_reserve_keys", "LT02键预占", "RESERVE", keyCfg);

        TxnInvokeRequest first = reserveReq("LT02-KEY-1", recordA, "5");
        first.setBusinessKeys(Map.of("material", "LT02-KEY-A"));
        TxnInvokeResult ok = asTenant(TENANT, USER, () -> executor.invoke(reserveId, first));
        assertThat(ok.status()).isEqualTo("SUCCEEDED");
        String keysJson = jdbc.queryForObject("SELECT biz_keys_json FROM sw_form_txn_reservation WHERE id = ?",
                String.class, ok.reservationId());
        assertThat(keysJson).contains("material").contains("LT02-KEY-A");

        TxnInvokeRequest mismatch = reserveReq("LT02-KEY-2", recordA, "5");
        mismatch.setBusinessKeys(Map.of("material", "LT02-KEY-B"));
        TxnInvokeResult mismatchResult = asTenant(TENANT, USER, () -> executor.invoke(reserveId, mismatch));
        assertThat(mismatchResult.status()).isEqualTo("REJECTED");
        assertThat(mismatchResult.errorCode()).isEqualTo(FormErrorCode.ACTION_FIELD_BINDING_INVALID.getCode());
        assertThat(reservedOf(recordA)).as("键不匹配无副作用").isEqualByComparingTo("5");

        TxnInvokeRequest undeclared = reserveReq("LT02-KEY-3", recordA, "5");
        undeclared.setBusinessKeys(Map.of("warehouse", "W1"));
        TxnInvokeResult undeclaredResult = asTenant(TENANT, USER, () -> executor.invoke(reserveId, undeclared));
        assertThat(undeclaredResult.status()).isEqualTo("REJECTED");
        assertThat(undeclaredResult.errorCode()).isEqualTo(FormErrorCode.ACTION_FIELD_BINDING_INVALID.getCode());

        TxnInvokeRequest other = reserveReq("LT02-KEY-4", recordB, "7");
        other.setBusinessKeys(Map.of("material", "LT02-KEY-B"));
        assertThat(asTenant(TENANT, USER, () -> executor.invoke(reserveId, other)).status()).isEqualTo("SUCCEEDED");
        assertThat(reservedOf(recordA)).as("不同业务键记录互不影响").isEqualByComparingTo("5");
        assertThat(reservedOf(recordB)).isEqualByComparingTo("7");
        System.out.println("[P62-EV] lt02.keys mismatch/undeclared=rejected isolation=A:5/B:7 snapshot=material");
    }

    @Test
    @DisplayName("LT02 声明精度与合法负边界：超精度明确拒绝不静默舍入；可用量非负边界含等号")
    void quantityPrecisionAndNonNegativeBoundaries() {
        String recordId = submit("LT02-PREC", "100");
        TxnActionConfig scale3 = cfg("qty_available", "qty_reserved", 600L);
        scale3.setQuantityScale(3);
        TxnActionConfig scale6 = cfg("qty_available", "qty_reserved", 600L);
        scale6.setQuantityScale(6);
        String reserve3 = publishAction("lt02_reserve_s3", "LT02精度预占3", "RESERVE", scale3);
        String reserve6 = publishAction("lt02_reserve_s6", "LT02精度预占6", "RESERVE", scale6);
        String adjustId = publishAction("lt02_adjust", "LT02调整", "ADJUST", scale6);

        assertThat(invokeReserve(reserve3, "LT02-P1", recordId, "1.234").status()).isEqualTo("SUCCEEDED");
        TxnInvokeResult overScale = invokeReserve(reserve3, "LT02-P2", recordId, "1.2345");
        assertThat(overScale.status()).isEqualTo("REJECTED");
        assertThat(overScale.errorCode()).isEqualTo(FormErrorCode.ACTION_QUANTITY_INVALID.getCode());
        assertThat(overScale.errorMsg()).contains("精度");
        assertThat(invokeReserve(reserve3, "LT02-P3", recordId, "2.000").status()).isEqualTo("SUCCEEDED");
        assertThat(reservedOf(recordId)).as("未发生静默舍入（1.234+2.000）").isEqualByComparingTo("3.234");
        assertThat(invokeReserve(reserve3, "LT02-P4", recordId, "0").status()).isEqualTo("REJECTED");
        assertThat(invokeReserve(reserve3, "LT02-P5", recordId, "-2").status()).isEqualTo("REJECTED");
        TxnInvokeResult huge = invokeReserve(reserve3, "LT02-P6", recordId, "100000000000000");
        assertThat(huge.status()).isEqualTo("REJECTED");
        assertThat(huge.errorMsg()).contains("范围");

        assertThat(invokeReserve(reserve6, "LT02-P7", recordId, "0.000001").status())
                .as("声明 6 位精度：6 位小数可受理").isEqualTo("SUCCEEDED");
        TxnInvokeResult sevenDigits = invokeReserve(reserve6, "LT02-P8", recordId, "0.0000001");
        assertThat(sevenDigits.status()).isEqualTo("REJECTED");
        assertThat(sevenDigits.errorCode()).isEqualTo(FormErrorCode.ACTION_QUANTITY_INVALID.getCode());

        assertThat(invokeAdjust(adjustId, "LT02-P9", recordId, "0").status()).isEqualTo("REJECTED");
        String toZero = availableOf(recordId).negate().toPlainString();
        assertThat(invokeAdjust(adjustId, "LT02-P10", recordId, toZero).status())
                .as("调整到可用=0 的边界可受理").isEqualTo("SUCCEEDED");
        assertThat(availableOf(recordId)).isEqualByComparingTo("0");
        TxnInvokeResult belowZero = invokeAdjust(adjustId, "LT02-P11", recordId, "-0.001");
        assertThat(belowZero.status()).isEqualTo("REJECTED");
        assertThat(belowZero.errorCode()).isEqualTo(FormErrorCode.ACTION_INSUFFICIENT_AVAILABLE.getCode());
        System.out.println("[P62-EV] lt02.precision scale3=1.234-ok/1.2345-rejected no-rounding"
                + " scale6=6-digits-ok/7-digits-rejected adjust=zero-boundary-ok/below-rejected");
    }

    // ==================== LT04：冻结版本结算、停用边界与非法声明 ====================

    @Test
    @DisplayName("LT04/T05 冻结版本结算与停用边界：新发布不改旧预占语义；停用只拒新调用，旧凭据仍可结算")
    void settlementUsesFrozenVersionAndSurvivesDisable() {
        String formKey = "p62_stock_multi";
        String formId = asTenant(TENANT, USER, () -> {
            FormDefDTO draft = formDefService.createDraft(formKey, "P62多字段库存", null, null);
            formDefService.saveConfig(draft.getId(), multiFieldDefinition());
            formDefService.publish(draft.getId());
            return draft.getId();
        });
        String table = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(formKey).getPhysicalTableName());
        String recordId = asTenant(TENANT, USER, () -> submitService.submitForm(formKey,
                data("material", "LT04-M", "qty_available", "100", "qty_reserved", "0", "qty_reserved2", "0"),
                null, null, null));

        // v1：预占绑定 qty_reserved
        String reserveAction = asTenant(TENANT, USER, () -> {
            String id = actionService.create(formId, new TxnActionSaveRequest("lt04_reserve", "LT04预占", "RESERVE",
                    null, cfg("qty_available", "qty_reserved", 600L))).id();
            actionService.publish(id);
            return id;
        });
        TxnInvokeResult reserved = invokeReserve(reserveAction, "LT04-R1", recordId, "5");
        assertThat(reserved.status()).isEqualTo("SUCCEEDED");
        assertThat(reserved.actionVersion()).isEqualTo(1);
        String reservationId = reserved.reservationId();

        // 重新发布 v2：预占字段改指 qty_reserved2（新发布不得改变既有预占的结算语义）
        asTenant(TENANT, USER, () -> {
            actionService.update(reserveAction, new TxnActionSaveRequest(null, "LT04预占", "RESERVE", null,
                    cfg("qty_available", "qty_reserved2", 600L)));
            actionService.publish(reserveAction);
            return null;
        });
        assertThat(asTenant(TENANT, USER, () -> actionService.get(reserveAction).currentVersion())).isEqualTo(2);

        // CONFIRM 动作自身声明 qty_reserved2：结算仍必须按预占受理冻结版本（qty_reserved）执行
        String confirmAction = asTenant(TENANT, USER, () -> {
            String id = actionService.create(formId, new TxnActionSaveRequest("lt04_confirm", "LT04确认", "CONFIRM",
                    null, cfg("qty_available", "qty_reserved2", null))).id();
            actionService.publish(id);
            return id;
        });
        TxnInvokeRequest confirmReq = new TxnInvokeRequest();
        confirmReq.setReservationId(reservationId);
        confirmReq.setInvocationKey("LT04-C1");
        TxnInvokeResult confirmed = asTenant(TENANT, USER, () -> executor.invoke(confirmAction, confirmReq));
        assertThat(confirmed.status()).isEqualTo("SUCCEEDED");
        assertThat(confirmed.actionVersion()).as("结算按预占受理冻结版本执行").isEqualTo(1);
        assertThat(colOf(table, "qty_reserved", recordId)).as("冻结版本预占字段被扣减").isEqualByComparingTo("0");
        assertThat(colOf(table, "qty_reserved2", recordId)).as("新版本字段未被旧预占改写").isEqualByComparingTo("0");
        assertThat(colOf(table, "qty_available", recordId)).isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject("SELECT action_version FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = 'CONFIRM'", Integer.class, reservationId)).isEqualTo(1);

        // 跨表单凭据绑定：其他表单的预占凭据不能用本表单动作结算（修复前会误结算）
        String plainFormId = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(FORM_KEY).getId());
        String otherReserve = asTenant(TENANT, USER, () -> {
            String id = actionService.create(plainFormId, new TxnActionSaveRequest("lt04_reserve_other",
                    "LT04预占其他表", "RESERVE", null, cfg("qty_available", "qty_reserved", 600L))).id();
            actionService.publish(id);
            return id;
        });
        String otherRecord = submit("LT04-OTHER", "20");
        TxnInvokeResult otherReserved = invokeReserve(otherReserve, "LT04-RB", otherRecord, "2");
        TxnInvokeRequest crossForm = new TxnInvokeRequest();
        crossForm.setReservationId(otherReserved.reservationId());
        crossForm.setInvocationKey("LT04-CROSS");
        TxnInvokeResult crossResult = asTenant(TENANT, USER, () -> executor.invoke(confirmAction, crossForm));
        assertThat(crossResult.status()).isEqualTo("REJECTED");
        assertThat(crossResult.errorCode()).isEqualTo(FormErrorCode.ACTION_RESERVATION_NOT_FOUND.getCode());
        assertThat(reservedOf(otherRecord)).as("跨表单结算被拒无副作用").isEqualByComparingTo("2");

        // 停用边界：预占动作停用 → 新预占被拒；既有凭据（停用前受理）仍可结算
        TxnInvokeResult reserved2 = invokeReserve(reserveAction, "LT04-R2", recordId, "3");
        assertThat(reserved2.status()).isEqualTo("SUCCEEDED");
        asTenant(TENANT, USER, () -> {
            actionService.disable(reserveAction);
            return null;
        });
        assertRejected(() -> asTenant(TENANT, USER, () ->
                        executor.invoke(reserveAction, reserveReq("LT04-R3", recordId, "1"))),
                FormErrorCode.ACTION_DISABLED.getCode());
        TxnInvokeRequest settleOld = new TxnInvokeRequest();
        settleOld.setReservationId(reserved2.reservationId());
        settleOld.setInvocationKey("LT04-C2");
        assertThat(asTenant(TENANT, USER, () -> executor.invoke(confirmAction, settleOld)).status())
                .as("停用只拒绝新调用：旧凭据仍可结算").isEqualTo("SUCCEEDED");
        assertThat(colOf(table, "qty_reserved", recordId)).isEqualByComparingTo("0");
        asTenant(TENANT, USER, () -> {
            actionService.enable(reserveAction);
            return null;
        });

        // 停用确认动作：无凭据的新调用被拒；既有凭据仍可结算；已受理请求重放不受停用影响
        TxnInvokeResult reserved4 = invokeReserve(reserveAction, "LT04-R4", recordId, "2");
        assertThat(reserved4.status()).isEqualTo("SUCCEEDED");
        asTenant(TENANT, USER, () -> {
            actionService.disable(confirmAction);
            return null;
        });
        TxnInvokeRequest noCredential = new TxnInvokeRequest();
        noCredential.setInvocationKey("LT04-C3");
        assertRejected(() -> asTenant(TENANT, USER, () -> executor.invoke(confirmAction, noCredential)),
                FormErrorCode.ACTION_DISABLED.getCode());
        TxnInvokeRequest settleOld2 = new TxnInvokeRequest();
        settleOld2.setReservationId(reserved4.reservationId());
        settleOld2.setInvocationKey("LT04-C4");
        assertThat(asTenant(TENANT, USER, () -> executor.invoke(confirmAction, settleOld2)).status())
                .isEqualTo("SUCCEEDED");
        TxnInvokeResult replayAfterDisable = asTenant(TENANT, USER, () -> executor.invoke(confirmAction, settleOld2));
        assertThat(replayAfterDisable.replay()).as("已受理请求的回查不受停用影响").isTrue();

        asTenant(TENANT, USER, () -> {
            actionService.enable(confirmAction);
            return null;
        });
        assertThat(asTenant(TENANT, USER, () -> actionService.get(reserveAction).status())).isEqualTo("PUBLISHED");
        assertThat(asTenant(TENANT, USER, () -> actionService.get(confirmAction).status())).isEqualTo("PUBLISHED");
        System.out.println("[P62-EV] lt04.frozen-v2-published confirm=v1-semantics(qty_reserved-deducted/qty_reserved2-untouched)"
                + " cross-form-credential=rejected disable=new-call-rejected/old-credential-settled/replay-ok");
    }

    // ==================== LT04a：同一旧预占跨重发布与 C1 策略变化 ====================

    @Test
    @DisplayName("LT04a 单对象证据：同一表单/记录/预占经历合法 C1 变更与动作重发布后，仍按冻结版本结算且普通写入持续受保护")
    void singleObjectReservationSurvivesRepublishAndC1PolicyChange() {
        // 单对象证据集：一个表单、一条记录、一笔预占，全程同一身份贯穿
        String formKey = "p62_stock_single";
        String formId = asTenant(TENANT, USER, () -> {
            FormDefDTO draft = formDefService.createDraft(formKey, "P62单对象库存", null, null);
            formDefService.saveConfig(draft.getId(), multiFieldDefinition());
            formDefService.publish(draft.getId());
            return draft.getId();
        });
        String table = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(formKey).getPhysicalTableName());
        String recordId = asTenant(TENANT, USER, () -> submitService.submitForm(formKey,
                data("material", "LT04A-SINGLE", "qty_available", "100", "qty_reserved", "0", "qty_reserved2", "0"),
                null, null, null));

        // 变更前：普通写入口未受保护，更新原路径可用（同对象同入口的前后对照）
        Long version0 = jdbc.queryForObject("SELECT " + q("version") + " FROM " + q(table)
                + " WHERE " + q("id") + " = ?", Long.class, recordId);
        asTenant(TENANT, USER, () -> {
            com.sw.ck.form.api.dto.FormDataUpdateRequest req = new com.sw.ck.form.api.dto.FormDataUpdateRequest();
            req.setVersion(version0);
            req.setData(data("material", "LT04A-SINGLE", "qty_available", "100", "qty_reserved", "0",
                    "qty_reserved2", "7"));
            updateService.updateRecord(formKey, recordId, req);
            return null;
        });
        assertThat(colOf(table, "qty_reserved2", recordId)).as("变更前普通写入可用").isEqualByComparingTo("7");

        // v1 受理：预占 5（冻结版本 v1：balance=qty_available / reserved=qty_reserved）
        String reserveAction = asTenant(TENANT, USER, () -> {
            String id = actionService.create(formId, new TxnActionSaveRequest("lt04a_reserve", "LT04a预占", "RESERVE",
                    null, cfg("qty_available", "qty_reserved", 600L))).id();
            actionService.publish(id);
            return id;
        });
        TxnInvokeResult reserved = invokeReserve(reserveAction, "LT04A-R1", recordId, "5");
        assertThat(reserved.status()).isEqualTo("SUCCEEDED");
        assertThat(reserved.actionVersion()).isEqualTo(1);
        String reservationId = reserved.reservationId();
        assertThat(colOf(table, "qty_reserved", recordId)).isEqualByComparingTo("5");

        // 合法 C1 策略变更（同对象）：启用保护并纳入三个数值列；受理成功
        C1PolicyModel changed = new C1PolicyModel();
        changed.setEnabled(true);
        changed.setProtectedFields(List.of("qty_available", "qty_reserved", "qty_reserved2"));
        changed.setBalanceField("qty_available");
        changed.setReservedField("qty_reserved");
        changed.setNonNegativeAvailable(true);
        assertThat(asTenant(TENANT, USER, () -> c1PolicyService.save(formId, changed).enabled()))
                .as("合法 C1 变更被受理").isTrue();
        C1PolicyView appliedPolicy = asTenant(TENANT, USER, () -> c1PolicyService.get(formId));
        assertThat(appliedPolicy.enabled()).isTrue();

        // 变更后普通写入被拒且旧状态不变（值保持 100/5/7）
        int protectedCode = FormErrorCode.C1_WRITE_PROTECTED.getCode();
        Long version1 = jdbc.queryForObject("SELECT " + q("version") + " FROM " + q(table)
                + " WHERE " + q("id") + " = ?", Long.class, recordId);
        assertRejected(() -> asTenant(TENANT, USER, () -> {
            com.sw.ck.form.api.dto.FormDataUpdateRequest req = new com.sw.ck.form.api.dto.FormDataUpdateRequest();
            req.setVersion(version1);
            req.setData(data("material", "LT04A-SINGLE", "qty_available", "999", "qty_reserved", "0",
                    "qty_reserved2", "0"));
            updateService.updateRecord(formKey, recordId, req);
            return null;
        }), protectedCode);
        assertThat(colOf(table, "qty_available", recordId)).isEqualByComparingTo("100");
        assertThat(colOf(table, "qty_reserved", recordId)).isEqualByComparingTo("5");
        assertThat(colOf(table, "qty_reserved2", recordId)).isEqualByComparingTo("7");

        // 非法 C1 变更被拒且策略保持合法变更后的值（旧状态不变）
        C1PolicyModel illegal = new C1PolicyModel();
        illegal.setEnabled(true);
        illegal.setProtectedFields(List.of("no_such_field"));
        illegal.setBalanceField("no_such_field");
        assertRejected(() -> asTenant(TENANT, USER, () -> c1PolicyService.save(formId, illegal)),
                FormErrorCode.ACTION_FIELD_BINDING_INVALID.getCode());
        C1PolicyView afterIllegal = asTenant(TENANT, USER, () -> c1PolicyService.get(formId));
        assertThat(afterIllegal.policyJson()).as("非法变更不覆盖既有策略").isEqualTo(appliedPolicy.policyJson());
        assertThat(afterIllegal.appliedAt()).isEqualTo(appliedPolicy.appliedAt());

        // 动作重发布 v2：预占字段改指 qty_reserved2 —— 旧预占语义不得随新版本改变
        asTenant(TENANT, USER, () -> {
            actionService.update(reserveAction, new TxnActionSaveRequest(null, "LT04a预占", "RESERVE", null,
                    cfg("qty_available", "qty_reserved2", 600L)));
            actionService.publish(reserveAction);
            return null;
        });
        assertThat(asTenant(TENANT, USER, () -> actionService.get(reserveAction).currentVersion())).isEqualTo(2);

        // 同一旧预占结算：CONFIRM 动作自身声明 qty_reserved2，仍必须按受理冻结版本 v1 执行
        String confirmAction = asTenant(TENANT, USER, () -> {
            String id = actionService.create(formId, new TxnActionSaveRequest("lt04a_confirm", "LT04a确认", "CONFIRM",
                    null, cfg("qty_available", "qty_reserved2", null))).id();
            actionService.publish(id);
            return id;
        });
        TxnInvokeRequest confirmReq = new TxnInvokeRequest();
        confirmReq.setReservationId(reservationId);
        confirmReq.setInvocationKey("LT04A-C1");
        TxnInvokeResult confirmed = asTenant(TENANT, USER, () -> executor.invoke(confirmAction, confirmReq));
        assertThat(confirmed.status()).as("旧凭据跨变更结算成功").isEqualTo("SUCCEEDED");
        assertThat(confirmed.actionVersion()).as("按受理冻结版本 v1 结算").isEqualTo(1);
        assertThat(colOf(table, "qty_available", recordId)).as("余额按冻结语义扣减").isEqualByComparingTo("95");
        assertThat(colOf(table, "qty_reserved", recordId)).as("冻结版本预占字段归零").isEqualByComparingTo("0");
        assertThat(colOf(table, "qty_reserved2", recordId)).as("新版本字段未被旧预占改写（保持 7）")
                .isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_form_txn_reservation WHERE id = ?",
                String.class, reservationId)).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = 'CONFIRM'", Long.class, reservationId)).as("CONFIRM 台账恰一条").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT action_version FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = 'CONFIRM'", Integer.class, reservationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance_after FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = 'CONFIRM'", BigDecimal.class, reservationId)).isEqualByComparingTo("95");
        assertThat(jdbc.queryForObject("SELECT reserved_after FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = 'CONFIRM'", BigDecimal.class, reservationId)).isEqualByComparingTo("0");

        // 不能重复结算：同凭据再次确认被拒，台账仍一条
        TxnInvokeRequest again = new TxnInvokeRequest();
        again.setReservationId(reservationId);
        again.setInvocationKey("LT04A-C2");
        TxnInvokeResult reConfirm = asTenant(TENANT, USER, () -> executor.invoke(confirmAction, again));
        assertThat(reConfirm.status()).isEqualTo("REJECTED");
        assertThat(reConfirm.errorCode()).isEqualTo(FormErrorCode.ACTION_RESERVATION_NOT_ACTIVE.getCode());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_ledger WHERE reservation_id = ?"
                + " AND entry_type = 'CONFIRM'", Long.class, reservationId)).isEqualTo(1L);
        assertThat(colOf(table, "qty_available", recordId)).as("重复结算无第二次扣减").isEqualByComparingTo("95");

        // 结算后普通写入仍受保护（策略未失效）
        Long version2 = jdbc.queryForObject("SELECT " + q("version") + " FROM " + q(table)
                + " WHERE " + q("id") + " = ?", Long.class, recordId);
        assertRejected(() -> asTenant(TENANT, USER, () -> {
            com.sw.ck.form.api.dto.FormDataUpdateRequest req = new com.sw.ck.form.api.dto.FormDataUpdateRequest();
            req.setVersion(version2);
            req.setData(data("material", "LT04A-SINGLE2", "qty_available", "1", "qty_reserved", "0",
                    "qty_reserved2", "0"));
            updateService.updateRecord(formKey, recordId, req);
            return null;
        }), protectedCode);
        assertThat(colOf(table, "qty_available", recordId)).isEqualByComparingTo("95");

        System.out.println("[P62-EV] lt04a.single-object form=" + formId + " record=" + recordId
                + " reservation=" + reservationId + " reserveAction=" + reserveAction + "(v1->v2) confirmAction="
                + confirmAction + " policyChange=allowed(applyAt=" + appliedPolicy.appliedAt() + ")"
                + " illegalChange=rejected(policy-unchanged) settle=frozen-v1(balance95/reserved0/reserved2-7)"
                + " ordinary-write=blocked-before-and-after no-double-settle=true");
    }

    // ==================== LT04/T05 非法声明拒绝 ====================

    @Test
    @DisplayName("LT04/T05 非法声明拒绝：模型不支持的配置键在反序列化边界被收集，并在保存/发布入口明确定位拒绝")
    void unsupportedDeclarationsRejected() throws Exception {
        // 对照：未声明模型类忽略未知键（证明下面的收集来自声明的封闭模型，而非全局配置）
        TxnInvokeRequest tolerant = objectMapper.readValue(
                "{\"quantity\":\"1\",\"unknownKey\":true}", TxnInvokeRequest.class);
        assertThat(tolerant.getQuantity()).isEqualTo("1");

        TxnActionConfig withUnknown = objectMapper.readValue(
                "{\"balanceField\":\"qty_available\",\"remoteSideEffect\":{\"url\":\"http://example.invalid\"}}",
                TxnActionConfig.class);
        assertThat(withUnknown.getUnsupportedKeys()).as("未知键被收集而非静默丢弃").containsKey("remoteSideEffect");
        assertThat(withUnknown.getBalanceField()).isEqualTo("qty_available");

        // 保存入口：非法声明被明确拒绝，错误信息含违规键名
        String formId = asTenant(TENANT, USER, () -> formDefService.getFormDefByKey(FORM_KEY).getId());
        TxnActionConfig bad = cfg("qty_available", "qty_reserved", 600L);
        bad.getUnsupportedKeys().put("humanWait", Boolean.TRUE);
        bad.getUnsupportedKeys().put("transactionPropagation", "REQUIRES_NEW");
        assertThatThrownBy(() -> asTenant(TENANT, USER, () -> actionService.create(formId,
                new TxnActionSaveRequest("lt04_bad_decl", "LT04非法声明", "RESERVE", null, bad))))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("humanWait")
                .hasMessageContaining("transactionPropagation");

        // 发布入口：存量配置含未声明键（历史行/直改库）→ 结构化发布错误定位到键，发布被拒
        String storedId = asTenant(TENANT, USER, () -> actionService.create(formId,
                new TxnActionSaveRequest("lt04_stored_bad", "LT04存量非法", "RESERVE", null,
                        cfg("qty_available", "qty_reserved", 600L))).id());
        jdbc.update("UPDATE sw_form_txn_action SET config_json = ? WHERE id = ?",
                "{\"balanceField\":\"qty_available\",\"reservedField\":\"qty_reserved\","
                        + "\"expiresInSeconds\":600,\"remoteSideEffect\":{\"url\":\"http://example.invalid\"}}",
                storedId);
        assertThat(asTenant(TENANT, USER, () -> actionService.validate(storedId)))
                .anyMatch(e -> "config.remoteSideEffect".equals(e.field()));
        assertRejected(() -> asTenant(TENANT, USER, () -> actionService.publish(storedId)),
                FormErrorCode.ACTION_CONFIG_INVALID.getCode());

        // C1 策略声明同样封闭：未知键在保存入口被拒
        C1PolicyModel c1Bad = objectMapper.readValue("{\"enabled\":true,\"remoteApproval\":true}",
                C1PolicyModel.class);
        assertThat(c1Bad.getUnsupportedKeys()).containsKey("remoteApproval");
        assertThatThrownBy(() -> asTenant(TENANT, USER, () -> c1PolicyService.save(formId, c1Bad)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("remoteApproval");
        System.out.println("[P62-EV] lt04.unsupported-declaration collect=remoteSideEffect/remoteApproval"
                + " save=rejected(humanWait/propagation) publish=structured-error(config.remoteSideEffect)");
    }

    // ==================== LT04：已受理但响应丢失后的同身份回查 ====================

    @Test
    @DisplayName("LT04 响应丢失后同身份回查：同键重试返回原结果且无重复效果；记录/凭据/台账按调用身份可定位")
    void acceptedButResponseLostIsReadableByIdentity() {
        String recordId = submit("LT04-LOST", "40");
        String reserveId = publishAction("lt04_lost_reserve", "LT04丢失预占", "RESERVE", reserveCfg());
        TxnInvokeResult first = invokeReserve(reserveId, "LT04-LOST-K", recordId, "6");
        assertThat(first.status()).isEqualTo("SUCCEEDED");

        TxnInvokeResult retry = invokeReserve(reserveId, "LT04-LOST-K", recordId, "6");
        assertThat(retry.replay()).as("同键重试为重放").isTrue();
        assertThat(retry.invocationId()).isEqualTo(first.invocationId());
        assertThat(retry.reservationId()).isEqualTo(first.reservationId());
        assertThat(reservedOf(recordId)).as("重放不产生第二次效果").isEqualByComparingTo("6");
        assertThat(countInvocations(reserveId, "LT04-LOST-K")).isEqualTo(1L);
        assertThat(countLedgerByReservation(reserveId, first.reservationId())).isEqualTo(1L);

        var invocations = asTenant(TENANT, USER, () -> executor.pageInvocations(reserveId, null, null, USER, 1, 20));
        assertThat(invocations.getRecords()).anyMatch(v -> first.invocationId().equals(v.id())
                && "SUCCEEDED".equals(v.status())
                && v.resultJson() != null && v.resultJson().contains(first.reservationId()));
        var byId = asTenant(TENANT, USER, () -> executor.getInvocation(first.invocationId()));
        assertThat(byId.bizRecordId()).isEqualTo(recordId);
        var reservations = asTenant(TENANT, USER, () -> executor.pageReservations(reserveId, "ACTIVE", 1, 20));
        assertThat(reservations.getRecords()).anyMatch(v -> first.reservationId().equals(v.id())
                && v.quantity().compareTo(new BigDecimal("6")) == 0);
        var ledger = asTenant(TENANT, USER, () ->
                executor.pageLedger(reserveId, 1, first.reservationId(), 1, 20));
        assertThat(ledger.getRecords()).hasSize(1);
        assertThat(ledger.getRecords().get(0).entryType()).isEqualTo("RESERVE");
        System.out.println("[P62-EV] lt04.lost-response retry=replay/same-invocation no-duplicate-effect"
                + " readback=invocation/reservation/ledger");
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

    private String multiFieldDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62多字段库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false},"
                + "{\"name\":\"qty_reserved2\",\"type\":\"NUMBER\",\"label\":\"预占量2\",\"required\":false}]}";
    }

    /** 用真实模板（含签名）填充数据行：列顺序与模板映射行一致，数据行从第 3 行开始。 */
    private byte[] fillTemplate(byte[] template, Object[][] rows) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(template));
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("模板");
            for (int r = 0; r < rows.length; r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(2 + r);
                for (int c = 0; c < rows[r].length; c++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(c);
                    Object value = rows[r][c];
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else {
                        cell.setCellValue(value == null ? "" : String.valueOf(value));
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private TxnInvokeResult invokeReserve(String actionId, String key, String recordId, String qty) {
        return asTenant(TENANT, USER, () -> executor.invoke(actionId, reserveReq(key, recordId, qty)));
    }

    private TxnInvokeResult invokeAdjust(String actionId, String key, String recordId, String qty) {
        TxnInvokeRequest req = new TxnInvokeRequest();
        req.setInvocationKey(key);
        req.setRecordId(recordId);
        req.setQuantity(qty);
        return asTenant(TENANT, USER, () -> executor.invoke(actionId, req));
    }

    private BigDecimal colOf(String table, String column, String recordId) {
        return jdbc.queryForObject("SELECT " + q(column) + " FROM " + q(table)
                + " WHERE " + q("id") + " = ?", BigDecimal.class, recordId);
    }

    private long countLedgerByReservation(String actionId, String reservationId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_ledger WHERE action_id = ?"
                + " AND reservation_id = ?", Long.class, actionId, reservationId);
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

    private long countReservationsByRecord(String recordId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_reservation WHERE record_id = ?",
                Long.class, recordId);
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
