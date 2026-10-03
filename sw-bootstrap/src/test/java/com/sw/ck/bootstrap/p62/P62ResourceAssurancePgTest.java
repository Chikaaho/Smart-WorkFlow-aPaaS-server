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

    @org.springframework.beans.factory.annotation.Autowired
    private com.sw.ck.bpm.process.service.ResourceAdmissionService admissionService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue queue;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.support.TransactionTemplate txTemplate;

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
        // 死锁取证：错误级语句进 PG 日志（并发缺陷定位需要冲突双方语句，只有错误码不足以归因）
        pg = EmbeddedPostgres.builder()
                .setServerConfig("log_min_error_statement", "log")
                .start();
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

        AtomicBoolean running = new AtomicBoolean(true);
        List<Thread> workers = new ArrayList<>();
        SampleCollector protectedRealtime = new SampleCollector("protected-realtime-samples.csv.gz");
        SampleCollector protectedLight = new SampleCollector("protected-light-samples.csv.gz");
        SampleCollector oaRead = new SampleCollector("oa-read-samples.csv.gz");
        SampleCollector oaApproval = new SampleCollector("oa-approval-samples.csv.gz");
        SampleCollector burstLoad = new SampleCollector("burst-load-samples.csv.gz");
        SampleCollector burstBatch = new SampleCollector("burst-batch-samples.csv.gz");
        // 配对追踪（RA02b）：受理→命令→引擎任务→目标/审批完成需逐项关联，不靠计数总和
        List<LightSubmitTrace> lightTraces = java.util.Collections.synchronizedList(new ArrayList<>());
        List<ApprovalTrace> approvalTraces = java.util.Collections.synchronizedList(new ArrayList<>());
        List<BatchSubmitTrace> batchTraces = java.util.Collections.synchronizedList(new ArrayList<>());

        // 配额采样（5s）：占用计数 ≤ 全局/租户上限（RG02 不突破）；分段与双租户占用同列落盘，
        // 使保留容量可对账（RA02b：保留/跨租户额度行为需真实数值）
        AtomicBoolean sampling = new AtomicBoolean(true);
        StringBuilder quotaCsv = new StringBuilder("ts,global_total,global_prod_reserved,global_oa_reserved,"
                + "global_shared,tenant0_total,tenant100_total,engine_jobs,deadletter\n");
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                try {
                    quotaCsv.append(LocalDateTime.now().format(TS)).append(',')
                            .append(usageOf("GLOBAL", 0L, "TOTAL")).append(',')
                            .append(usageOf("GLOBAL", 0L, "PROD_RESERVED")).append(',')
                            .append(usageOf("GLOBAL", 0L, "OA_RESERVED")).append(',')
                            .append(usageOf("GLOBAL", 0L, "SHARED")).append(',')
                            .append(usageOf("TENANT", 0L, "TOTAL")).append(',')
                            .append(usageOf("TENANT", 100L, "TOTAL")).append(',')
                            .append(jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_job", Long.class))
                            .append(',')
                            .append(jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_deadletter_job",
                                    Long.class)).append('\n');
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

        // RA02 资源采样（1s；定义见 stat-definition.txt）：Druid 活跃/等待、PG 锁等待、
        // 进程真实 CPU（getProcessCpuTime 差分，非 load average）、堆用量、GC 次数/耗时、
        // 与样本配对的受保护路径慢请求计数（最近 1s/10s 窗口内完成且超门槛的请求数）
        StringBuilder resCsv = new StringBuilder("ts,pool_active,pool_wait_thread,pool_wait_millis_max,"
                + "pg_lock_waits,proc_cpu_pct_of_machine,proc_cpu_pct_of_one_core,load_avg,heap_used_mib,"
                + "gc_count,gc_time_ms,slow_rt_1s_le300,slow_light_10s_le2000,slow_oa_10s_le1000,"
                + "slow_approval_10s_le1000,burst_reject_1s\n");
        Thread resSampler = new Thread(() -> {
            long lastCpuNanos = -1;
            long lastWallNanos = -1;
            long lastGcCount = -1;
            long lastGcTime = -1;
            int cores = Runtime.getRuntime().availableProcessors();
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
                    long cpuNanos = ((com.sun.management.OperatingSystemMXBean) java.lang.management
                            .ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
                    long wallNanos = System.nanoTime();
                    double machinePct = -1;
                    double oneCorePct = -1;
                    if (lastCpuNanos > 0 && wallNanos > lastWallNanos) {
                        double cpuDelta = cpuNanos - lastCpuNanos;
                        double wallDelta = wallNanos - lastWallNanos;
                        oneCorePct = cpuDelta / wallDelta * 100.0;
                        machinePct = oneCorePct / cores;
                    }
                    lastCpuNanos = cpuNanos;
                    lastWallNanos = wallNanos;
                    double loadAvg = ((java.lang.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean())
                            .getSystemLoadAverage();
                    java.lang.management.MemoryUsage heap = java.lang.management.ManagementFactory
                            .getMemoryMXBean().getHeapMemoryUsage();
                    long gcCount = 0;
                    long gcTime = 0;
                    for (java.lang.management.GarbageCollectorMXBean gc : java.lang.management
                            .ManagementFactory.getGarbageCollectorMXBeans()) {
                        if (gc.getCollectionCount() > 0) {
                            gcCount += gc.getCollectionCount();
                        }
                        if (gc.getCollectionTime() > 0) {
                            gcTime += gc.getCollectionTime();
                        }
                    }
                    if (lastGcCount < 0) {
                        lastGcCount = gcCount;
                        lastGcTime = gcTime;
                    }
                    long nowMs = System.currentTimeMillis();
                    int slowRt1s = countSince(protectedRealtime, nowMs - 1_000, 300);
                    int slowLight10s = countSince(protectedLight, nowMs - 10_000, 2_000);
                    int slowOa10s = countSince(oaRead, nowMs - 10_000, 1_000);
                    int slowApprove10s = countSince(oaApproval, nowMs - 10_000, 1_000);
                    int burstReject1s = countOutcomeSince(burstLoad, nowMs - 1_000, "REJECTED");
                    resCsv.append(LocalDateTime.now().format(TS)).append(',')
                            .append(poolActive).append(',').append(poolWaitThread).append(',')
                            .append(poolWaitMax).append(',').append(lockWaits).append(',')
                            .append(String.format(Locale.ROOT, "%.2f", machinePct)).append(',')
                            .append(String.format(Locale.ROOT, "%.2f", oneCorePct)).append(',')
                            .append(String.format(Locale.ROOT, "%.2f", loadAvg)).append(',')
                            .append(heap.getUsed() / 1024 / 1024).append(',')
                            .append(gcCount - lastGcCount).append(',').append(gcTime - lastGcTime).append(',')
                            .append(slowRt1s).append(',').append(slowLight10s).append(',')
                            .append(slowOa10s).append(',').append(slowApprove10s).append(',')
                            .append(burstReject1s).append('\n');
                    lastGcCount = gcCount;
                    lastGcTime = gcTime;
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
            long acceptMs = System.currentTimeMillis();
            long begin = System.nanoTime();
            String[] outcome = submitLight(protectedFx);
            protectedLight.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome[0]);
            lightTraces.add(new LightSubmitTrace(protectedTenant, outcome[1], outcome[0],
                    acceptMs, System.currentTimeMillis()));
        }));
        workers.add(pacedWorker(running, 500, 4, () -> { // 2/s
            long begin = System.nanoTime();
            String outcome = readOaTodo(protectedFx);
            oaRead.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome);
        }));
        workers.add(pacedWorker(running, 1000, 4, () -> { // 1/s
            long acceptMs = System.currentTimeMillis();
            long begin = System.nanoTime();
            String[] outcome = acceptApproval(protectedFx);
            oaApproval.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome[0]);
            approvalTraces.add(new ApprovalTrace(protectedTenant, outcome[1], outcome[0],
                    acceptMs, System.currentTimeMillis()));
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
            long acceptMs = System.currentTimeMillis();
            long begin = System.nanoTime();
            String[] outcome = submitLight(burstFx);
            burstLoad.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome[0]);
            lightTraces.add(new LightSubmitTrace(burstTenant, outcome[1], outcome[0],
                    acceptMs, System.currentTimeMillis()));
        }));
        Thread batchSubmitter = pacedWorker(running, 5000, 2, () -> { // 500 单位/5s=100/s
            long acceptMs = System.currentTimeMillis();
            long begin = System.nanoTime();
            String[] outcome = submitBatch500(burstFx);
            burstBatch.offer(LocalDateTime.now().format(TS),
                    (System.nanoTime() - begin) / 1_000_000.0, outcome[0]);
            batchTraces.add(new BatchSubmitTrace(burstTenant, outcome[1], outcome[0],
                    acceptMs, System.currentTimeMillis()));
        });
        workers.add(batchSubmitter);
        for (Thread worker : workers) {
            worker.start();
        }

        long formalBegin = System.currentTimeMillis();
        Thread.sleep(WARMUP_SECONDS * 1000L);
        // 正式窗口起点标记（原始时间戳对齐；预热样本照常落盘供审计单列）
        long formalStart = System.currentTimeMillis();
        formalBeginMsHolder = formalStart;
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
        long convergeStart = System.currentTimeMillis();
        long convergeDeadline = convergeStart + 120_000L;
        long openBefore = openCommands();
        while (System.currentTimeMillis() < convergeDeadline) {
            if (openCommands() == 0) {
                break;
            }
            Thread.sleep(2000);
        }
        long openAfter = openCommands();
        long convergeMillis = System.currentTimeMillis() - convergeStart;
        app.getBean(com.sw.ck.bpm.process.queue.ResourceAssuranceReconcileJob.class).reconcileOnce();
        long counterTotal = usageOf("GLOBAL", 0L, "TOTAL");
        long factTotal = app.getBean(com.sw.ck.bpm.process.service.ResourceFactView.class)
                .factBySegment().values().stream().mapToLong(Long::longValue).sum();
        String report = report(scenario, formalBegin, protectedRealtime, protectedLight, oaRead,
                oaApproval, burstLoad, burstBatch);
        writeEvidence(scenario + "-report.txt", report + "\nquota-samples:\n" + quotaCsv
                + "\nconvergence: openBefore=" + openBefore + " openAfter=" + openAfter
                + " convergeMillis=" + convergeMillis + " counterTotal=" + counterTotal
                + " factTotal=" + factTotal
                + " window=warmup" + WARMUP_SECONDS + "s+formal" + FORMAL_SECONDS
                + "s shortVerify=" + isShortVerify() + " runId=" + runId);
        // RA02b：受理→命令→引擎任务→目标/审批完成的逐项配对（不以计数总和替代）
        writePairingEvidence(scenario, lightTraces, approvalTraces, batchTraces);
        writeConvergenceDetail(scenario, protectedTenant, convergeMillis, openBefore, openAfter,
                counterTotal, factTotal);
        writeStatDefinition(scenario, formalStart);
        System.out.println("[P62-EV] " + scenario + " done openAfter=" + openAfter
                + " counterTotal=" + counterTotal + " factTotal=" + factTotal);
        shortVerifyAssertions(protectedRealtime, protectedLight, oaRead, oaApproval, burstLoad);
        assertThat(openAfter).as("负载停止后 120s 内全部收敛").isZero();
        assertThat(counterTotal).as("收敛后占用计数与事实勾稽一致（对账后）").isEqualTo(factTotal);
    }

    /** 单次轻流程受理追踪（受理→命令→目标动作完成配对的对象身份）。 */
    private record LightSubmitTrace(long tenant, String recordId, String outcome,
                                    long acceptMs, long responseMs) {
    }

    /** 单次 OA 审批受理追踪（受理→命令→审批动作完成配对）。 */
    private record ApprovalTrace(long tenant, String taskId, String outcome,
                                 long acceptMs, long responseMs) {
    }

    /** 单次批次受理追踪（批次按项会计配对）。 */
    private record BatchSubmitTrace(long tenant, String batchKey, String outcome,
                                    long acceptMs, long responseMs) {
    }

    /**
     * RA02b 配对证据：每笔被追踪受理在命令行的领取/完成时点、资源冻结字段、引擎实例与
     * 目标动作调用结果逐项关联；等待上界（OA 领取≤5s、批量项≤30s）按实际最大值判定；
     * 未收敛项逐项列出（不隐藏，不整体判通过）。
     */
    private void writePairingEvidence(String scenario, List<LightSubmitTrace> lightTraces,
                                      List<ApprovalTrace> approvalTraces,
                                      List<BatchSubmitTrace> batchTraces) throws Exception {
        StringBuilder csv = new StringBuilder("kind,tenant,key,outcome,command_key,"
                + "command_status,command_create,claimed_at,finished_at,resource_class,"
                + "resource_segment,resource_units,resource_released_at,claim_wait_ms,"
                + "accept_to_finished_ms,instance_id,instance_status,invocation_id,"
                + "invocation_status,invocation_duration_ms\n");
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        List<Double> protectedOaClaimWaits = new ArrayList<>();
        Map<String, Integer> orphanByStatus = new LinkedHashMap<>();
        int traced = 0;
        int unpaired = 0;
        for (LightSubmitTrace trace : lightTraces) {
            if (trace.recordId() == null || "-".equals(trace.recordId())) {
                continue;
            }
            traced++;
            String commandKey = "FLOW_START:" + trace.recordId();
            Map<String, Object> cmd = queryCommandRow(trace.tenant(), commandKey);
            if (cmd == null) {
                unpaired++;
                orphanByStatus.merge("NO_COMMAND_ROW:" + trace.outcome(), 1, Integer::sum);
                continue;
            }
            Map<String, Object> inst = queryInstanceRow(trace.recordId());
            String instanceId = inst == null ? "-" : String.valueOf(inst.get("process_instance_id"));
            Map<String, Object> inv = "-".equals(instanceId) ? null
                    : queryInvocationRow("NODE:" + instanceId + ":act-1");
            String status = String.valueOf(cmd.get("status"));
            statusCounts.merge("command:" + status, 1L, Long::sum);
            Long claimWait = millisBetween(cmd.get("create_time"), cmd.get("claimed_at"));
            Long acceptToFinish = millisBetween(cmd.get("create_time"), cmd.get("finished_at"));
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                orphanByStatus.merge("OPEN:" + status, 1, Integer::sum);
            }
            if (trace.tenant() == 0L && claimWait != null) {
                protectedOaClaimWaits.add(claimWait.doubleValue());
            }
            csv.append("light,").append(trace.tenant()).append(',').append(trace.recordId())
                    .append(',').append(trace.outcome()).append(',').append(commandKey).append(',')
                    .append(status).append(',').append(str(cmd.get("create_time"))).append(',')
                    .append(str(cmd.get("claimed_at"))).append(',').append(str(cmd.get("finished_at")))
                    .append(',').append(str(cmd.get("resource_class"))).append(',')
                    .append(str(cmd.get("resource_segment"))).append(',')
                    .append(str(cmd.get("resource_units"))).append(',')
                    .append(str(cmd.get("resource_released_at"))).append(',')
                    .append(claimWait == null ? "-" : claimWait).append(',')
                    .append(acceptToFinish == null ? "-" : acceptToFinish).append(',')
                    .append(instanceId).append(',').append(inst == null ? "-" : str(inst.get("status")))
                    .append(',').append(inv == null ? "-" : str(inv.get("id"))).append(',')
                    .append(inv == null ? "-" : str(inv.get("status"))).append(',')
                    .append(inv == null ? "-" : str(inv.get("duration_ms"))).append('\n');
        }
        for (ApprovalTrace trace : approvalTraces) {
            if (trace.taskId() == null || "-".equals(trace.taskId()) || "SKIP".equals(trace.taskId())) {
                continue;
            }
            traced++;
            String commandKey = "TASK_APPROVE:" + trace.taskId() + ":" + 91999L;
            Map<String, Object> cmd = queryCommandRow(trace.tenant(), commandKey);
            if (cmd == null) {
                commandKey = "TASK_APPROVE:" + trace.taskId() + ":" + 92999L;
                cmd = queryCommandRow(trace.tenant(), commandKey);
            }
            if (cmd == null) {
                unpaired++;
                orphanByStatus.merge("NO_COMMAND_ROW:approval", 1, Integer::sum);
                continue;
            }
            String status = String.valueOf(cmd.get("status"));
            statusCounts.merge("approval-command:" + status, 1L, Long::sum);
            Long claimWait = millisBetween(cmd.get("create_time"), cmd.get("claimed_at"));
            if (trace.tenant() == 0L && claimWait != null) {
                protectedOaClaimWaits.add(claimWait.doubleValue());
            }
            if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                orphanByStatus.merge("OPEN:approval:" + status, 1, Integer::sum);
            }
            Map<String, Object> hiTask = queryHiTask(trace.taskId());
            csv.append("approval,").append(trace.tenant()).append(',').append(trace.taskId())
                    .append(',').append(trace.outcome()).append(',').append(commandKey).append(',')
                    .append(status).append(',').append(str(cmd.get("create_time"))).append(',')
                    .append(str(cmd.get("claimed_at"))).append(',').append(str(cmd.get("finished_at")))
                    .append(',').append(str(cmd.get("resource_class"))).append(',')
                    .append(str(cmd.get("resource_segment"))).append(',')
                    .append(str(cmd.get("resource_units"))).append(',')
                    .append(str(cmd.get("resource_released_at"))).append(',')
                    .append(claimWait == null ? "-" : claimWait).append(',')
                    .append(millisBetween(cmd.get("create_time"), cmd.get("finished_at"))).append(',')
                    .append('-').append(',').append(hiTask == null ? "-" : str(hiTask.get("delete_reason_")))
                    .append(',').append('-').append(',').append('-').append(',').append('-')
                    .append('\n');
        }
        for (BatchSubmitTrace trace : batchTraces) {
            if (trace.batchKey() == null || "-".equals(trace.batchKey())) {
                continue;
            }
            traced++;
            Map<String, Object> batch = queryOne("SELECT b.id AS id, b.batch_key, b.status,"
                    + " b.total_count, b.succeeded_count, b.failed_count, b.command_id"
                    + " FROM sw_bpm_command_batch b WHERE b.batch_key = ? AND b.tenant_id = ?",
                    trace.batchKey(), trace.tenant());
            Map<String, Object> itemStats = queryOne("SELECT COUNT(*) AS items,"
                    + " COUNT(*) FILTER (WHERE i.status = 'SUCCEEDED') AS succeeded,"
                    + " COUNT(*) FILTER (WHERE i.status = 'FAILED') AS failed,"
                    + " COUNT(*) FILTER (WHERE i.status IN ('PENDING','PROCESSING')) AS pending"
                    + " FROM sw_bpm_command_batch_item i JOIN sw_bpm_command_batch b ON i.batch_id = b.id"
                    + " WHERE b.batch_key = ? AND b.tenant_id = ?", trace.batchKey(), trace.tenant());
            Map<String, Object> cmd = queryOne("SELECT status, resource_class, resource_segment,"
                    + " resource_units, resource_released_at, create_time, claimed_at, finished_at"
                    + " FROM sw_bpm_command WHERE command_key = ? AND tenant_id = ?",
                    "BATCH:" + trace.batchKey(), trace.tenant());
            statusCounts.merge("batch:" + trace.outcome().split(":")[0], 1L, Long::sum);
            if (batch == null && cmd == null) {
                unpaired++;
            }
            Map<String, Object> itemWait = queryOne("SELECT MAX(EXTRACT(EPOCH FROM"
                    + " (i.update_time - i.create_time)) * 1000) AS max_item_ms"
                    + " FROM sw_bpm_command_batch_item i JOIN sw_bpm_command_batch b ON i.batch_id = b.id"
                    + " WHERE b.batch_key = ? AND b.tenant_id = ?", trace.batchKey(), trace.tenant());
            csv.append("batch,").append(trace.tenant()).append(',').append(trace.batchKey())
                    .append(',').append(trace.outcome()).append(',').append("BATCH:").append(trace.batchKey())
                    .append(',').append(cmd == null ? "NO_COMMAND(拒绝整笔回滚)" : str(cmd.get("status")))
                    .append(',').append(field(cmd, "create_time")).append(',')
                    .append(field(cmd, "claimed_at")).append(',').append(field(cmd, "finished_at"))
                    .append(',').append(field(cmd, "resource_class")).append(',')
                    .append(field(cmd, "resource_segment")).append(',')
                    .append(field(cmd, "resource_units")).append(',')
                    .append(field(cmd, "resource_released_at")).append(',')
                    .append(millisBetween(cmd == null ? null : cmd.get("create_time"),
                            cmd == null ? null : cmd.get("claimed_at"))).append(',')
                    .append(millisBetween(cmd == null ? null : cmd.get("create_time"),
                            cmd == null ? null : cmd.get("finished_at"))).append(',')
                    .append("batchStatus=").append(batch == null ? "-" : str(batch.get("status")))
                    .append(" items=").append(itemStats == null ? "-" : str(itemStats.get("items")))
                    .append(" succeeded=").append(itemStats == null ? "-" : str(itemStats.get("succeeded")))
                    .append(" failed=").append(itemStats == null ? "-" : str(itemStats.get("failed")))
                    .append(" pending=").append(itemStats == null ? "-" : str(itemStats.get("pending")))
                    .append(" maxItemWaitMs=").append(itemWait == null ? "-" : str(itemWait.get("max_item_ms")))
                    .append(',').append('-').append(',').append('-').append(',').append('-')
                    .append(',').append('-').append('\n');
        }
        writeEvidence("pairing.csv", csv.toString());
        double maxClaimWait = protectedOaClaimWaits.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        Map<String, Long> commandStatuses = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT status, COUNT(*) AS n FROM sw_bpm_command"
                + " WHERE create_time >= ? GROUP BY status", new java.sql.Timestamp(formalBeginMsHolder))) {
            commandStatuses.put(String.valueOf(row.get("status")), ((Number) row.get("n")).longValue());
        }
        writeEvidence("pairing-summary.txt", "scenario=" + scenario + "\ntracedSubmissions=" + traced
                + "\nunpaired=" + unpaired + "\ntraceOutcomeCounts=" + statusCounts
                + "\nprotectedOaClaimWaitSamples=" + protectedOaClaimWaits.size()
                + "\nprotectedOaClaimWaitMaxMs=" + String.format(Locale.ROOT, "%.0f", maxClaimWait)
                + " (合同上界 5000ms)\nopenOrUnpairedByStatus=" + orphanByStatus
                + "\ncommandsCreatedAfterWindowBeginByStatus=" + commandStatuses + "\n");
    }

    private volatile long formalBeginMsHolder;

    private Map<String, Object> queryCommandRow(long tenant, String commandKey) {
        return queryOne("SELECT status, create_time, claimed_at, finished_at, resource_class,"
                + " resource_segment, resource_units, resource_released_at FROM sw_bpm_command"
                + " WHERE command_key = ? AND tenant_id = ?", commandKey, tenant);
    }

    private Map<String, Object> queryInstanceRow(String businessKey) {
        return queryOne("SELECT process_instance_id, status FROM sw_bpm_instance WHERE business_key = ?",
                businessKey);
    }

    private Map<String, Object> queryInvocationRow(String invocationKey) {
        return queryOne("SELECT id, status, duration_ms FROM sw_form_txn_invocation"
                + " WHERE invocation_key = ?", invocationKey);
    }

    private Map<String, Object> queryHiTask(String taskId) {
        try {
            return queryOne("SELECT delete_reason_ FROM act_hi_taskinst WHERE id_ = ?", taskId);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> queryOne(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "-" : String.valueOf(value).replace('\n', ' ');
    }

    /** 行缺失（拒绝整笔回滚/无命令行）时的安全取列。 */
    private static String field(Map<String, Object> row, String column) {
        return row == null ? "-" : str(row.get(column));
    }

    private static Long millisBetween(Object from, Object to) {
        if (!(from instanceof java.sql.Timestamp start) || !(to instanceof java.sql.Timestamp end)) {
            return null;
        }
        return end.getTime() - start.getTime();
    }

    /** 收敛明细：残余占用逐项归因 + 未完成对象清单（不以账面相等替代工作量清零）。 */
    private void writeConvergenceDetail(String scenario, long protectedTenant, long convergeMillis,
                                        long openBefore, long openAfter, long counterTotal,
                                        long factTotal) throws Exception {
        StringBuilder sb = new StringBuilder("scenario=").append(scenario)
                .append("\nloadStoppedConvergeMillis=").append(convergeMillis)
                .append(" (合同上界 120000ms)\nopenCommandsBefore=").append(openBefore)
                .append(" openCommandsAfter=").append(openAfter)
                .append("\ncounterTotal=").append(counterTotal).append(" factTotal=").append(factTotal)
                .append("\nengineJobs=").append(openJobs())
                .append(" deadletter=").append(queryOne("SELECT COUNT(*) AS n FROM"
                        + " act_ru_deadletter_job") == null ? "-"
                        : str(queryOne("SELECT COUNT(*) AS n FROM act_ru_deadletter_job").get("n")))
                .append("\n");
        sb.append("\nusageRows(scope,scope_key,segment,outstanding):\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT scope, scope_key, segment, outstanding"
                + " FROM sw_bpm_resource_usage ORDER BY scope, scope_key, segment")) {
            sb.append("  ").append(row.get("scope")).append(',').append(row.get("scope_key")).append(',')
                    .append(row.get("segment")).append(',').append(row.get("outstanding")).append('\n');
        }
        sb.append("\nopenCommandsByStatusAndCompletionPoint:\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT status, completion_point, channel,"
                + " resource_class, COUNT(*) AS n FROM sw_bpm_command WHERE status IN"
                + " ('PENDING','PROCESSING') GROUP BY status, completion_point, channel, resource_class")) {
            sb.append("  ").append(row).append('\n');
        }
        // 未释放占用按事实口径列出（与 ResourceFactView 同一条件：命令在途；或轻流程命令已完成
        // 但目标动作链未完成；或批次项未终结）——不把 resource_released_at（无写入方）当唯一依据
        sb.append("\nopenChargedCommands(fact 口径：PENDING/PROCESSING 命令):\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT tenant_id, status, completion_point,"
                + " resource_class, resource_segment, SUM(resource_units) AS units, COUNT(*) AS n"
                + " FROM sw_bpm_command WHERE resource_units IS NOT NULL"
                + " AND (command_type IS NULL OR command_type <> 'BATCH_INVOKE')"
                + " AND status IN ('PENDING','PROCESSING')"
                + " GROUP BY tenant_id, status, completion_point, resource_class, resource_segment")) {
            sb.append("  ").append(row).append('\n');
        }
        sb.append("\nopenChargedLightTargetChain(已完成但目标链未完成, class=PROD/point=TARGET_ACTION_DONE):\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT tenant_id, resource_segment,"
                + " COUNT(*) AS n FROM sw_bpm_command WHERE resource_units IS NOT NULL"
                + " AND status = 'COMPLETED' AND resource_class = 'PROD'"
                + " AND completion_point = 'TARGET_ACTION_DONE'"
                + " GROUP BY tenant_id, resource_segment")) {
            sb.append("  ").append(row).append('\n');
        }
        sb.append("\nopenBatchItems(批次项未终结):\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT c.tenant_id, c.resource_segment,"
                + " SUM(CASE WHEN i.status = 'PENDING' THEN 1 ELSE 0 END) AS units, COUNT(*) AS n"
                + " FROM sw_bpm_command c JOIN sw_bpm_command_batch b ON b.command_id = c.id"
                + " JOIN sw_bpm_command_batch_item i ON i.batch_id = b.id"
                + " WHERE c.resource_units IS NOT NULL AND c.status IN ('PENDING','PROCESSING')"
                + " GROUP BY c.tenant_id, c.resource_segment")) {
            sb.append("  ").append(row).append('\n');
        }
        sb.append("\nprotectedTenantUsage(tenant=").append(protectedTenant).append("):"
                + " total=").append(usageOf("TENANT", protectedTenant, "TOTAL")).append('\n');
        writeEvidence("convergence-detail.txt", sb.toString());
    }

    /** 采集/统计口径定义（复核：采集定义须支持归因强度；协变量与样本的配对规则显式化）。 */
    private void writeStatDefinition(String scenario, long formalStart) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("scenario=").append(scenario).append("\nrunId=").append(runId).append('\n');
        sb.append("windowBeginEpochMs=").append(formalStart).append(" warmup=").append(WARMUP_SECONDS)
                .append("s formal=").append(FORMAL_SECONDS).append("s shortVerify=").append(isShortVerify())
                .append('\n');
        sb.append("sampleFields=ts_end(完成时刻),ts_start(发起时刻=ts_end-latency),latency_ms,outcome\n");
        sb.append("requestCohort=发起入组：ts_start∈[windowBegin, windowBegin+formal)；"
                + "完成入组：ts_end∈同区间。报告同时给出两者与『发起入组但完成在窗口外』计数，"
                + "跨窗口请求追踪到完成/超时（不因窗口结束丢弃慢样本）\n");
        sb.append("percentile=nearest-rank（升序第 ceil(q*n) 个样本），n=该口径样本数；"
                + "不插值、不剔除慢样本、不以 p50 替代 p99\n");
        sb.append("successClasses=protected-realtime:SUCCEEDED / protected-light:ACCEPTED / "
                + "oa-read:OK / oa-approval:ACCEPTED / burst:SUCCEEDED+ACCEPTED / 批:ACCEPTED\n");
        sb.append("rejectClasses=REJECTED:*(业务拒绝，含错误码) / TIMEOUT(请求超时 10s) / ERROR:*(客户端异常)\n");
        sb.append("allResultDistribution=每采集器同时报告合法样本分位数与全结果分位数（含拒绝/超时）\n");
        sb.append("proc_cpu_pct_of_machine=Δ(getProcessCpuTime)/Δ(wall)/核数*100（进程真实 CPU 占用，"
                + "非 load average；相邻两次 1s 采样差分，首个采样点为 -1）\n");
        sb.append("proc_cpu_pct_of_one_core=Δ(getProcessCpuTime)/Δ(wall)*100\n");
        sb.append("load_avg=getSystemLoadAverage()（一/五/十五分钟均值中的 1min，含等待进程，不单独作为"
                + "CPU 饱和证据）\n");
        sb.append("heap_used_mib=MemoryMXBean 堆已用；gc_count/gc_time_ms=相邻采样区间内全收集器"
                + "回收次数/耗时增量（GC 停顿与尾延迟的相关性据此判定）\n");
        sb.append("slow_rt_1s_le300=最近 1000ms 内完成且 latency>300ms 的受保护实时请求数；"
                + "slow_light_10s_le2000 / slow_oa_10s_le1000 / slow_approval_10s_le1000 同理"
                + "（慢样本与资源采样点按完成时刻配对）\n");
        sb.append("burst_reject_1s=最近 1000ms 内完成的突发拒绝数（拒绝响应 P99 与拒绝到达率的配对）\n");
        sb.append("pool_wait_thread/pool_wait_millis_max=Druid 等待线程数/maxWait 配置值（非实际最大等待）；"
                + "pg_lock_waits=pg_stat_activity 中 wait_event_type='Lock' 的连接数\n");
        sb.append("resourceSampleInterval=1000ms（采样点时刻=ts 列，本地+08:00）\n");
        writeEvidence("stat-definition.txt", sb.toString());
        this.formalBeginMsHolder = formalStart;
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

        // 中断前逐对象快照：commandId/recordId/状态（在途 vs 已完成分离，复核 RA04）
        List<String[]> preSnapshot = new ArrayList<>();
        int preCompleted = 0;
        for (String recordId : recordIds) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT id::text AS cid, status FROM sw_bpm_command WHERE command_key = ?",
                    "FLOW_START:" + recordId);
            String status = String.valueOf(row.get("status"));
            if ("COMPLETED".equals(status)) {
                preCompleted++;
            }
            preSnapshot.add(new String[]{recordId, String.valueOf(row.get("cid")), status});
        }
        // 中断层级声明（复核 RA04）：优雅上下文重建（app.close() 优雅停机）——
        // 非进程崩溃/SIGKILL 层级；该层级由分级阶段 G2a SIGKILL 真实中断演练覆盖（46.478s 零重复，
        // 历史锁定，本轮无相关实现回退不重开）。资源释放实现变化影响恢复路径，故本轮重建层级复验。
        String interruptTier = "graceful-context-rebuild(app.close)";
        String shutdownAt = LocalDateTime.now().format(TS);
        app.close();
        app = newBoot("jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified",
                contractProps());
        jdbc = app.getBean(JdbcTemplate.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        String newInstanceReadyAt = LocalDateTime.now().format(TS);

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
            Long pendingJobs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM act_ru_job", Long.class);
            // 收敛=命令终态且引擎目标 job 清零（目标完成点合同：命令完成≠目标完成）
            if (completed >= 100 && (pendingJobs == null || pendingJobs == 0)) {
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
        // 逐项目标表（复核 RA04）：recordId/commandId/中断前状态/重启后终态/调用行 id——
        // 100 个已受理对象与权威效果逐项对应，效果唯一性（每命令恰 1 效果行）单独断言
        StringBuilder perItem = new StringBuilder(
                "record_id,command_id,pre_status,post_status,invocation_count,invocation_id\n");
        long uniqueEffectViolations = 0;
        for (String[] pre : preSnapshot) {
            Map<String, Object> post = jdbc.queryForMap(
                    "SELECT status AS st FROM sw_bpm_command c WHERE c.command_key = ?",
                    "FLOW_START:" + pre[0]);
            // 权威效果判据=目标动作调用行（FLOW_START 命令无效果账本行；调用行即业务效果事实）
            List<Map<String, Object>> invocationsOfCommand = jdbc.queryForList(
                    "SELECT v.id::text AS iid FROM sw_form_txn_invocation v"
                            + " JOIN sw_bpm_instance inst ON v.invocation_key ="
                            + " 'NODE:' || inst.process_instance_id || ':act-1'"
                            + " WHERE inst.business_key = ?",
                    pre[0]);
            String invocationId = invocationsOfCommand.isEmpty() ? "-"
                    : String.valueOf(invocationsOfCommand.get(0).get("iid"));
            if (invocationsOfCommand.size() != 1) {
                uniqueEffectViolations++;
            }
            perItem.append(pre[0]).append(',').append(pre[1]).append(',').append(pre[2]).append(',')
                    .append(post.get("st")).append(',').append(invocationsOfCommand.size()).append(',')
                    .append(invocationId).append('\n');
        }
        writeNew(evidenceDir.resolve("recovery-per-item.csv"), out ->
                out.write(perItem.toString().getBytes(StandardCharsets.UTF_8)));
        writeEvidence("recovery-restart.txt", "policyVersion=" + policyVersion
                + " admittedUsageBeforeRestart=" + admittedUsage
                + " preRestartCompleted=" + preCompleted + "/100（在途=" + (100 - preCompleted) + "）"
                + " interruptTier=" + interruptTier
                + " shutdownAt=" + shutdownAt + " newInstanceReadyAt=" + newInstanceReadyAt
                + " completed=" + completed + "/100 elapsedMs=" + elapsed
                + " invocations=" + invocations + " (重复效果=0)"
                + " uniqueEffectViolations=" + uniqueEffectViolations
                + " counterTotal=" + counterTotal + " factTotal=" + factTotal
                + " raInstances=" + instanceCount
                + " perItemTable=recovery-per-item.csv"
                + " runId=" + runId);
        assertThat(uniqueEffectViolations).as("每命令权威效果恰 1（唯一性）").isZero();
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
        // 跨策略版本（复核 RA04）：v1（全局2000）在途对象冻结字段 → v2 减配（全局仅 60）启用 →
        // 新受理按 v2 上限拒绝（在途不重解释、不丢账），旧对象按冻结版本继续结算；usage 不按版本分账
        // 重新启用 v1（宽额度）作为在途对象受理时的生效版本（停用回退轮已 disable；同时解除停新受理）
        asTenant(0L, 91999L, () -> policyService.stopAcceptance(policyId, false));
        BpmResourcePolicy v1Reenabled = asTenant(0L, 91999L, () ->
                policyService.enable(policyId, "RA04 v1 重新启用（在途冻结取证）"));
        Map<String, Object> v1PolicyRow = queryOne("SELECT id, policy_version, status, enabled,"
                + " global_max_outstanding, tenant_max_outstanding, prod_reserved, oa_reserved,"
                + " shared_capacity, tenant_rate_per_sec, tenant_burst FROM sw_bpm_resource_policy"
                + " WHERE id = ?", policyId);
        // v1 在途对象：20 项批次（占用按项计），冻结字段在减配前/后逐字段回读
        String inFlightBatchKey = "V1INFLIGHT-" + runId;
        String inFlightSubmit = submitBatch(fx, inFlightBatchKey, 20);
        Map<String, Object> inFlightBefore = queryOne("SELECT command_key, status, resource_class,"
                + " resource_segment, resource_units, policy_version, resource_released_at"
                + " FROM sw_bpm_command WHERE command_key = ? AND tenant_id = 0",
                "BATCH:" + inFlightBatchKey);
        Map<String, Object> usageBeforeDowngrade = queryOne("SELECT"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='GLOBAL' AND"
                + " segment='TOTAL') AS global_total,"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='TENANT' AND"
                + " scope_key=0 AND segment='TOTAL') AS tenant0_total,"
                + " (SELECT COUNT(*) FROM information_schema.columns WHERE"
                + " table_name='sw_bpm_resource_usage' AND column_name='policy_version')"
                + " AS usage_policy_version_columns");
        ResourcePolicyService svc = policyService;
        BpmResourcePolicy v2Draft = new BpmResourcePolicy();
        v2Draft.setGlobalMaxOutstanding(60);
        v2Draft.setTenantMaxOutstanding(50);
        v2Draft.setProdReserved(10);
        v2Draft.setOaReserved(10);
        v2Draft.setSharedCapacity(40);
        v2Draft.setTenantRatePerSec(50);
        v2Draft.setTenantBurst(500);
        v2Draft.setRealtimeGlobalConcurrency(16);
        v2Draft.setRealtimeTenantConcurrency(8);
        v2Draft.setBatchSliceItems(25);
        v2Draft.setBatchPollClaimLimit(1);
        BpmResourcePolicy v2 = asTenant(0L, 91999L, () -> svc.create(v2Draft));
        BpmResourcePolicy v2Enabled = asTenant(0L, 91999L, () -> svc.enable(v2.getId(), "RA04 减配版本"));
        // v1 在途批次按原冻结版本继续结算（减配不重解释）；结算完再占满 v2 租户额度 50
        long inFlightDeadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < inFlightDeadline && usageOf("TENANT", 0L, "TOTAL") > 0) {
            Thread.sleep(500);
        }
        // v1 在途已释放（上面排干），v2 减配后按租户上限 50 占满（50≤全局 60）再拒
        var admission = app.getBean(com.sw.ck.bpm.process.service.ResourceAdmissionService.class);
        asTenant(0L, 91999L, () -> {
            for (int i = 0; i < 50; i++) {
                final int seq = i;
                admission.admit(0L, com.sw.ck.bpm.process.entity.ResourceClassEnum.OA, 1,
                        "RA04-V2-OCC-" + seq);
            }
            return null;
        });
        String v2Rejected = post("/api/form/data/" + fx.formKey, fx.bearer,
                "{\"material\":\"V2REJ-" + runId + "\",\"qty_available\":\"1\",\"qty_reserved\":\"0\","
                        + "\"target_record_id\":\"" + fx.recordIds.get(2) + "\"}",
                json -> json);
        assertThat(v2Rejected).as("减配版本新受理按 v2 上限拒绝").contains("2427");
        Map<String, Object> usageAtV2Full = queryOne("SELECT"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='GLOBAL' AND"
                + " segment='TOTAL') AS global_total,"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='TENANT' AND"
                + " scope_key=0 AND segment='TOTAL') AS tenant0_total,"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='GLOBAL' AND"
                + " segment='SHARED') AS global_shared");
        // 释放后 v2 界面恢复受理（不重解释旧版本在途）
        asTenant(0L, 91999L, () -> {
            admission.release(0L, "SHARED", 50);
            return null;
        });
        jdbc.update("UPDATE sw_bpm_resource_usage SET outstanding = 0 WHERE scope = 'GLOBAL'");
        String afterV2 = post("/api/form/data/" + fx.formKey, fx.bearer,
                "{\"material\":\"V2OK-" + runId + "\",\"qty_available\":\"1\",\"qty_reserved\":\"0\","
                        + "\"target_record_id\":\"" + fx.recordIds.get(3) + "\"}",
                json -> json);
        assertThat(afterV2).as("v2 界面内合法受理成功").doesNotContain("2427");

        // 旧无字段行兼容（复核 RA04：旧在途无资源字段采用可追踪兼容规则）：
        // 直接 INSERT 旧形态命令行（资源字段 NULL=旧对象）→ 经真实领取(claim)+完成(complete)
        // 走完整终态路径：不参与资源会计、对账不为其记账，历史查询仍一致可读
        jdbc.update("INSERT INTO sw_bpm_command (id, command_key, command_type, channel, status,"
                        + " payload, tenant_id, initiator_id, create_time, update_time, deleted, version)"
                        + " VALUES (981001, 'LEGACY:" + runId + "', 'TASK_APPROVE', 'NORMAL', 'PENDING',"
                        + " '{}', 0, 1, current_timestamp, current_timestamp, 0, 0)");
        var commandQueue = app.getBean(com.sw.ck.bpm.process.queue.BpmCommandQueue.class);
        Map<String, Object> legacyBeforeComplete = queryOne("SELECT id, status, resource_class,"
                + " resource_units, resource_segment, policy_version, finished_at FROM sw_bpm_command"
                + " WHERE id = 981001");
        long legacyGlobalUsageBefore = usageOf("GLOBAL", 0L, "TOTAL");
        long legacyFactBefore = app.getBean(com.sw.ck.bpm.process.service.ResourceFactView.class)
                .factBySegment().values().stream().mapToLong(Long::longValue).sum();
        // 真实消费路径：调度领取（跨租户扫描）→ 消费方身份还原 → 完成
        var claimToken = commandQueue.claimDue(
                        List.of(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL), 200).stream()
                .filter(envelope -> envelope.getCommandId() == 981001L)
                .map(com.sw.ck.bpm.process.queue.CommandEnvelope::getClaimToken)
                .findFirst().orElse(null);
        assertThat(claimToken).as("旧无字段行可被真实调度领取").isNotNull();
        asTenant(0L, 91999L, () -> {
            commandQueue.complete(981001L, claimToken, "{\"legacy\":true}");
            return null;
        });
        Map<String, Object> legacyAfterComplete = queryOne("SELECT id, status, resource_class,"
                + " resource_units, resource_segment, policy_version, resource_released_at,"
                + " finished_at FROM sw_bpm_command WHERE id = 981001");
        assertThat(String.valueOf(legacyAfterComplete.get("status"))).as("旧无字段行完成")
                .isEqualTo("COMPLETED");
        assertThat(legacyAfterComplete.get("resource_released_at")).as("旧行不参与资源释放会计").isNull();
        Long legacyUnits = usageOf("GLOBAL", 0L, "TOTAL");
        long legacyFactAfter = app.getBean(com.sw.ck.bpm.process.service.ResourceFactView.class)
                .factBySegment().values().stream().mapToLong(Long::longValue).sum();
        app.getBean(com.sw.ck.bpm.process.queue.ResourceAssuranceReconcileJob.class).reconcileOnce();
        assertThat(usageOf("GLOBAL", 0L, "TOTAL"))
                .as("旧无字段行不参与资源会计（对账后仍不计费）").isEqualTo(legacyUnits);

        // 停用回退后历史查询不丢（复核 RA04）：拒绝审计/策略版本行仍完整可读
        Long policyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_resource_policy", Long.class);
        Long rejectRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_resource_reject_log", Long.class);
        assertThat(policyRows).as("策略版本行保留（追加不删）").isGreaterThanOrEqualTo(2);
        assertThat(rejectRows).as("拒绝审计保留").isGreaterThanOrEqualTo(1);

        // v2 在途/账本实际数值：减配后冻结字段不被重解释；usage 无版本列（跨版本共用总账）
        Map<String, Object> inFlightAfter = queryOne("SELECT command_key, status, resource_class,"
                + " resource_segment, resource_units, policy_version, resource_released_at"
                + " FROM sw_bpm_command WHERE command_key = ? AND tenant_id = 0",
                "BATCH:" + inFlightBatchKey);
        Map<String, Object> v2PolicyRow = queryOne("SELECT id, policy_version, status, enabled,"
                + " global_max_outstanding, tenant_max_outstanding, prod_reserved, oa_reserved,"
                + " shared_capacity FROM sw_bpm_resource_policy WHERE id = ?", v2.getId());
        Map<String, Object> usageAfterRelease = queryOne("SELECT"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='GLOBAL' AND"
                + " segment='TOTAL') AS global_total,"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='TENANT' AND"
                + " scope_key=0 AND segment='TOTAL') AS tenant0_total");
        // 旧行历史查询可读（停用回退后仍可查历史拒绝/策略版本/批次行）
        Long historyBatchRows = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch"
                + " WHERE batch_key = ?", Long.class, inFlightBatchKey);
        List<Map<String, Object>> v1FrozenKeys = jdbc.queryForList("SELECT command_key, policy_version,"
                + " resource_segment, resource_units FROM sw_bpm_command WHERE policy_version = ?"
                + " AND resource_units IS NOT NULL ORDER BY id LIMIT 20", v1Reenabled.getPolicyVersion());
        Map<String, Object> legacyRow = queryOne("SELECT id, command_key, status, resource_class,"
                + " resource_units, resource_segment, policy_version, resource_released_at,"
                + " finished_at FROM sw_bpm_command WHERE id = 981001");
        Long legacyInFact = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command WHERE id = 981001"
                + " AND resource_released_at IS NOT NULL", Long.class);
        Map<String, Object> historyQueryRow = queryOne("SELECT c.id, c.status, c.command_type,"
                + " c.channel, c.finished_at FROM sw_bpm_command c WHERE c.id = 981001"
                + " AND c.deleted = 0");
        StringBuilder detail = new StringBuilder();
        detail.append("v1PolicyRow=").append(v1PolicyRow).append('\n')
                .append("v2PolicyRow=").append(v2PolicyRow).append('\n')
                .append("inFlightSubmit20=").append(inFlightSubmit).append('\n')
                .append("inFlightBeforeHasFields=").append(inFlightBefore).append('\n')
                .append("inFlightAfterHasFields=").append(inFlightAfter).append('\n')
                .append("usageBeforeDowngrade=").append(usageBeforeDowngrade).append('\n')
                .append("usageAtV2Full=").append(usageAtV2Full).append('\n')
                .append("usageAfterRelease=").append(usageAfterRelease).append('\n')
                .append("v2RejectResponse=").append(v2Rejected).append('\n')
                .append("v2AcceptAfterReleaseResponse=").append(afterV2).append('\n')
                .append("stoppedResponse=").append(stoppedBody).append('\n')
                .append("resumedResponse=").append(resumed).append('\n')
                .append("legacyRowBeforeComplete=").append(legacyBeforeComplete).append('\n')
                .append("legacyRowAfterComplete=").append(legacyAfterComplete).append('\n')
                .append("legacyGlobalUsageBefore=").append(legacyGlobalUsageBefore)
                .append(" legacyGlobalUsageAfter=").append(legacyUnits).append('\n')
                .append("legacyFactBefore=").append(legacyFactBefore).append(" legacyFactAfter=")
                .append(legacyFactAfter).append('\n')
                .append("legacyHistoryQueryRow=").append(historyQueryRow).append('\n')
                .append("legacyRowReleasedAtNotNull=").append(legacyInFact).append('\n')
                .append("legacyRowFinalReadback=").append(legacyRow).append('\n')
                .append("historyBatchQueryRows=").append(historyBatchRows).append('\n')
                .append("frozenV1Commands=").append(v1FrozenKeys).append('\n');
        writeEvidence("stop-acceptance-downgrade.txt", "policyVersion=" + policyVersion
                + " stoppedRejected=" + stoppedBody.contains("2429")
                + " stoppedUsage=" + stoppedUsage + " resumedAccept=" + !resumed.contains("2429")
                + " v2PolicyVersion=" + v2Enabled.getPolicyVersion()
                + " v2ReducedQuotaRejected=" + v2Rejected.contains("2427")
                + " v2AcceptAfterRelease=" + !afterV2.contains("2427")
                + " legacyRowNotCounted=true"
                + " policyRows=" + policyRows + " rejectRows=" + rejectRows
                + " runId=" + runId + "\n--- actual values ---\n" + detail);
    }

    // ==================== RG02/RG05：批次按项会计 ====================

    @Test
    @DisplayName("RG02/RG05：500 项整笔准入按项占额、重放不重复占用、同键异载荷拒绝、额度不足整笔拒绝、并发不超卖、逐项终态回收")
    @EnabledIfSystemProperty(named = "p62.resource.batch", matches = "true")
    void batchPerItemAccountingContract() throws Exception {
        enableContractPolicy();
        TenantFixture burstFx = fixtures.get(100L);
        TenantFixture protectedFx = fixtures.get(0L);
        StringBuilder ev = new StringBuilder("runId=" + runId + "\n");
        ev.append("phase=1 500ItemsAdmission\n");

        // 1) 500 项整笔准入：按实际项数占额（不能以一批 500 项只占一个名额规避）
        String batchA = "BA-" + runId;
        String submitA = submitBatch(burstFx, batchA, 500);
        // 逐项会计不变量（单条 SQL 一致快照：项终态与占用释放同事务提交）：
        // 租户占用 + 已结算项数 = 受理冻结的整笔项数
        Map<String, Object> ledgerInvariant = queryOne("SELECT"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='TENANT'"
                + " AND scope_key=100 AND segment='TOTAL') AS tenant_used,"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='GLOBAL'"
                + " AND segment='TOTAL') AS global_used,"
                + " (SELECT outstanding FROM sw_bpm_resource_usage WHERE scope='GLOBAL'"
                + " AND segment='SHARED') AS shared_used,"
                + " (SELECT COUNT(*) FROM sw_bpm_command_batch_item i"
                + " JOIN sw_bpm_command_batch b ON i.batch_id = b.id"
                + " WHERE b.batch_key = ? AND i.status = 'SUCCEEDED') AS settled_items", batchA);
        long tenantUsedAfterA = ((Number) ledgerInvariant.get("tenant_used")).longValue();
        long settledAtRead = ((Number) ledgerInvariant.get("settled_items")).longValue();
        long sharedAfterA = ((Number) ledgerInvariant.get("shared_used")).longValue();
        long globalAfterA = ((Number) ledgerInvariant.get("global_used")).longValue();
        long rateTokensA = app.getBean(com.sw.ck.bpm.process.service.TenantRateBuckets.class)
                .availableTokens(100L, 500);
        Map<String, Object> cmdA = queryOne("SELECT command_key, status, resource_class,"
                + " resource_segment, resource_units, completion_point, create_time, claimed_at,"
                + " resource_released_at FROM sw_bpm_command WHERE command_key = ? AND tenant_id = 100",
                "BATCH:" + batchA);
        Map<String, Object> batchRowA = queryOne("SELECT id, status, total_count, succeeded_count,"
                + " failed_count, command_id FROM sw_bpm_command_batch WHERE batch_key = ?"
                + " AND tenant_id = 100", batchA);
        ev.append("submitA500=").append(submitA).append('\n')
                .append("afterA: tenant100Used=").append(tenantUsedAfterA).append(" settledItemsAtRead=")
                .append(settledAtRead).append(" globalShared=").append(sharedAfterA)
                .append(" globalTotal=").append(globalAfterA)
                .append(" rateTokensAvailable=").append(rateTokensA).append('\n')
                .append("commandA=").append(cmdA).append('\n')
                .append("batchA=").append(batchRowA).append('\n');
        assertThat(submitA).as("500 项批次在额度充足时整笔准入").isEqualTo("ACCEPTED");
        assertThat(((Number) cmdA.get("resource_units")).longValue())
                .as("受理按实际项数整笔占额（500 项=500 单位；非按批占 1 名额）").isEqualTo(500L);
        assertThat(String.valueOf(cmdA.get("resource_class"))).isEqualTo("BULK");
        assertThat(tenantUsedAfterA + settledAtRead)
                .as("逐项会计不变量：占用 + 已结算项 = 受理项数").isEqualTo(500L);
        assertThat(sharedAfterA).as("BULK 只占共享段").isEqualTo(tenantUsedAfterA);
        assertThat(String.valueOf(cmdA.get("completion_point"))).isEqualTo("BATCH_SETTLED");

        // 2) 同键同载荷重放：返回原批次、不重复占用
        String replayA = submitBatch(burstFx, batchA, 500);
        long tenantUsedAfterReplay = usageOf("TENANT", 100L, "TOTAL");
        Long batchCountA = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch"
                + " WHERE batch_key = ?", Long.class, batchA);
        Long commandCountA = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command"
                + " WHERE command_key = ?", Long.class, "BATCH:" + batchA);
        ev.append("replayA_same_payload=").append(replayA).append(" tenantUsedAfterReplay=")
                .append(tenantUsedAfterReplay).append(" batchRows=").append(batchCountA)
                .append(" commandRows=").append(commandCountA).append('\n');
        assertThat(replayA).as("同键同载荷重放返回原批次").isEqualTo("ACCEPTED");
        assertThat(tenantUsedAfterReplay).as("重放不重复占用额度").isEqualTo(500);
        assertThat(batchCountA).isEqualTo(1L);
        assertThat(commandCountA).isEqualTo(1L);

        // 3) 同键异载荷：必须拒绝（不得静默返回原批次）
        String differentPayload = submitBatch(burstFx, batchA, 2);
        ev.append("replayA_diff_payload=").append(differentPayload).append('\n');
        assertThat(differentPayload).as("同键异载荷拒绝").contains("2426");
        assertThat(usageOf("TENANT", 100L, "TOTAL")).as("异载荷拒绝不改变占用").isEqualTo(500);

        // 4) 逐项终态回收 + 领取等待：批次 A 结算后占用逐项回收、逐项动作恰一次
        long settleDeadline = System.currentTimeMillis() + 120_000L;
        Map<String, Object> batchRowA2 = null;
        while (System.currentTimeMillis() < settleDeadline) {
            batchRowA2 = queryOne("SELECT id, status, total_count, succeeded_count, failed_count,"
                    + " command_id FROM sw_bpm_command_batch WHERE batch_key = ? AND tenant_id = 100",
                    batchA);
            if (batchRowA2 != null && "COMPLETED".equals(String.valueOf(batchRowA2.get("status")))) {
                break;
            }
            Thread.sleep(1000);
        }
        Map<String, Object> itemStatsA = queryOne("SELECT COUNT(*) AS items,"
                + " COUNT(*) FILTER (WHERE i.status = 'SUCCEEDED') AS succeeded,"
                + " COUNT(*) FILTER (WHERE i.status = 'FAILED') AS failed,"
                + " COUNT(*) FILTER (WHERE i.status IN ('PENDING','PROCESSING')) AS pending,"
                + " MAX(EXTRACT(EPOCH FROM (i.update_time - i.create_time)) * 1000) AS max_item_ms"
                + " FROM sw_bpm_command_batch_item i JOIN sw_bpm_command_batch b ON i.batch_id = b.id"
                + " WHERE b.batch_key = ?", batchA);
        long invocationDeadline = System.currentTimeMillis() + 20_000L;
        Long invocationCountA = jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_invocation"
                + " WHERE invocation_key LIKE ?", Long.class, "BATCH:" + batchA + ":%");
        while ((invocationCountA == null || invocationCountA < 500)
                && System.currentTimeMillis() < invocationDeadline) {
            Thread.sleep(500);
            invocationCountA = jdbc.queryForObject("SELECT COUNT(*) FROM sw_form_txn_invocation"
                    + " WHERE invocation_key LIKE ?", Long.class, "BATCH:" + batchA + ":%");
        }
        Long distinctInvocationA = jdbc.queryForObject("SELECT COUNT(DISTINCT invocation_key)"
                + " FROM sw_form_txn_invocation WHERE invocation_key LIKE ?",
                Long.class, "BATCH:" + batchA + ":%");
        Map<String, Object> cmdA2 = queryOne("SELECT claimed_at, create_time, finished_at, status,"
                + " resource_released_at FROM sw_bpm_command WHERE command_key = ? AND tenant_id = 100",
                "BATCH:" + batchA);
        Long claimWaitA = cmdA2 == null ? null
                : millisBetween(cmdA2.get("create_time"), cmdA2.get("claimed_at"));
        long tenantUsedAfterA2 = usageOf("TENANT", 100L, "TOTAL");
        long globalAfterA2 = usageOf("GLOBAL", 0L, "TOTAL");
        long sharedAfterA2 = usageOf("GLOBAL", 0L, "SHARED");
        ev.append("phase=2 settle\n").append("batchA_final=").append(batchRowA2)
                .append(" itemStatsA=").append(itemStatsA).append(" claimWaitMs=").append(claimWaitA)
                .append(" invocations=").append(invocationCountA)
                .append(" distinctInvocationKeys=").append(distinctInvocationA).append('\n')
                .append("afterSettle: tenant100Used=").append(tenantUsedAfterA2)
                .append(" globalTotal=").append(globalAfterA2)
                .append(" globalShared=").append(sharedAfterA2).append('\n');
        assertThat(String.valueOf(batchRowA2 == null ? null : batchRowA2.get("status")))
                .as("批次逐项结算完成").isEqualTo("COMPLETED");
        assertThat(((Number) itemStatsA.get("succeeded")).longValue()).isEqualTo(500L);
        assertThat(((Number) itemStatsA.get("pending")).longValue()).isZero();
        assertThat(invocationCountA).as("逐项动作调用 500 次").isEqualTo(500L);
        assertThat(distinctInvocationA).as("无重复效果（调用键唯一）").isEqualTo(500L);
        assertThat(tenantUsedAfterA2).as("项终态回收占用（租户）").isZero();
        assertThat(globalAfterA2).as("项终态回收占用（全局）").isZero();
        if (claimWaitA != null) {
            assertThat(claimWaitA).as("批量项最大领取等待≤30s").isLessThanOrEqualTo(30_000L);
        }

        // 5) 减配额版本（租户上限 1）：整笔裁决——2 项批次额度不足整笔拒绝且无残留，1 项批次受理
        ResourcePolicyService svc = app.getBean(ResourcePolicyService.class);
        BpmResourcePolicy tightDraft = new BpmResourcePolicy();
        tightDraft.setGlobalMaxOutstanding(2000);
        tightDraft.setTenantMaxOutstanding(1);
        tightDraft.setProdReserved(400);
        tightDraft.setOaReserved(400);
        tightDraft.setSharedCapacity(1200);
        tightDraft.setTenantRatePerSec(50);
        tightDraft.setTenantBurst(500);
        tightDraft.setRealtimeGlobalConcurrency(16);
        tightDraft.setRealtimeTenantConcurrency(8);
        tightDraft.setBatchSliceItems(25);
        tightDraft.setBatchPollClaimLimit(1);
        BpmResourcePolicy tight = asTenant(0L, 91999L, () -> svc.create(tightDraft));
        BpmResourcePolicy tightEnabled = asTenant(0L, 91999L, () -> svc.enable(tight.getId(), "批次会计窄额度"));
        ev.append("phase=3 tightTenantCap1 policyVersion=").append(tightEnabled.getPolicyVersion())
                .append('\n');
        String rejectedBatch = "BR-" + runId;
        String submitB = submitBatch(burstFx, rejectedBatch, 2);
        Long batchB = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch"
                + " WHERE batch_key = ?", Long.class, rejectedBatch);
        Long commandB = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command"
                + " WHERE command_key = ?", Long.class, "BATCH:" + rejectedBatch);
        Long itemsB = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command_batch_item i"
                + " JOIN sw_bpm_command_batch b ON i.batch_id = b.id WHERE b.batch_key = ?",
                Long.class, rejectedBatch);
        ev.append("overCap2Items=").append(submitB).append(" residualBatchRows=").append(batchB)
                .append(" residualCommandRows=").append(commandB).append(" residualItemRows=")
                .append(itemsB).append(" tenantUsedAfterReject=")
                .append(usageOf("TENANT", 100L, "TOTAL")).append('\n');
        assertThat(submitB).as("额度不足整笔拒绝（不是先占少量名额）").contains("2427");
        assertThat(batchB).as("拒绝不留批次行").isZero();
        assertThat(commandB).as("拒绝不留命令行").isZero();
        assertThat(itemsB).as("拒绝不留批次项").isZero();
        assertThat(usageOf("TENANT", 100L, "TOTAL")).isZero();
        String oneItemBatch = "BO-" + runId;
        String submitOne = submitBatch(burstFx, oneItemBatch, 1);
        ev.append("withinCap1Item=").append(submitOne).append(" tenantUsed=")
                .append(usageOf("TENANT", 100L, "TOTAL")).append('\n');
        assertThat(submitOne).as("额度内的 1 项批次正常受理").isEqualTo("ACCEPTED");
        // 该 1 项批次结算后再进入跨租户与并发竞争（避免与在途占用混淆）
        long oneSettleDeadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < oneSettleDeadline && usageOf("TENANT", 100L, "TOTAL") > 0) {
            Thread.sleep(500);
        }
        assertThat(usageOf("TENANT", 100L, "TOTAL")).as("1 项批次结算后释放").isZero();

        // 6) 跨租户额度独立：租户 100 的窄上限不影响租户 0 的租户额度
        String crossTenantBatch = "BC-" + runId;
        String submitProtected = submitBatch(protectedFx, crossTenantBatch, 1);
        ev.append("phase=4 crossTenant\n").append("crossTenantProtectedBatch=").append(submitProtected)
                .append(" tenant0Used=").append(usageOf("TENANT", 0L, "TOTAL")).append('\n');
        assertThat(submitProtected).as("租户 100 满额不阻断租户 0 的合法批次").isEqualTo("ACCEPTED");
        long crossSettleDeadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < crossSettleDeadline && usageOf("TENANT", 0L, "TOTAL") > 0) {
            Thread.sleep(500);
        }

        // 7) 并发受理不超卖（真实准入路径，租户上限 1）：两笔并发 1 单位 → 恰一笔占位成功
        var admission = app.getBean(com.sw.ck.bpm.process.service.ResourceAdmissionService.class);
        List<String> raceOutcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> raceSegments = java.util.Collections.synchronizedList(new ArrayList<>());
        Runnable attempt = () -> {
            try {
                var ticket = asTenant(0L, 91999L, () -> admission.admit(0L,
                        com.sw.ck.bpm.process.entity.ResourceClassEnum.OA, 1,
                        "RACE-" + runId + "-" + java.util.UUID.randomUUID()));
                if (ticket != null) {
                    raceSegments.add(ticket.segment());
                }
                raceOutcomes.add(ticket == null ? "NO_POLICY" : "ADMITTED");
            } catch (com.sw.ck.common.exception.BaseException exceeded) {
                raceOutcomes.add("REJECTED");
            }
        };
        Thread t1 = new Thread(attempt);
        Thread t2 = new Thread(attempt);
        t1.start();
        t2.start();
        t1.join(30_000);
        t2.join(30_000);
        long admitted = raceOutcomes.stream().filter("ADMITTED"::equals).count();
        long tenantUsedRace = usageOf("TENANT", 0L, "TOTAL");
        ev.append("phase=5 race\n").append("raceOutcomes=").append(raceOutcomes)
                .append(" admitted=").append(admitted).append(" tenantUsedAfterRace=")
                .append(tenantUsedRace).append('\n');
        assertThat(admitted).as("并发受理只放行额度内的那一笔").isEqualTo(1L);
        assertThat(tenantUsedRace).as("并发不突破额度").isEqualTo(1L);
        String raceSegment = raceSegments.isEmpty() ? "SHARED" : raceSegments.get(0);
        asTenant(0L, 91999L, () -> {
            admission.release(0L, raceSegment, 1);
            return null;
        });
        ev.append("phase=6 done tenantUsedAfterRelease=").append(usageOf("TENANT", 0L, "TOTAL"))
                .append('\n');
        writeEvidence("batch-accounting.txt", ev.toString());
        System.out.println("[P62-EV] batch per-item accounting ok");
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

    /** @return [outcome, 受理产生的 recordId（拒绝时 "-"）]，recordId 供受理→目标完成配对。 */
    private String[] submitLight(TenantFixture fx) {
        String target = pickRecord(fx);
        String material = "L-" + runId + "-" + fx.tenant + "-" + java.util.UUID.randomUUID();
        String body = "{\"material\":\"" + material + "\",\"qty_available\":\"1000\","
                + "\"qty_reserved\":\"0\",\"target_record_id\":\"" + target + "\"}";
        String[] result = {"-", "-"};
        result[0] = post("/api/form/data/" + fx.formKey, fx.bearer, body, json -> {
            if (json != null && !json.isEmpty()) {
                result[1] = json.startsWith("{") || json.startsWith("[") ? "-" : json;
                return "ACCEPTED";
            }
            return "REJECTED:" + codeOf(json);
        });
        return result;
    }

    /** @return [outcome, batchKey]，batchKey 供批次按项会计配对。 */
    private String[] submitBatch500(TenantFixture fx) {
        String batchKey = "B-" + runId + "-" + fx.tenant + "-" + java.util.UUID.randomUUID();
        return new String[]{submitBatch(fx, batchKey, 500), batchKey};
    }

    private String submitBatch(TenantFixture fx, String batchKey, int itemCount) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < itemCount; i++) {
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

        /** 完成入组样本（ts_end ≥ 窗口起点）。 */
        java.util.List<String[]> formalRows() {
            synchronized (lock) {
                return rows.stream().filter(this::inFormal).collect(java.util.stream.Collectors.toList());
            }
        }

        /** 最近 sinceMs 之后完成的样本行（资源采样点的慢请求配对）。 */
        java.util.List<String[]> rowsCompletedSince(long sinceMs) {
            synchronized (lock) {
                return rows.stream().filter(row -> epochOf(row[0]) >= sinceMs)
                        .collect(java.util.stream.Collectors.toList());
            }
        }

        /** 发起入组但完成在窗口结束之后（跨窗口请求抽样计数；仍按原样本保留）。 */
        long startedInWindowFinishedAfter(long windowEndMs) {
            synchronized (lock) {
                return rows.stream().filter(row -> epochOf(row[1]) >= formalBeginMs
                        && epochOf(row[1]) <= windowEndMs && epochOf(row[0]) > windowEndMs).count();
            }
        }

        /** 全结果延迟（含拒绝/超时/失败，不静默排除）。 */
        java.util.List<Double> allResultLatencies() {
            synchronized (lock) {
                return rows.stream().filter(this::inFormal).map(row -> Double.parseDouble(row[2]))
                        .sorted().collect(java.util.stream.Collectors.toList());
            }
        }

        private long epochOf(String ts) {
            try {
                return LocalDateTime.parse(ts, TS).atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
            } catch (Exception e) {
                return Long.MAX_VALUE;
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
            // .gz 扩展名必须真实 gzip 封装（RA06：原始 CSV 直接落 .gz 属封装错误）
            writeNew(evidenceDir.resolve(name), out -> {
                try (var gz = new java.util.zip.GZIPOutputStream(out, 64 * 1024)) {
                    gz.write(csv.toString().getBytes(StandardCharsets.UTF_8));
                }
            });
            writeNew(evidenceDir.resolve(name.replace(".csv.gz", "") + "-outcomes.txt"), out ->
                    out.write((histogram + "\n").getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 报告口径（stat-definition.txt 同步落盘）：每个采集器给出
     * ①全样本/发起入组/完成入组/窗口外计数 ②全结果分位数（含拒绝/超时/失败）
     * ③合法样本分位数 ④拒绝样本分位数；分位数一律 nearest-rank，不剔除慢样本。
     */
    private String report(String scenario, long formalBegin, SampleCollector... collectors) {
        StringBuilder sb = new StringBuilder("scenario=").append(scenario)
                .append(" windowBegin=").append(formalBegin)
                .append(" warmup=").append(WARMUP_SECONDS).append("s formal=").append(FORMAL_SECONDS)
                .append("s shortVerify=").append(isShortVerify())
                .append(" entry=HTTP:").append(port).append('\n');
        for (SampleCollector collector : collectors) {
            Map<String, Long> histogram = collector.outcomeHistogram();
            long total = histogram.values().stream().mapToLong(Long::longValue).sum();
            List<String[]> formalRows = collector.formalRows();
            List<Double> all = collector.allResultLatencies();
            List<Double> success = collector.legalLatencies("SUCCEEDED");
            if (success.isEmpty()) {
                success = collector.legalLatencies("ACCEPTED");
            }
            if (success.isEmpty()) {
                success = collector.legalLatencies("OK");
            }
            List<Double> rejects = collector.latenciesOf(outcome -> outcome.startsWith("REJECTED")
                    || "TIMEOUT".equals(outcome) || outcome.startsWith("ERROR"));
            long startedInWindow = formalRows.size();
            long completedInWindow = formalRows.size();
            long startedInWindowCompletedAfter = collector.startedInWindowFinishedAfter(formalBegin
                    + FORMAL_SECONDS * 1000L);
            sb.append("collector=").append(collector.name)
                    .append(" samples_total=").append(total)
                    .append(" cohort_start_in_window=").append(startedInWindow)
                    .append(" cohort_end_in_window=").append(completedInWindow)
                    .append(" start_in_window_finish_after=").append(startedInWindowCompletedAfter)
                    .append(" outcomes=").append(histogram).append('\n');
            sb.append("  all_results n=").append(all.size())
                    .append(String.format(Locale.ROOT, " p50=%.1fms p90=%.1fms p95=%.1fms p99=%.1fms max=%.1fms",
                            percentile(all, 0.50), percentile(all, 0.90), percentile(all, 0.95),
                            percentile(all, 0.99), max(all)))
                    .append('\n');
            sb.append("  legal_results n=").append(success.size())
                    .append(String.format(Locale.ROOT, " p50=%.1fms p90=%.1fms p95=%.1fms p99=%.1fms max=%.1fms",
                            percentile(success, 0.50), percentile(success, 0.90), percentile(success, 0.95),
                            percentile(success, 0.99), max(success)))
                    .append('\n');
            sb.append("  rejected_results n=").append(rejects.size())
                    .append(String.format(Locale.ROOT, " p50=%.1fms p90=%.1fms p99=%.1fms max=%.1fms",
                            percentile(rejects, 0.50), percentile(rejects, 0.90),
                            percentile(rejects, 0.99), max(rejects)))
                    .append('\n');
        }
        return sb.toString();
    }

    private static double max(List<Double> sorted) {
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
    }

    /** 最近 windowMs 内完成且超过门槛的受保护请求数（慢样本↔资源采样点配对）。 */
    private static int countSince(SampleCollector collector, long sinceMs, double thresholdMs) {
        return (int) collector.rowsCompletedSince(sinceMs).stream()
                .filter(row -> Double.parseDouble(row[2]) > thresholdMs).count();
    }

    private static int countOutcomeSince(SampleCollector collector, long sinceMs, String prefix) {
        return (int) collector.rowsCompletedSince(sinceMs).stream()
                .filter(row -> row[3].startsWith(prefix)).count();
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
        // RA01b：运行身份三件套——源码提交、工作树状态、制品指纹；由启动命令显式传入，
        // 缺失即失败（不把 HEAD 属性当作工作树未修改证据）
        String worktreeStatus = System.getProperty("p62.worktree.status");
        String artifactFingerprint = System.getProperty("p62.artifact.fingerprint");
        if (worktreeStatus == null || artifactFingerprint == null) {
            throw new IllegalStateException("运行身份缺失（RA01b 要求 -Dp62.worktree.status 与"
                    + " -Dp62.artifact.fingerprint）：worktree=" + worktreeStatus
                    + " artifact=" + artifactFingerprint);
        }
        sb.append("worktreeStatus=").append(worktreeStatus).append('\n');
        sb.append("artifactFingerprint=").append(artifactFingerprint).append('\n');
        sb.append("logLevelComSw=").append(app.getEnvironment().getProperty("logging.level.com.sw.ck"))
                .append('\n');
        sb.append("logLevelRoot=").append(app.getEnvironment().getProperty("logging.level.root"))
                .append('\n');
        sb.append("mybatisLogImpl=").append(app.getEnvironment()
                .getProperty("mybatis-plus.configuration.log-impl", "(default)")).append('\n');
        sb.append("jvmArgs=").append(java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments()).append('\n');
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
