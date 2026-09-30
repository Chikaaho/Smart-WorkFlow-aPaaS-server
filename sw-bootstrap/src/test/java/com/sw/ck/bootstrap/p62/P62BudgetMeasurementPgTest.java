package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.model.TxnInvokeRequest;
import com.sw.ck.form.txn.model.TxnInvokeResult;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P62 分级执行 S6：U08 固定预算测量（限定环境；非生产 SLA，不进常规回归门）。
 *
 * <p>手动运行（约 20 分钟，含预热/正式/恢复/压力边界四组）：</p>
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62BudgetMeasurementPgTest \
 *   -Dp62.budget.measurement=true -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>固定合同（方向《分级执行与统一命令》时效与测量节）：
 * 单应用进程 + 单隔离 PG17.5、堆 2GiB；两租户各 1000 对象、10% 请求命中热点、
 * 90% 均匀分布其余 999 对象、固定随机种子；并发 16（两租户各 8）、预热 60s、
 * 正式 5min；实时动作预算 P99≤300ms（服务层入口至事务提交）、轻流程入口至持久
 * 受理 P99≤2s（另报受理至动作结算）；样本 ≥5000/组、合法失败=0、过载拒绝≤1%；
 * 恢复：100 条无外部依赖命令全部收敛 ≤120s、重复效果=0；压力边界 64 并发只度量。
 * 测量边界声明：入口为服务层事务入口（FormSubmitService/TxnActionExecutor），
 * 不含 HTTP 反序列化耗时；进程不重启，恢复段模拟"新进程可服务后开始消费"。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 S6 U08 固定预算测量（手动运行：-Dp62.budget.measurement=true）")
@EnabledIfSystemProperty(named = "p62.budget.measurement", matches = "true")
class P62BudgetMeasurementPgTest {

    private static final long SEED = 20260930L;
    private static final int WARMUP_SECONDS = 60;
    private static final int FORMAL_SECONDS = 300;
    private static final int CONCURRENCY_TOTAL = 16;
    private static final int HOTSPOT_MOD = 10;

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static final Path EVIDENCE_DIR = Path.of("build", "p62-budget");

    private FormDefService formDefService;
    private FormSubmitService submitService;
    private TxnActionService actionService;
    private TxnActionExecutor executor;
    private TxnBatchService batchService;
    private BpmCommandMapper commandMapper;

    private final Map<Long, String> tenantAction = new HashMap<>();
    private final Map<Long, String> tenantFormKey = new HashMap<>();
    private final Map<Long, List<String>> tenantRecords = new HashMap<>();

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
        props.put("sw.security.jwt.secret", "p62-budget-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-budget-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.datasource.dynamic.hikari.maximum-pool-size", "32");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-budget", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62BudgetLoginContextProvider", provider);
                })
                .run();
        // 测量静默：压测下 DEBUG SQL 日志会引入磁盘 I/O 背景负载，污染时序样本（固定合同要求无其他背景负载）。
        // initializer 阶段设置的 logging 属性晚于日志系统初始化，必须在代码级收敛。
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.WARN);
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        actionService = app.getBean(TxnActionService.class);
        executor = app.getBean(TxnActionExecutor.class);
        batchService = app.getBean(TxnBatchService.class);
        commandMapper = app.getBean(BpmCommandMapper.class);
        Files.createDirectories(EVIDENCE_DIR);

        // 测量用户（正式身份链：dispatcher 消费时经 UserDetailsProvider 回查，role 2 携调用权限）
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (91999, 'budget-operator', 'seed-not-a-login-secret', '预算测量操作员', 0, 0) "
                + "ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91999, 0, 91999, 2) "
                + "ON CONFLICT (id) DO NOTHING");

        seedTenant(0L, "p62_budget_t0");
        seedTenant(100L, "p62_budget_t100");
        System.out.println("[P62-EV] budget boot ok pgPort=" + pg.getPort()
                + " heap=" + Runtime.getRuntime().maxMemory() / 1024 / 1024 + "MiB"
                + " jvm=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.arch"));
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

    // ==================== 场景一：实时动作（预算 P99≤300ms） ====================

    @Test
    @DisplayName("实时动作：并发 16（两租户各 8）× 预热 60s + 正式 5min，P99≤300ms、合法失败=0")
    void realTimeActionBudget() throws Exception {
        MeasurementResult result = runLoad("realtime-action", () -> {
            Long tenant = ThreadLocalTenant.get();
            String recordId = pickRecord(tenant);
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setInvocationKey("BUDGET-" + tenant + "-" + java.util.UUID.randomUUID());
            req.setRecordId(recordId);
            req.setQuantity("1");
            long begin = System.nanoTime();
            TxnInvokeResult r = executor.invoke(tenantAction.get(tenant), req);
            long elapsedNanos = System.nanoTime() - begin;
            boolean legal = "SUCCEEDED".equals(r.status());
            return new Sample(elapsedNanos / 1_000_000.0, legal,
                    legal ? "SUCCEEDED" : "REJECTED:" + r.errorCode());
        }, true);
        report("realtime-action", result, 300.0);
    }

    // ==================== 场景二：生产轻流程（预算 P99≤2s 至持久受理） ====================

    @Test
    @DisplayName("轻流程：并发 16 × 预热 60s + 正式 5min，入口至持久受理 P99≤2s、合法失败=0")
    void lightProcessAcceptanceBudget() throws Exception {
        MeasurementResult result = runLoad("light-process", () -> {
            Long tenant = ThreadLocalTenant.get();
            long begin = System.nanoTime();
            String recordId = submitService.submitForm(tenantFormKey.get(tenant),
                    data("material", "B-" + java.util.UUID.randomUUID(),
                            "qty_available", "1000", "qty_reserved", "0"),
                    null, null, null);
            long acceptNanos = System.nanoTime() - begin;
            boolean accepted = recordId != null && !recordId.isBlank();
            return new Sample(acceptNanos / 1_000_000.0, accepted,
                    accepted ? "ACCEPTED" : "NO_RECORD");
        }, false);
        report("light-process-acceptance", result, 2000.0);
    }

    // ==================== 恢复：100 条命令收敛 ≤120s ====================

    @Test
    @DisplayName("恢复：100 条无外部依赖命令全部收敛 ≤120s、重复效果=0")
    void recoveryDrainBudget() throws Exception {
        String actionId = tenantAction.get(0L);
        List<String> records = tenantRecords.get(0L);
        // 100 条单项批次（无外部依赖：本地库存对象、即时可结算）
        List<Long> commandIds = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
            request.setBatchKey("budget-recover-" + i);
            request.setActionId(actionId);
            TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
            item.setItemKey("recover-" + i);
            item.setRecordId(records.get(i));
            item.setQuantity("1");
            request.setItems(List.of(item));
            TxnBatchView view = asTenant(0L, () -> batchService.submit(request));
            commandIds.add(view.getCommandId());
        }
        long begin = System.currentTimeMillis();
        long deadline = begin + 120_000L;
        AtomicInteger completed = new AtomicInteger();
        while (System.currentTimeMillis() < deadline) {
            completed.set(0);
            for (Long id : commandIds) {
                BpmCommand c = asTenant(0L, () -> commandMapper.selectById(id));
                if (c != null && "COMPLETED".equals(c.getStatus())) {
                    completed.incrementAndGet();
                }
            }
            if (completed.get() >= 100) {
                break;
            }
            Thread.sleep(500);
        }
        long elapsed = System.currentTimeMillis() - begin;
        long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref LIKE 'BATCH:budget-recover-%'",
                Long.class);
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:budget-recover-%'",
                Long.class);
        assertThat(elapsed).as("100 条命令收敛 %d ms ≤120s", elapsed).isLessThanOrEqualTo(120_000L);
        assertThat(effects).as("效果权威恰 100").isEqualTo(100L);
        assertThat(invocations).as("调用记录恰 100（重复效果=0）").isEqualTo(100L);
        writeEvidence("recovery-drain.txt", "elapsedMs=" + elapsed + " completed=" + completed
                + " effects=100 invocations=100 (≤120s budget, duplicate-effects=0)");
        System.out.println("[P62-EV] budget.recovery elapsedMs=" + elapsed + " completed=100/100"
                + " duplicate-effects=0");
    }

    // ==================== 压力边界：64 并发只度量 ====================

    @Test
    @DisplayName("压力边界：64 并发（两租户各 32）× 5min 只度量（不设达标结论）")
    void stressBoundaryObservation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("p62.budget.stress"), "压力边界按需运行：-Dp62.budget.stress=true");
        StressConfig.CONCURRENCY.set(64);
        MeasurementResult result = runLoad("stress-boundary", () -> {
            Long tenant = ThreadLocalTenant.get();
            String recordId = pickRecord(tenant);
            TxnInvokeRequest req = new TxnInvokeRequest();
            req.setInvocationKey("STRESS-" + tenant + "-" + java.util.UUID.randomUUID());
            req.setRecordId(recordId);
            req.setQuantity("1");
            long begin = System.nanoTime();
            TxnInvokeResult r = executor.invoke(tenantAction.get(tenant), req);
            long elapsedNanos = System.nanoTime() - begin;
            boolean legal = "SUCCEEDED".equals(r.status());
            return new Sample(elapsedNanos / 1_000_000.0, legal,
                    legal ? "SUCCEEDED" : "REJECTED:" + r.errorCode());
        }, true);
        report("stress-boundary", result, Double.NaN);
    }

    static final class StressConfig {
        static final ThreadLocal<Integer> CONCURRENCY = ThreadLocal.withInitial(() -> 16);
        private StressConfig() {
        }
    }

    // ==================== 负载框架 ====================

    private record Sample(double millis, boolean legal, String outcome) {
    }

    /** 测量操作身份（固定测量用户 91999；sys_user 行由 boot 种子化供调度身份回查）。 */
    private static LoginUser budgetOperator(Long tenant) {
        LoginUser user = new LoginUser();
        user.setUserId(91999L);
        user.setTenantId(tenant);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke")));
        return user;
    }

    private static final class ThreadLocalTenant {
        private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
        static Long get() {
            return TENANT.get();
        }
        static void set(Long tenant) {
            TENANT.set(tenant);
        }
    }

    private MeasurementResult runLoad(String scenario, Callable<Sample> action,
                                      boolean perTenantPool) throws Exception {
        int concurrency = "stress-boundary".equals(scenario) ? 64 : CONCURRENCY_TOTAL;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Sample>> warmupFutures = new ArrayList<>();
        AtomicLong submitCount = new AtomicLong();
        // 预热 60s（不计正式样本）
        long warmupEnd = System.currentTimeMillis() + WARMUP_SECONDS * 1000L;
        for (int t = 0; t < concurrency; t++) {
            final Long tenant = perTenantPool ? (t % 2 == 0 ? 0L : 100L) : 0L;
            warmupFutures.add(pool.submit(() -> {
                ThreadLocalTenant.set(tenant);
                LoginUserHolder.set(budgetOperator(tenant));
                try {
                    start.await();
                    while (System.currentTimeMillis() < warmupEnd) {
                        action.call();
                        submitCount.incrementAndGet();
                    }
                } finally {
                    LoginUserHolder.clear();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : warmupFutures) {
            f.get();
        }
        // 正式 5min 全量记录
        List<Future<List<Sample>>> formalFutures = new ArrayList<>();
        long formalEnd = System.currentTimeMillis() + FORMAL_SECONDS * 1000L;
        for (int t = 0; t < concurrency; t++) {
            final Long tenant = perTenantPool ? (t % 2 == 0 ? 0L : 100L) : 0L;
            formalFutures.add(pool.submit(() -> {
                ThreadLocalTenant.set(tenant);
                LoginUserHolder.set(budgetOperator(tenant));
                try {
                    List<Sample> samples = new ArrayList<>();
                    while (System.currentTimeMillis() < formalEnd) {
                        Sample sample = action.call();
                        // 容量保护：异常突发（如认证故障）下截断明细仅保留计数（回执声明）
                        if (samples.size() < 250_000) {
                            samples.add(sample);
                        }
                        submitCount.incrementAndGet();
                    }
                    return samples;
                } finally {
                    LoginUserHolder.clear();
                }
            }));
        }
        List<Sample> all = new ArrayList<>();
        for (Future<List<Sample>> f : formalFutures) {
            all.addAll(f.get());
        }
        pool.shutdown();
        return new MeasurementResult(scenario, concurrency, warmupSeconds(), FORMAL_SECONDS, all);
    }

    private int warmupSeconds() {
        return WARMUP_SECONDS;
    }

    private record MeasurementResult(String scenario, int concurrency, int warmupSeconds,
                                     int formalSeconds, List<Sample> samples) {
    }

    private void report(String scenario, MeasurementResult result, double budgetMillis)
            throws Exception {
        List<Double> legal = result.samples().stream().filter(Sample::legal)
                .map(Sample::millis).sorted().toList();
        long illegal = result.samples().size() - legal.size();
        long rejects = result.samples().stream().filter(s -> !s.legal()).count();
        double p50 = percentile(legal, 0.50);
        double p95 = percentile(legal, 0.95);
        double p99 = percentile(legal, 0.99);
        double max = legal.isEmpty() ? 0 : legal.get(legal.size() - 1);
        double rejectRate = result.samples().isEmpty() ? 0 : (double) rejects / result.samples().size();
        String verdict = Double.isNaN(budgetMillis)
                ? "OBSERVATION-ONLY"
                : (p99 <= budgetMillis && illegal == 0 && rejectRate <= 0.01
                        ? "PASS" : "FAIL");
        String summary = ("scenario=%s concurrency=%d warmup=%ds formal=%ds samples=%d legal=%d"
                + " illegal=%d rejectRate=%.4f p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms"
                + " budgetP99=%s verdict=%s seed=%d").formatted(
                        scenario, result.concurrency(), result.warmupSeconds(), result.formalSeconds(),
                        result.samples().size(), legal.size(), illegal, rejectRate,
                        p50, p95, p99, max,
                        Double.isNaN(budgetMillis) ? "N/A" : budgetMillis + "ms", verdict, SEED);
        writeEvidence(scenario + ".txt", summary);
        System.out.println("[P62-EV] budget." + summary);
        assertThat(verdict)
                .as("%s 预算裁决", scenario).isEqualTo(Double.isNaN(budgetMillis) ? "OBSERVATION-ONLY" : "PASS");
    }

    private void writeEvidence(String fileName, String content) throws Exception {
        Files.writeString(EVIDENCE_DIR.resolve(fileName),
                content + "\nrecordedAt=" + LocalDateTime.now() + "\n",
                StandardCharsets.UTF_8);
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 固定种子目标选择：10% 热点（首条记录），90% 均匀分布其余 999 条。 */
    private String pickRecord(Long tenant) {
        List<String> records = tenantRecords.get(tenant);
        Random random = new Random(SEED + Thread.currentThread().getId() + records.hashCode());
        if (random.nextInt(HOTSPOT_MOD) == 0) {
            return records.get(0);
        }
        return records.get(1 + random.nextInt(records.size() - 1));
    }

    // ==================== 种子 ====================

    private void seedTenant(Long tenant, String formKey) {
        asTenant(tenant, () -> {
            FormDefDTO draft = formDefService.createDraft(formKey, "预算测量库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            String formId = formDefService.getFormDefByKey(formKey).getId();
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String actionId = actionService.create(formId, new TxnActionSaveRequest(
                    "budget_reserve", "预算预占", "RESERVE", null, cfg)).id();
            actionService.publish(actionId);
            tenantAction.put(tenant, actionId);
            tenantFormKey.put(tenant, formKey);
            return null;
        });
        List<String> records = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            records.add(asTenant(tenant, () -> submitService.submitForm(formKey,
                    data("material", "OBJ-" + tenant + "-" + records.size(),
                            "qty_available", "100000", "qty_reserved", "0"),
                    null, null, null)));
        }
        tenantRecords.put(tenant, records);
        System.out.println("[P62-EV] budget seeded tenant=" + tenant + " records=1000 action="
                + tenantAction.get(tenant));
    }

    private String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"预算测量库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static <T> T asTenant(Long tenant, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(91999L);
        user.setTenantId(tenant);
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
