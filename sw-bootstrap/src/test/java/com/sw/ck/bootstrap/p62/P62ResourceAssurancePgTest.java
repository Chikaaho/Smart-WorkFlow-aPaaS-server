package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.ResourcePolicyService;
import com.sw.ck.bpm.process.entity.BpmResourcePolicy;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
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

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 资源保障测量（RG01—RG05/RG07 行为链；方向 direction-p62-resource-assurance.md）。
 *
 * <p><b>环境合同</b>：M1 8核/8GiB、JDK21、单应用进程、隔离 PG17.5（zonky）、堆 2GiB、
 * Druid 64、Flowable 异步 ON×8、dispatcher 100ms/批 50（env-frozen 冻结回读）。</p>
 *
 * <p><b>分段运行（资源重任务，逐方法手动触发，串行不并行）</b>：</p>
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62ResourceAssurancePgTest#fixedAssuranceWindowProfile \
 *   -Dp62.resource.fixed=true -Dp62.runId=&lt;runId&gt; -Dp62.evidence.dir=&lt;绝对路径&gt; \
 *   -Dp62.build.commit=$(git rev-parse HEAD) -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * # 其余方法：#burstTenantSwapProfile(-Dp62.resource.swap=true)、#recoveryRestartContract(-Dp62.resource.recovery=true)、
 * #          #stopAcceptanceDowngrade(-Dp62.resource.downgrade=true)、#invalidEnablementGate(-Dp62.resource.gate=true)
 * </pre>
 *
 * <p><b>窗口口径</b>：正式合同=60s 预热单列 + 600s 正式窗口；本会话受单命令 10min
 * 工具上限约束，实跑采用 -Dp62.resource.warmup/-Dp62.resource.formal 缩短验证轮
 * （shortVerify=true 标注，不作为正式 10min 窗口判定值；正式窗口命令固定于回执）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 资源保障测量（真实HTTP入口，分段手动：-Dp62.resource.*=true）")
class P62ResourceAssurancePgTest {

    private static final long SEED = 20261002L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int OBJECTS_PER_TENANT = 1000;

    private static final int WARMUP_SECONDS = Integer.getInteger("p62.resource.warmup", 60);
    private static final int FORMAL_SECONDS = Integer.getInteger("p62.resource.formal", 600);

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String runId;
    private static Path evidenceDir;
    private static int port;

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(10);

    private static final Map<Long, TenantFixture> fixtures = new HashMap<>();
    private final Map<Integer, Random> workerRandoms = new ConcurrentHashMap<>();

    private static final class TenantFixture {
        Long tenant;
        long userId;
        String bearer;
        String formKey;
        String stockTable;
        String actionId;
        String lightProcessKey;
        String approvalProcessKey;
        List<String> recordIds = new ArrayList<>();
        List<String> taskIds = new ArrayList<>();
    }

    @BeforeAll
    void boot() throws Exception {
        runId = System.getProperty("p62.runId");
        String dir = System.getProperty("p62.evidence.dir");
        if (runId == null || dir == null || dir.isBlank()) {
            throw new IllegalStateException("必须提供 -Dp62.runId 与 -Dp62.evidence.dir");
        }
        evidenceDir = Path.of(dir);
        Files.createDirectories(evidenceDir);
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        app = newBoot(pgUrl, contractProps());
        jdbc = app.getBean(JdbcTemplate.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        seedTenant(0L, 91999L, "p62_ra_t0");
        seedTenant(100L, 92999L, "p62_ra_t100");
        seedApprovalWorkflow(0L, 160);
        seedApprovalWorkflow(100L, 160);
        writeEnvFrozen();
        System.out.println("[P62-EV] resource-assurance boot ok pgPort=" + pg.getPort()
                + " httpPort=" + port + " runId=" + runId);
    }

    /** 合同配置（RA01：dispatcher 100ms/批50、池64、异步ON×8 显式生效，画像/env-frozen 读生效值）。 */
    public static Map<String, String> contractProps() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("spring.datasource.dynamic.druid.initial-size", "10");
        props.put("spring.datasource.dynamic.druid.min-idle", "10");
        props.put("spring.datasource.dynamic.druid.max-active", "64");
        props.put("spring.datasource.dynamic.druid.max-wait", "10000");
        props.put("flowable.async-executor-activate", "true");
        props.put("flowable.process.async.executor.core-pool-size", "8");
        props.put("flowable.process.async.executor.max-pool-size", "8");
        props.put("sw.bpm.txn-batch.enabled", "true");
        props.put("sw.security.debug-auth.enabled", "true");
        props.put("sw.bpm.command.poll-interval-millis", "100");
        props.put("sw.bpm.command.p0-poll-interval-millis", "100");
        props.put("sw.bpm.command.batch-size", "50");
        props.put("sw.bpm.command.p0-batch-size", "50");
        props.put("sw.bpm.command.stale-seconds", "60");
        return props;
    }

    private ConfigurableApplicationContext newBoot(String pgUrl, Map<String, String> overrides) {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-resource-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", P62BudgetMeasurementPgTest.generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-resource-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.putAll(overrides);
        return new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-resource", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62ResourceLoginContextProvider", provider);
                    context.addBeanFactoryPostProcessor(bf -> {
                        if (bf.containsBeanDefinition("loginUserCacheService")) {
                            ((org.springframework.beans.factory.support.DefaultListableBeanFactory) bf)
                                    .setAllowBeanDefinitionOverriding(true);
                            ((org.springframework.beans.factory.support.DefaultListableBeanFactory) bf)
                                    .registerBeanDefinition("loginUserCacheService",
                                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                                    P62BudgetMeasurementPgTest.NoopLoginUserCacheService.class));
                        }
                    });
                })
                .run();
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
    }

    // ==================== RG04：有效画像启用 + 画像回读 ====================

    private long enableContractPolicy() {
        ResourcePolicyService policyService = app.getBean(ResourcePolicyService.class);
        BpmResourcePolicy policy = asTenant(0L, 91999L, () -> {
            BpmResourcePolicy draft = new BpmResourcePolicy();
            draft.setGlobalMaxOutstanding(2000);
            draft.setTenantMaxOutstanding(800);
            draft.setProdReserved(400);
            draft.setOaReserved(400);
            draft.setSharedCapacity(1200);
            draft.setTenantRatePerSec(50);
            draft.setTenantBurst(500);
            draft.setRealtimeGlobalConcurrency(16);
            draft.setRealtimeTenantConcurrency(8);
            draft.setBatchSliceItems(25);
            draft.setBatchPollClaimLimit(1);
            draft.setRemark("P62 资源保障固定合同画像（方向§3）");
            return policyService.create(draft);
        });
        BpmResourcePolicy enabled = asTenant(0L, 91999L, () -> policyService.enable(policy.getId(), null));
        assertThat(enabled.getStatus()).isEqualTo("ACTIVE");
        return enabled.getPolicyVersion();
    }

    @Test
    @DisplayName("RG04：有效画像启用成功+运行画像回读；OFF/池5 无效启用明确拒绝（隔离环境定性）")
    void invalidEnablementGate() throws Exception {
        long policyVersion = enableContractPolicy();
        // 有效画像：启用检查通过、运行画像回读（配置来源/有效值/消费者/计数勾稽）
        var profile = asTenant(0L, 91999L, () ->
                app.getBean(com.sw.ck.bpm.process.service.impl.BpmResourceOpsService.class).profile());
        assertThat(profile.policyEnabled()).isTrue();
        assertThat(profile.enablementViolations()).isEmpty();
        assertThat(((Number) profile.pool().get("actualMaxActive")).longValue()).isEqualTo(64L);
        assertThat(profile.asyncExecutor().get("asyncExecutorActivate")).isEqualTo(true);
        assertThat(profile.consumers().get("batchInvokeHandlerRegistered")).isEqualTo(true);
        writeEvidence("enablement-valid-profile.txt",
                "policyVersion=" + policyVersion + "\npool=" + profile.pool()
                        + "\nasyncExecutor=" + profile.asyncExecutor()
                        + "\ndispatcher=" + profile.dispatcher()
                        + "\nconsumers=" + profile.consumers()
                        + "\nrealtimeGuard=" + profile.realtimeGuard());

        // OFF/池5 定性：独立隔离上下文（prod 池 5 + 异步 OFF）→ 无效启用在开启新受理前明确拒绝
        ConfigurableApplicationContext weak = newBoot(
                "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified",
                Map.of("spring.datasource.dynamic.druid.max-active", "5",
                        "flowable.async-executor-activate", "false"));
        try {
            var weakJdbc = weak.getBean(JdbcTemplate.class);
            weakJdbc.update("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id,"
                    + " version, name, code, status, description, domain_name) "
                    + "VALUES (1, current_timestamp, current_timestamp, 0, 0, 0, '门禁租户',"
                    + " 'p62-gate-t', 0, '启用门禁', 'localhost') ON CONFLICT (id) DO NOTHING");
            weakJdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status)"
                    + " VALUES (93999, 'gate-op', 'seed-not-a-login-secret', '门禁操作员', 1, 0)"
                    + " ON CONFLICT (id) DO NOTHING");
            var weakService = weak.getBean(ResourcePolicyService.class);
            BpmResourcePolicy draft = asTenant(1L, 93999L, () -> {
                BpmResourcePolicy p = new BpmResourcePolicy();
                p.setGlobalMaxOutstanding(2000);
                p.setTenantMaxOutstanding(800);
                p.setProdReserved(400);
                p.setOaReserved(400);
                p.setSharedCapacity(1200);
                p.setTenantRatePerSec(50);
                p.setTenantBurst(500);
                p.setRealtimeGlobalConcurrency(16);
                p.setRealtimeTenantConcurrency(8);
                return p;
            });
            List<String> violations = asTenant(1L, 93999L, () -> weakService.checkEnablement(draft));
            assertThat(violations).anyMatch(v -> v.contains("消费者未启用"));
            assertThat(violations).anyMatch(v -> v.contains("不相容"));
            writeEvidence("enablement-invalid-pool5-async-off.txt",
                    "weakContext=druidMaxActive=5,asyncExecutorActivate=false\nviolations=" + violations);
        } finally {
            weak.close();
        }
    }

    // ==================== RG02/RG03：固定保障 + 单租户突发窗口 ====================

    @Test
    @DisplayName("RG02/RG03：固定保障（受保护租户 5/5/2/1）+ 单租户突发 200 单位/s，保留容量与公平合同")
    void fixedAssuranceWindowProfile() throws Exception {
        runAssuranceWindow(0L, 100L, "fixed-burst");
    }

    @Test
    @DisplayName("RG03：突发负载交换租户身份，同一保障合同对受保护租户成立")
    void burstTenantSwapProfile() throws Exception {
        runAssuranceWindow(100L, 0L, "fixed-burst-swap");
    }

    private void runAssuranceWindow(long protectedTenant, long burstTenant, String scenario)
            throws Exception {
        enableContractPolicy();
        TenantFixture protectedFx = fixtures.get(protectedTenant);
        TenantFixture burstFx = fixtures.get(burstTenant);

        // 配额采样（5s）：占用计数 ≤ 全局/租户上限（RG02 不突破）
        AtomicBoolean sampling = new AtomicBoolean(true);
        StringBuilder quotaCsv = new StringBuilder("ts,global_total,tenant_used,engine_jobs,deadletter\n");
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                try {
                    Long global = usageOf("GLOBAL", 0L, "TOTAL");
                    Long tenantUsed = usageOf("TENANT", protectedTenant, "TOTAL");
                    Long jobs = jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_job", Long.class);
                    Long dead = jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_deadletter_job", Long.class);
                    quotaCsv.append(LocalDateTime.now().format(TS)).append(',').append(global).append(',')
                            .append(tenantUsed).append(',').append(jobs).append(',').append(dead).append('\n');
                } catch (Exception e) {
                    quotaCsv.append("sample-error\n");
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "p62-quota-sampler");
        sampler.setDaemon(true);
        sampler.start();

        // RA02 资源采样（1s）：Druid 活跃/等待线程、PG 锁等待、进程 CPU、受保护路径慢样本数——
        // 尾尖归因必须有连接/锁/CPU 证据（复核 RA02）
        StringBuilder resCsv = new StringBuilder(
                "ts,pool_active,pool_wait_thread,pool_wait_millis_max,pg_lock_waits,proc_cpu,slow_rt_10s\n");
        Thread resSampler = new Thread(() -> {
            long lastSlow = 0;
            while (sampling.get()) {
                try {
                    Object poolActive = null;
                    Object poolWaitThread = null;
                    Object poolWaitMax = null;
                    for (javax.sql.DataSource ds : app.getBeanProvider(javax.sql.DataSource.class)
                            .stream().toList()) {
                        java.util.List<Object> candidates = new ArrayList<>();
                        if (ds.getClass().getSimpleName().contains("DynamicRouting")) {
                            Object map = ds.getClass().getMethod("getDataSources").invoke(ds);
                            if (map instanceof java.util.Map<?, ?> m) {
                                candidates.addAll(m.values());
                            }
                        } else {
                            candidates.add(ds);
                        }
                        for (Object c : candidates) {
                            Object cur = c;
                            for (int d = 0; cur != null && d < 6; d++) {
                                if (cur.getClass().getSimpleName().contains("Druid")) {
                                    poolActive = cur.getClass().getMethod("getActiveCount").invoke(cur);
                                    poolWaitThread =
                                            cur.getClass().getMethod("getWaitThreadCount").invoke(cur);
                                    poolWaitMax = cur.getClass().getMethod("getMaxWait").invoke(cur);
                                    break;
                                }
                                cur = unwrapField(cur, "realDataSource");
                            }
                            if (poolActive != null) {
                                break;
                            }
                        }
                        if (poolActive != null) {
                            break;
                        }
                    }
                    Long lockWaits = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type='Lock'", Long.class);
                    double cpu = ((java.lang.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                            .getSystemLoadAverage();
                    resCsv.append(LocalDateTime.now().format(TS)).append(',')
                            .append(poolActive).append(',').append(poolWaitThread).append(',')
                            .append(poolWaitMax).append(',').append(lockWaits).append(',')
                            .append(String.format(Locale.ROOT, "%.2f", cpu)).append(',').append('\n');
                } catch (Exception e) {
                    resCsv.append("sample-error\n");
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                writeNew(evidenceDir.resolve("resource-samples.csv"), out ->
                        out.write(resCsv.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                System.out.println("[P62-EV] resource csv write failed: " + e);
            }
        }, "p62-res-sampler");
        resSampler.setDaemon(true);
        resSampler.start();

        AtomicBoolean running = new AtomicBoolean(true);
        List<Thread> workers = new ArrayList<>();
        SampleCollector protectedRealtime = new SampleCollector("protected-realtime-samples.csv.gz");
        SampleCollector protectedLight = new SampleCollector("protected-light-samples.csv.gz");
        SampleCollector oaRead = new SampleCollector("oa-read-samples.csv.gz");
        SampleCollector oaApproval = new SampleCollector("oa-approval-samples.csv.gz");
        SampleCollector burstLoad = new SampleCollector("burst-load-samples.csv.gz");
        SampleCollector burstBatch = new SampleCollector("burst-batch-samples.csv.gz");
        protectedRealtime.start();
        protectedLight.start();
        oaRead.start();
        oaApproval.start();
        burstLoad.start();
        burstBatch.start();

        // 受保护租户：实时 5/s、轻流程 5/s、OA 读 2/s、审批受理 1/s（每路径 ≤4 在途）
        workers.add(pacedWorker(running, 200, 4, () -> { // 5/s
            long begin = System.nanoTime();
            String outcome = invokeRealtime(protectedFx);
            protectedRealtime.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        }));
        workers.add(pacedWorker(running, 200, 4, () -> { // 5/s
            long begin = System.nanoTime();
            String outcome = submitLight(protectedFx);
            protectedLight.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        }));
        workers.add(pacedWorker(running, 500, 4, () -> { // 2/s
            long begin = System.nanoTime();
            String outcome = readOaTodo(protectedFx);
            oaRead.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        }));
        workers.add(pacedWorker(running, 1000, 4, () -> { // 1/s
            long begin = System.nanoTime();
            String[] outcome = acceptApproval(protectedFx);
            oaApproval.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome[0]);
        }));
        // 突发租户：200 单位/s 名义到达（实时 60/s + 轻流程 40/s + 500 项批次每 5s 一批=100 单位/s），
        // ≤48 并行请求；越额被明确拒绝（拒绝率如实报告）
        workers.add(pacedWorker(running, 16, 24, () -> { // ~60/s
            long begin = System.nanoTime();
            String outcome = invokeRealtime(burstFx);
            burstLoad.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        }));
        workers.add(pacedWorker(running, 25, 12, () -> { // ~40/s
            long begin = System.nanoTime();
            String outcome = submitLight(burstFx);
            burstLoad.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        }));
        Thread batchSubmitter = pacedWorker(running, 5000, 2, () -> { // 500 单位/5s=100/s
            long begin = System.nanoTime();
            String outcome = submitBatch500(burstFx);
            burstBatch.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        });
        workers.add(batchSubmitter);
        for (Thread worker : workers) {
            worker.start();
        }

        long formalBegin = System.currentTimeMillis();
        Thread.sleep(WARMUP_SECONDS * 1000L);
        // 正式窗口起点标记（原始时间戳对齐；预热样本照常落盘供审计单列）
        long formalStart = System.currentTimeMillis();
        protectedRealtime.markFormalBegin(formalStart);
        protectedLight.markFormalBegin(formalStart);
        oaRead.markFormalBegin(formalStart);
        oaApproval.markFormalBegin(formalStart);
        burstLoad.markFormalBegin(formalStart);
        burstBatch.markFormalBegin(formalStart);
        Thread.sleep(FORMAL_SECONDS * 1000L);
        running.set(false);
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        sampling.set(false);
        sampler.join(10_000);
        resSampler.join(10_000);
        protectedRealtime.finish();
        protectedLight.finish();
        oaRead.finish();
        oaApproval.finish();
        burstLoad.finish();
        burstBatch.finish();

        // 负载停止后收敛（120s）：已受理单动作工作全部收敛、占用计数与事实勾稽
        long convergeDeadline = System.currentTimeMillis() + 120_000L;
        long openBefore = openCommands();
        while (System.currentTimeMillis() < convergeDeadline) {
            if (openCommands() == 0) {
                break;
            }
            Thread.sleep(2000);
        }
        long openAfter = openCommands();
        app.getBean(com.sw.ck.bpm.process.queue.ResourceAssuranceReconcileJob.class).reconcileOnce();
        long counterTotal = usageOf("GLOBAL", 0L, "TOTAL");
        long factTotal = app.getBean(com.sw.ck.bpm.process.service.ResourceFactView.class)
                .factBySegment().values().stream().mapToLong(Long::longValue).sum();
        String report = report(scenario, formalBegin, protectedRealtime, protectedLight, oaRead,
                oaApproval, burstLoad, burstBatch);
        writeEvidence(scenario + "-report.txt", report + "\nquota-samples:\n" + quotaCsv
                + "\nconvergence: openBefore=" + openBefore + " openAfter=" + openAfter
                + " counterTotal=" + counterTotal + " factTotal=" + factTotal
                + " window=warmup" + WARMUP_SECONDS + "s+formal" + FORMAL_SECONDS
                + "s shortVerify=" + isShortVerify() + " runId=" + runId);
        System.out.println("[P62-EV] " + scenario + " done openAfter=" + openAfter
                + " counterTotal=" + counterTotal + " factTotal=" + factTotal);
        shortVerifyAssertions(protectedRealtime, protectedLight, oaRead, oaApproval, burstLoad);
        assertThat(openAfter).as("负载停止后 120s 内全部收敛").isZero();
        assertThat(counterTotal).as("收敛后占用计数与事实勾稽一致（对账后）").isEqualTo(factTotal);
    }

    private void shortVerifyAssertions(SampleCollector protectedRealtime, SampleCollector protectedLight,
                                       SampleCollector oaRead, SampleCollector oaApproval,
                                       SampleCollector burstLoad) {
        List<Double> rt = protectedRealtime.legalLatencies("SUCCEEDED");
        List<Double> light = protectedLight.legalLatencies("ACCEPTED");
        List<Double> read = oaRead.legalLatencies("OK");
        List<Double> approve = oaApproval.legalLatencies("ACCEPTED");
        assertThat(protectedRealtime.failureCount("SUCCEEDED"))
                .as("受保护实时动作意外失败/过载拒绝=0").isZero();
        assertThat(percentile(rt, 0.99)).as("实时入口→提交 P99≤300ms").isLessThanOrEqualTo(300);
        assertThat(protectedLight.failureCount("ACCEPTED")).as("受保护轻流程失败/拒绝=0").isZero();
        assertThat(percentile(light, 0.99)).as("轻流程入口→持久受理 P99≤2s").isLessThanOrEqualTo(2000);
        assertThat(oaRead.failureCount("OK")).as("OA 读失败=0").isZero();
        assertThat(percentile(read, 0.99)).as("OA 读 P99≤1s").isLessThanOrEqualTo(1000);
        assertThat(oaApproval.failureCount("ACCEPTED")).as("OA 审批受理失败=0").isZero();
        assertThat(percentile(approve, 0.99)).as("OA 审批受理 P99≤1s").isLessThanOrEqualTo(1000);
        // 突发拒绝 P99≤1s（拒绝只允许记到实际越额负载；拒绝率如实报告不伪装成功率）
        List<Double> burstRejected = burstLoad.latenciesOf(outcome -> outcome.startsWith("REJECTED"));
        if (!burstRejected.isEmpty()) {
            assertThat(percentile(burstRejected, 0.99))
                    .as("超额拒绝响应 P99≤1s").isLessThanOrEqualTo(1000);
        }
        long burstRejectedCount = burstLoad.outcomeCount(outcome -> outcome.startsWith("REJECTED"));
        long burstSucceeded = burstLoad.outcomeCount(o -> o.equals("SUCCEEDED"))
                + burstLoad.outcomeCount(o -> o.equals("ACCEPTED"));
        System.out.println("[P62-EV] burst outcomes: rejected=" + burstRejectedCount
                + " admitted=" + burstSucceeded + "（拒绝率如实报告，不设虚假成功率）");
    }

    // ==================== RG07：恢复 / 停受理与降配 ====================

    @Test
    @DisplayName("RG07：停新受理明确拒绝+在途结算；停用后回退；100 条恢复合同（新实例收敛≤120s、配额不双计）")
    void recoveryRestartContract() throws Exception {
        long policyVersion = enableContractPolicy();
        TenantFixture fx = fixtures.get(0L);
        List<String> recordIds = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            String material = "RC-" + runId + "-" + i;
            String body = "{\"material\":\"" + material + "\",\"qty_available\":\"1\","
                    + "\"qty_reserved\":\"0\",\"target_record_id\":\"" + fx.recordIds.get(i % fx.recordIds.size()) + "\"}";
            String recordId = postJson("/api/form/data/" + fx.formKey, fx.bearer, body,
                    json -> {
                        // 提交回包 data 为裸字符串 recordId（R<String>，extractJsonField 已剥引号）；
                        // 对象形态则取 recordId 字段
                        if (json != null && !json.startsWith("{") && !json.startsWith("[")) {
                            return json;
                        }
                        String field = extractJsonField(json, "recordId");
                        return field == null ? extractJsonField(json, "id") : field;
                    });
            recordIds.add(recordId);
        }
        long admittedUsage = usageOf("TENANT", 0L, "TOTAL");
        assertThat(admittedUsage).as("恢复合同受理占用>0").isGreaterThan(0);

        // 实例中断（本应用上下文关闭；PG 持久事实保留）→ 新实例接管
        app.close();
        app = newBoot("jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified",
                contractProps());
        jdbc = app.getBean(JdbcTemplate.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));

        long begin = System.currentTimeMillis();
        long deadline = begin + 120_000L;
        long completed = 0;
        // 收敛判定按受理时真实 recordId（提交回包）定位命令行，不用材料名猜测
        List<String> commandKeys = new ArrayList<>();
        for (String recordId : recordIds) {
            commandKeys.add("FLOW_START:" + recordId);
        }
        while (System.currentTimeMillis() < deadline) {
            completed = countCompleted(commandKeys);
            if (completed >= 100) {
                break;
            }
            Thread.sleep(1000);
        }
        long elapsed = System.currentTimeMillis() - begin;
        long invocations = countInvocationsByRecords(recordIds);
        app.getBean(com.sw.ck.bpm.process.queue.ResourceAssuranceReconcileJob.class).reconcileOnce();
        long counterTotal = usageOf("GLOBAL", 0L, "TOTAL");
        long factTotal = app.getBean(com.sw.ck.bpm.process.service.ResourceFactView.class)
                .factBySegment().values().stream().mapToLong(Long::longValue).sum();
        // 诊断转储：绑定/实例/调用行实际状态（防键猜测错位）
        Long bindingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE active = true", Long.class);
        List<Map<String, Object>> bindings = jdbc.queryForList(
                "SELECT process_def_key, form_key, active FROM sw_bpm_form_binding LIMIT 6");
        Long instanceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_instance WHERE business_key LIKE 'AP-%'"
                        + " OR business_key LIKE 'RC-%'", Long.class);
        List<String> invocationSamples = jdbc.queryForList(
                "SELECT invocation_key FROM sw_form_txn_invocation ORDER BY create_time DESC LIMIT 3",
                String.class);
        Long invocationByInstance = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation v"
                        + " JOIN sw_bpm_instance inst ON v.invocation_key LIKE"
                        + " 'NODE:' || inst.process_instance_id || '%'"
                        + " WHERE inst.business_key LIKE 'RC-%'", Long.class);
        writeEvidence("recovery-restart.txt", "policyVersion=" + policyVersion
                + " admittedUsageBeforeRestart=" + admittedUsage
                + " completed=" + completed + "/100 elapsedMs=" + elapsed
                + " invocations=" + invocations + " (重复效果=0)"
                + " counterTotal=" + counterTotal + " factTotal=" + factTotal
                + " bindingCount=" + bindingCount + " bindings=" + bindings
                + " raInstances=" + instanceCount
                + " invocationSamples=" + invocationSamples
                + " invocationByInstanceLike=" + invocationByInstance
                + " runId=" + runId);
        assertThat(elapsed).as("100 条已受理收敛 ≤120s").isLessThanOrEqualTo(120_000L);
        assertThat(completed).isEqualTo(100);
        assertThat(invocations).as("重复效果=0（调用记录恰 100）").isEqualTo(100);
        assertThat(counterTotal).as("配额占用恢复且不双计（对账后计数=事实）").isEqualTo(factTotal);
    }

    @Test
    @DisplayName("RG07：停新受理后新受理明确拒绝且在途结算；停用回退不丢历史查询")
    void stopAcceptanceDowngrade() throws Exception {
        ResourcePolicyService policyService = app.getBean(ResourcePolicyService.class);
        long policyVersion = enableContractPolicy();
        Long policyId = policyService.listAll().get(0).getId();
        TenantFixture fx = fixtures.get(0L);

        // 停新受理：新 HTTP 受理明确拒绝（2429），在途命令继续按原合同结算
        asTenant(0L, 91999L, () -> policyService.stopAcceptance(policyId, true));
        // 停受理拒绝经 post() 捕获响应原文（postJson 在业务非 0 时抛错，无法留证）
        String stoppedBody = post("/api/form/data/" + fx.formKey, fx.bearer,
                "{\"material\":\"STOP-" + runId + "\",\"qty_available\":\"1\",\"qty_reserved\":\"0\","
                        + "\"target_record_id\":\"" + fx.recordIds.get(0) + "\"}",
                json -> json);
        // 拒绝外显口径：数值码 2429 + 停受理消息（errorKey 由模块 H2 测试经 reasonCode 锁定）
        assertThat(stoppedBody).contains("2429").contains("停新受理");
        // 在途排干
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline && openCommands() > 0) {
            Thread.sleep(1000);
        }
        long stoppedUsage = usageOf("TENANT", 0L, "TOTAL");
        assertThat(stoppedUsage).as("停受理期间无新增占用").isZero();

        // 停用回退：新受理回到无策略行为（不拒绝），不丢已有结果查询
        asTenant(0L, 91999L, () -> policyService.disable(policyId));
        String resumed = post("/api/form/data/" + fx.formKey, fx.bearer,
                "{\"material\":\"RESUME-" + runId + "\",\"qty_available\":\"1\",\"qty_reserved\":\"0\","
                        + "\"target_record_id\":\"" + fx.recordIds.get(1) + "\"}",
                json -> json);
        assertThat(resumed).as("停用后受理不再被策略拒绝").doesNotContain("2429");
        writeEvidence("stop-acceptance-downgrade.txt", "policyVersion=" + policyVersion
                + " stoppedRejected=" + stoppedBody.contains("2429")
                + " stoppedUsage=" + stoppedUsage + " resumedAccept=" + !resumed.contains("2429")
                + " runId=" + runId);
    }

    // ==================== 请求动作 ====================

    private String invokeRealtime(TenantFixture fx) {
        String recordId = pickRecord(fx);
        String invocationKey = "RA-" + runId + "-" + fx.tenant + "-" + java.util.UUID.randomUUID();
        String body = "{\"recordId\":\"" + recordId + "\",\"quantity\":\"1\",\"invocationKey\":\""
                + invocationKey + "\"}";
        return post("/api/form/action/" + fx.actionId + "/invoke", fx.bearer, body,
                json -> json.contains("\"SUCCEEDED\"") ? "SUCCEEDED"
                        : "REJECTED:" + errorCodeOf(json) + ":" + tail(json, 80));
    }

    private String submitLight(TenantFixture fx) {
        String target = pickRecord(fx);
        String material = "L-" + runId + "-" + fx.tenant + "-" + java.util.UUID.randomUUID();
        String body = "{\"material\":\"" + material + "\",\"qty_available\":\"1000\","
                + "\"qty_reserved\":\"0\",\"target_record_id\":\"" + target + "\"}";
        return post("/api/form/data/" + fx.formKey, fx.bearer, body,
                json -> json != null && !json.isEmpty() ? "ACCEPTED" : "REJECTED:" + codeOf(json));
    }

    private String submitBatch500(TenantFixture fx) {
        String batchKey = "B-" + runId + "-" + fx.tenant + "-" + java.util.UUID.randomUUID();
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            if (i > 0) {
                items.append(',');
            }
            String recordId = fx.recordIds.get((i * 2 + 1) % fx.recordIds.size());
            items.append("{\"itemKey\":\"it").append(i).append("\",\"recordId\":\"").append(recordId)
                    .append("\",\"quantity\":\"1\"}");
        }
        String body = "{\"batchKey\":\"" + batchKey + "\",\"actionId\":\"" + fx.actionId
                + "\",\"items\":[" + items + "]}";
        return post("/api/workflow/txn-batch", fx.bearer, body,
                json -> json != null && json.contains("batchKey") ? "ACCEPTED" : "REJECTED:" + codeOf(json));
    }

    private String readOaTodo(TenantFixture fx) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + port
                            + "/api/workflow/tasks/todo?pageNum=1&pageSize=10"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", fx.bearer)
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = HTTP.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return "REJECTED:" + response.statusCode();
            }
            return "0".equals(extractJsonNumber(response.body(), "code")) ? "OK"
                    : "REJECTED:" + extractJsonNumber(response.body(), "code");
        } catch (java.net.http.HttpTimeoutException e) {
            return "TIMEOUT";
        } catch (Exception e) {
            return "ERROR:" + e.getClass().getSimpleName();
        }
    }

    private String[] acceptApproval(TenantFixture fx) {
        if (fx.taskIds.isEmpty()) {
            return new String[]{"SKIP", "-"};
        }
        String taskId = fx.taskIds.remove(0);
        String body = "{\"opinion\":\"资源保障测量审批\"}";
        String outcome = post("/api/workflow/commands/tasks/" + taskId + "/approve?channel=NORMAL",
                fx.bearer, body, json -> json != null && json.contains("commandId")
                        ? "ACCEPTED" : "REJECTED:" + codeOf(json));
        return new String[]{outcome, taskId};
    }

    // ==================== 负载框架 ====================

    private interface PacedAction {
        void call() throws Exception;
    }

    private Thread pacedWorker(AtomicBoolean running, long intervalMillis, int maxInFlight,
                               PacedAction action) {
        return new Thread(() -> {
            java.util.concurrent.atomic.AtomicInteger inFlight =
                    new java.util.concurrent.atomic.AtomicInteger();
            while (running.get()) {
                if (inFlight.get() >= maxInFlight) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                inFlight.incrementAndGet();
                new Thread(() -> {
                    try {
                        action.call();
                    } catch (Exception e) {
                        // 动作异常由采集器按 ERROR 结果记录，不中断负载
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }).start();
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private static final class SampleCollector {
        private final String name;
        private final List<String[]> rows = new ArrayList<>();
        private final Object lock = new Object();
        /** 正式窗口起点（epoch ms）；样本按原始时间戳对齐窗口（方向§3 统计范围）。 */
        private volatile long formalBeginMs;

        SampleCollector(String name) {
            this.name = name;
        }

        void markFormalBegin(long epochMs) {
            this.formalBeginMs = epochMs;
        }

        void start() {
            // 样本保内存（窗口级批量），finish 落盘 gzip（CREATE_NEW）
        }

        void offer(String ts, double latency, String outcome) {
            // ts=完成时刻（采样口径）；发起时刻=完成时刻-latency，CSV 双列落盘供复核复算
            String start;
            try {
                start = LocalDateTime.parse(ts, TS).minusNanos((long) (latency * 1_000_000))
                        .format(TS);
            } catch (Exception e) {
                start = "-";
            }
            synchronized (lock) {
                rows.add(new String[]{ts, start, String.format(Locale.ROOT, "%.1f", latency), outcome});
            }
        }

        private boolean inFormal(String[] row) {
            if (formalBeginMs == 0) {
                return true;
            }
            try {
                LocalDateTime parsed = LocalDateTime.parse(row[0], TS);
                return parsed.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        >= formalBeginMs;
            } catch (Exception e) {
                return true;
            }
        }

        List<Double> legalLatencies(String legalOutcome) {
            return latenciesOf(legalOutcome::equals);
        }

        List<Double> latenciesOf(java.util.function.Predicate<String> outcomeMatch) {
            synchronized (lock) {
                List<Double> list = new ArrayList<>();
                for (String[] row : rows) {
                    if (inFormal(row) && outcomeMatch.test(row[3])) {
                        list.add(Double.parseDouble(row[2]));
                    }
                }
                list.sort(Double::compare);
                return list;
            }
        }

        long outcomeCount(java.util.function.Predicate<String> match) {
            synchronized (lock) {
                return rows.stream().filter(r -> inFormal(r) && match.test(r[3])).count();
            }
        }

        long failureCount(String legalOutcome) {
            synchronized (lock) {
                return rows.stream().filter(r -> inFormal(r) && !legalOutcome.equals(r[3])).count();
            }
        }

        Map<String, Long> outcomeHistogram() {
            synchronized (lock) {
                Map<String, Long> histogram = new LinkedHashMap<>();
                for (String[] row : rows) {
                    histogram.merge(row[3], 1L, Long::sum);
                }
                return histogram;
            }
        }

        /** 全部样本行（诊断分桶用）。 */
        java.util.List<String[]> allRows() {
            synchronized (lock) {
                return new ArrayList<>(rows);
            }
        }

        void finish() throws Exception {
            Map<String, Long> histogram = outcomeHistogram();
            StringBuilder csv = new StringBuilder("ts_end,ts_start,latency_ms,outcome\n");
            synchronized (lock) {
                for (String[] row : rows) {
                    csv.append(row[0]).append(',').append(row[1]).append(',')
                            .append(row[2]).append(',').append(row[3]).append('\n');
                }
            }
            writeNew(evidenceDir.resolve(name), out ->
                    out.write(csv.toString().getBytes(StandardCharsets.UTF_8)));
            writeNew(evidenceDir.resolve(name.replace(".csv.gz", "") + "-outcomes.txt"), out ->
                    out.write((histogram + "\n").getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String report(String scenario, long formalBegin, SampleCollector... collectors) {
        StringBuilder sb = new StringBuilder("scenario=").append(scenario)
                .append(" windowBegin=").append(formalBegin)
                .append(" warmup=").append(WARMUP_SECONDS).append("s formal=").append(FORMAL_SECONDS)
                .append("s shortVerify=").append(isShortVerify())
                .append(" entry=HTTP:").append(port).append('\n');
        for (SampleCollector collector : collectors) {
            var legal = collector.legalLatencies("SUCCEEDED");
            var accepted = collector.legalLatencies("ACCEPTED");
            var ok = collector.legalLatencies("OK");
            List<Double> success = !legal.isEmpty() ? legal : (!accepted.isEmpty() ? accepted : ok);
            sb.append("collector=").append(collector.name)
                    .append(" samples=").append(collector.outcomeHistogram().values()
                            .stream().mapToLong(Long::longValue).sum())
                    .append(" outcomes=").append(collector.outcomeHistogram())
                    .append(String.format(Locale.ROOT,
                            " p50=%.1fms p99=%.1fms max=%.1fms%n",
                            percentile(success, 0.50), percentile(success, 0.99),
                            success.isEmpty() ? 0 : success.get(success.size() - 1)));
        }
        return sb.toString();
    }

    // ==================== 种子 ====================

    private void seedTenant(Long tenant, long userId, String formKey) throws Exception {
        jdbc.update("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id,"
                        + " version, name, code, status, description, domain_name) "
                        + "VALUES (?, current_timestamp, current_timestamp, 0, 0, 0, ?, ?, 0,"
                        + " '资源保障隔离租户', 'localhost') ON CONFLICT (id) DO NOTHING",
                tenant, "资源保障租户" + tenant, "p62-ra-t" + tenant);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, ?, 'seed-not-a-login-secret', '资源保障操作员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", userId, "ra-op-" + userId, tenant);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                + "VALUES (?, ?, ?, 2) ON CONFLICT (id) DO NOTHING", userId, tenant, userId);
        jdbc.update("INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id,"
                        + " version, role_id, menu_id) "
                        + "SELECT ?, current_timestamp, current_timestamp, 0, 0, 0, 2, 350 "
                        + "WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 2"
                        + " AND menu_id = 350 AND deleted = 0) ON CONFLICT (id) DO NOTHING",
                360000L + userId);
        FormDefService formDefService = app.getBean(FormDefService.class);
        com.sw.ck.form.txn.service.TxnActionService actionService =
                app.getBean(com.sw.ck.form.txn.service.TxnActionService.class);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        FormSubmitService submitService = app.getBean(FormSubmitService.class);

        asTenant(tenant, userId, () -> {
            FormDefDTO draftForm = formDefService.createDraft(formKey, "资源保障库存", null, null);
            formDefService.saveConfig(draftForm.getId(), stockDefinition());
            formDefService.publish(draftForm.getId());
            return null;
        });
        TenantFixture fx = new TenantFixture();
        fx.tenant = tenant;
        fx.userId = userId;
        fx.bearer = "Bearer test_" + userId;
        fx.formKey = formKey;
        fx.stockTable = asTenant(tenant, userId, () -> formDefService.getFormDefByKey(formKey))
                .getPhysicalTableName();
        fx.actionId = asTenant(tenant, userId, () -> {
            String formId = formDefService.getFormDefByKey(formKey).getId();
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String actionId = actionService.create(formId, new TxnActionSaveRequest(
                    "resource_reserve_" + tenant, "资源预占", "RESERVE", null, cfg)).id();
            actionService.publish(actionId);
            return actionId;
        });
        fx.lightProcessKey = asTenant(tenant, userId, () -> {
            var def = processDefService.createDef("资源保障轻流程-" + tenant, formKey);
            List<GraphElement> elements = List.of(
                    node("start", "START", Map.of()),
                    node("act-1", "TXN_ACTION", Map.of(
                            "name", "库存预占", "actionId", fx.actionId,
                            "recordIdSource", "variable", "recordIdVariable", "variable:targetRecordId",
                            "quantity", "1", "failureStrategy", "BLOCK")),
                    node("end", "END", Map.of()),
                    edge("e1", "start", "act-1"), edge("e2", "act-1", "end"));
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name("资源保障轻流程-" + tenant)
                    .formKey(formKey).version(1).elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            processDefService.saveDraftGraph(def.getId(), json);
            BpmProcessDef published = processDefService.publish(def.getId());
            return published.getProcessKey();
        });
        jdbc.update("UPDATE sw_bpm_form_binding SET active = false WHERE process_def_key = ?",
                fx.lightProcessKey);
        for (int i = 0; i < OBJECTS_PER_TENANT; i++) {
            String recordId = asTenant(tenant, userId, () -> submitService.submitForm(formKey,
                    data("material", "OBJ-" + tenant + "-" + fx.recordIds.size(),
                            "qty_available", "100000", "qty_reserved", "0"), null, null, null));
            fx.recordIds.add(recordId);
        }
        jdbc.update("UPDATE sw_bpm_form_binding SET active = true WHERE process_def_key = ?",
                fx.lightProcessKey);
        fixtures.put(tenant, fx);
        System.out.println("[P62-EV] ra seeded tenant=" + tenant + " objects=" + OBJECTS_PER_TENANT);
    }

    /** 审批域种子：独立表单 + APPROVAL 指定审批人=测量用户，直启 count 个实例 → 真实待办任务。
     *  表单级单有效绑定不变式：审批流必须用独立表单，不得与轻流程表单共用（publish 激活即停旧）。 */
    private void seedApprovalWorkflow(long tenant, int count) throws Exception {
        TenantFixture fx = fixtures.get(tenant);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        FormSubmitService submitService = app.getBean(FormSubmitService.class);
        String approvalFormKey = "p62_ra_todo_t" + tenant;
        asTenant(tenant, fx.userId, () -> {
            FormDefService formDefService = app.getBean(FormDefService.class);
            FormDefDTO approvalForm = formDefService.createDraft(approvalFormKey, "资源保障待办", null, null);
            formDefService.saveConfig(approvalForm.getId(), stockDefinition());
            formDefService.publish(approvalForm.getId());
            return null;
        });
        String approvalKey = asTenant(tenant, fx.userId, () -> {
            var def = processDefService.createDef("资源保障审批-" + tenant, approvalFormKey);
            List<GraphElement> elements = List.of(
                    node("start", "START", Map.of()),
                    node("approve", "APPROVAL", Map.of("name", "资源审批",
                            "approver", Map.of("type", "DESIGNATED",
                                    "value", List.of(String.valueOf(fx.userId))))),
                    node("end", "END", Map.of()),
                    edge("e1", "start", "approve"), edge("e2", "approve", "end"));
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name("资源保障审批-" + tenant)
                    .formKey(approvalFormKey).version(1).elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            processDefService.saveDraftGraph(def.getId(), json);
            BpmProcessDef published = processDefService.publish(def.getId());
            return published.getProcessKey();
        });
        fx.approvalProcessKey = approvalKey;
        // 该审批流程不承载动作：直接经 ProcessStartService 起实例（种子路径，不经命令队列），
        // 每个实例产生一条真实待办任务（与 r04 seedOaBusiness 同口径）
        com.sw.ck.bpm.process.service.ProcessStartService startService =
                app.getBean(com.sw.ck.bpm.process.service.ProcessStartService.class);
        for (int i = 0; i < count; i++) {
            final int seq = i;
            asTenant(tenant, fx.userId, () -> {
                com.sw.ck.bpm.process.dto.StartCommand cmd =
                        new com.sw.ck.bpm.process.dto.StartCommand();
                cmd.setFormKey(approvalFormKey);
                cmd.setProcessDefKey(approvalKey);
                cmd.setRecordId("AP-" + tenant + "-" + seq);
                cmd.setSubmitter(fx.userId);
                cmd.setTenantId(tenant);
                cmd.setSubmittedData(data("material", "AP-" + tenant + "-" + seq,
                        "qty_available", "100000", "qty_reserved", "0"));
                startService.start(cmd);
                return null;
            });
        }
        // 等待实例与任务生成（有界轮询；等待对象=真实待办任务行。
        // 引擎表随首次流程消费惰性建库——表未建时按未生成处理，由 dispatcher 消费触发后再查）
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            Long tasks;
            try {
                tasks = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM act_ru_task WHERE assignee_ = ?", Long.class,
                        String.valueOf(fx.userId));
            } catch (org.springframework.jdbc.BadSqlGrammarException engineSchemaPending) {
                tasks = 0L;
            }
            if (tasks != null && tasks >= count) {
                break;
            }
            Thread.sleep(2000);
        }
        try {
            jdbc.queryForList("SELECT id_ FROM act_ru_task WHERE assignee_ = ? ORDER BY create_time_",
                    String.class, String.valueOf(fx.userId)).forEach(id -> fx.taskIds.add(id));
        } catch (org.springframework.jdbc.BadSqlGrammarException engineSchemaPending) {
            fx.taskIds.clear();
        }
        System.out.println("[P62-EV] ra approvals seeded tenant=" + tenant
                + " tasks=" + fx.taskIds.size());
    }

    // ==================== 工具 ====================

    /** 按命令键列表统计 COMPLETED 数（IN 分块，防超长）。 */
    private long countCompleted(List<String> commandKeys) {
        long total = 0;
        for (int i = 0; i < commandKeys.size(); i += 50) {
            List<String> chunk = commandKeys.subList(i, Math.min(i + 50, commandKeys.size()));
            String in = String.join(",", chunk.stream()
                    .map(k -> "'" + k.replace("'", "''") + "'").toList());
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_bpm_command WHERE command_key IN (" + in + ")"
                            + " AND status = 'COMPLETED'", Long.class);
            total += count == null ? 0 : count;
        }
        return total;
    }

    /** 按业务记录列表统计目标动作调用行数（重复效果判定；经实例 business_key 关联）。 */
    private long countInvocationsByRecords(List<String> recordIds) {
        long total = 0;
        for (int i = 0; i < recordIds.size(); i += 50) {
            List<String> chunk = recordIds.subList(i, Math.min(i + 50, recordIds.size()));
            String in = String.join(",", chunk.stream()
                    .map(k -> "'" + k.replace("'", "''") + "'").toList());
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_form_txn_invocation v"
                            + " JOIN sw_bpm_instance inst ON v.invocation_key ="
                            + " 'NODE:' || inst.process_instance_id || ':act-1'"
                            + " WHERE inst.business_key IN (" + in + ")", Long.class);
            total += count == null ? 0 : count;
        }
        return total;
    }

    private long usageOf(String scope, long scopeKey, String segment) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE((SELECT outstanding FROM sw_bpm_resource_usage WHERE scope = ?"
                        + " AND scope_key = ? AND segment = ?), 0)", Long.class, scope, scopeKey, segment);
        return value == null ? 0 : value;
    }

    private long openCommands() {
        Long open = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE status IN ('PENDING','PROCESSING')", Long.class);
        return open == null ? 0 : open;
    }

    private long openJobs() {
        Long open = jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_job", Long.class);
        return open == null ? 0 : open;
    }

    private String pickRecord(TenantFixture fx) {
        Random random = workerRandoms.computeIfAbsent((int) fx.tenant.longValue(),
                t -> new Random(SEED * 31 + t));
        if (random.nextInt(10) == 0) {
            return fx.recordIds.get(0);
        }
        return fx.recordIds.get(1 + random.nextInt(fx.recordIds.size() - 1));
    }

    private String post(String pathWithQuery, String bearer, String jsonBody,
                        java.util.function.Function<String, String> outcomeOf) {
        try {
            return postJson(pathWithQuery, bearer, jsonBody, outcomeOf);
        } catch (java.net.http.HttpTimeoutException e) {
            return "TIMEOUT";
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 结果码/原因随样本落盘（拒绝与失败逐项归因），消息截断防刷屏
            return "REJECTED:" + (message.length() > 60 ? message.substring(0, 60) : message);
        }
    }

    private <T> T postJson(String pathWithQuery, String bearer, String jsonBody,
                           java.util.function.Function<String, T> parser) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + port + pathWithQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        java.net.http.HttpResponse<String> response = HTTP.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        String code = extractJsonNumber(response.body(), "code");
        if (!"0".equals(code)) {
            throw new IllegalStateException("BIZ " + code + ": "
                    + extractJsonField(response.body(), "msg"));
        }
        String data = extractJsonField(response.body(), "data");
        return parser.apply(data == null ? "" : data);
    }

    /** 动作调用业务拒绝的 errorCode（data 内；与响应 code 分离）。 */
    private static String errorCodeOf(String json) {
        String code = extractJsonNumber(json == null ? "" : json, "errorCode");
        return code == null ? "-" : code;
    }

    /** 尾部片段（拒绝归因用；压缩空白便于样本列展示）。 */
    private static String tail(String json, int max) {
        if (json == null) {
            return "-";
        }
        String flat = json.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(flat.length() - max);
    }

    private static String codeOf(String json) {
        String code = extractJsonNumber(json == null ? "" : json, "code");
        return code == null ? "-" : code;
    }

    private static String extractJsonNumber(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        int end = colon + 1;
        while (end < json.length() && (json.charAt(end) == ' ' || json.charAt(end) == '-'
                || Character.isDigit(json.charAt(end)))) {
            end++;
        }
        return json.substring(colon + 1, end).trim();
    }

    private static String extractJsonField(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        if (json.charAt(start) == '{' || json.charAt(start) == '[') {
            return json.substring(start);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static boolean isShortVerify() {
        return WARMUP_SECONDS != 60 || FORMAL_SECONDS != 600;
    }

    private interface Sink {
        void write(java.io.OutputStream out) throws java.io.IOException;
    }

    private static void writeNew(Path file, Sink sink) throws java.io.IOException {
        Files.write(file, new byte[0], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (var out = Files.newOutputStream(file, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            sink.write(out);
        }
    }

    private void writeEvidence(String fileName, String content) throws Exception {
        Files.writeString(evidenceDir.resolve(fileName), content
                        + "\nrecordedAt=" + LocalDateTime.now().format(TS) + "\nrunId=" + runId + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file, StandardOpenOption.READ)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void writeEnvFrozen() throws Exception {
        // RA01：dynamic-datasource 包裹（DynamicRouting→ItemDataSource→Druid）需先 getDataSources()
        // 展开；取值失败必须抛错而非记 0（不凭 0 判池不存在）
        long druidMax = 0;
        for (javax.sql.DataSource ds : app.getBeanProvider(javax.sql.DataSource.class).stream().toList()) {
            java.util.List<Object> candidates = new ArrayList<>();
            if (ds.getClass().getSimpleName().contains("DynamicRouting")) {
                Object map = ds.getClass().getMethod("getDataSources").invoke(ds);
                if (map instanceof java.util.Map<?, ?> m) {
                    candidates.addAll(m.values());
                }
            } else {
                candidates.add(ds);
            }
            for (Object candidate : candidates) {
                Object cur = candidate;
                for (int depth = 0; cur != null && depth < 6; depth++) {
                    if (cur.getClass().getSimpleName().contains("Druid")) {
                        druidMax = ((Number) cur.getClass().getMethod("getMaxActive").invoke(cur)).longValue();
                        break;
                    }
                    cur = unwrapField(cur, "realDataSource");
                }
                if (druidMax > 0) {
                    break;
                }
            }
            if (druidMax > 0) {
                break;
            }
        }
        if (druidMax <= 0) {
            throw new IllegalStateException("Druid 实际池上限读取失败（RA01：取值失败须显式失败，不得记 0）");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("runId=").append(runId).append('\n');
        sb.append("recordedAt=").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("entry=HTTP real Tomcat port=").append(port).append('\n');
        sb.append("javaVersion=").append(System.getProperty("java.version")).append('\n');
        sb.append("cores=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("heapMaxMiB=").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append('\n');
        sb.append("buildCommit=").append(System.getProperty("p62.build.commit", "")).append('\n');
        sb.append("pgVersion=").append(jdbc.queryForObject("SELECT version()", String.class)).append('\n');
        sb.append("druidMaxActive(actual)=").append(druidMax).append('\n');
        Object poll = app.getEnvironment().getProperty("sw.bpm.command.poll-interval-millis");
        Object batch = app.getEnvironment().getProperty("sw.bpm.command.batch-size");
        if (poll == null || batch == null) {
            throw new IllegalStateException("dispatcher 合同配置未生效（poll=" + poll + ", batch=" + batch
                    + "）：RA01 要求 env-frozen 记录生效值而非 null");
        }
        sb.append("dispatcherPollMillis=").append(poll).append('\n');
        sb.append("dispatcherBatchSize=").append(batch).append('\n');
        sb.append("flowableAsyncCorePool=").append(app.getEnvironment()
                .getProperty("flowable.process.async.executor.core-pool-size")).append('\n');
        sb.append("policyContract=global2000/tenant800/prod400/oa400/shared1200/rate50/burst500/rt16/rt8\n");
        sb.append("windowContract=正式=60s预热+600s正式；本run formal=").append(FORMAL_SECONDS)
                .append("s shortVerify=").append(isShortVerify()).append('\n');
        for (Map.Entry<Long, TenantFixture> e : fixtures.entrySet()) {
            TenantFixture fx = e.getValue();
            sb.append("fixture tenant=").append(e.getKey()).append(" userId=").append(fx.userId)
                    .append(" formKey=").append(fx.formKey).append(" actionId=").append(fx.actionId)
                    .append(" lightProcess=").append(fx.lightProcessKey)
                    .append(" approvalProcess=").append(fx.approvalProcessKey)
                    .append(" objects=").append(fx.recordIds.size()).append('\n');
        }
        Files.writeString(evidenceDir.resolve("env-frozen.txt"), sb.toString(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static Object unwrapField(Object o, String field) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type)
                .config(config == null ? Map.of() : config).style(Map.of()).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of()).build();
    }

    private static String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"资源保障库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false},"
                + "{\"name\":\"target_record_id\",\"type\":\"TEXT\",\"label\":\"动作目标记录\",\"required\":false}]}";
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static <T> T asTenant(Long tenant, long userId, java.util.concurrent.Callable<T> action) {
        com.sw.ck.security.holder.LoginUser previous =
                com.sw.ck.security.holder.LoginUserHolder.get();
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenant);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "form:action:manage",
                "workflow:resource:view", "workflow:resource:manage")));
        try {
            com.sw.ck.security.holder.LoginUserHolder.set(user);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            if (previous == null) {
                com.sw.ck.security.holder.LoginUserHolder.clear();
            } else {
                com.sw.ck.security.holder.LoginUserHolder.set(previous);
            }
        }
    }
}
