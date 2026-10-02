package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P62 分级执行 G1a/G1b/G2b：U08 固定预算测量（复核02 一级提示原子账本）。
 *
 * <p><b>真实 HTTP 入口</b>：应用进程（测试 JVM 内单应用进程）以真实 Tomcat 端口启动，
 * 负载经 {@code POST /api/form/action/{id}/invoke}（实时动作）与
 * {@code POST /api/form/data/{formKey}}（生产轻流程持久受理）下发；身份用
 * debug-auth（{@code Bearer test_<uid>}，dev profile + 回环来源），每租户独立
 * bearer，权限经正式 UserDetailsProvider 回查。</p>
 *
 * <p><b>样本完整性</b>：逐请求全量记录（tenant/object/时间戳/耗时/结果）到 gzip CSV，
 * 固定 runId + {@link StandardOpenOption#CREATE_NEW} 不可覆盖；统计由样本文件回读
 * 复算，不由内存计数替代。失败轮样本永不删除、永不覆盖。</p>
 *
 * <p><b>G1b</b>：轻流程轮按 {@code sw_form_txn_invocation.biz_record_id} 关联受理
 * （表单行 create_time）与目标动作提交（节点调用行 update_time），双侧均为 PG 时钟；
 * 未完成样本不丢弃，报告有效对数与未完成数。</p>
 *
 * <p>手动运行（资源重任务，串行运行）：</p>
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62BudgetMeasurementPgTest \
 *   -Dp62.budget.measurement=true -Dp62.runId=&lt;runId&gt; \
 *   -Dp62.evidence.dir=&lt;绝对路径&gt; -Dp62.build.commit=$(git rev-parse HEAD) \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * # 压力边界单独：追加 -Dtest=P62BudgetMeasurementPgTest#stressBoundaryObservation -Dp62.budget.stress=true
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@DisplayName("P62 复核02 G1/G2b 固定预算测量（真实HTTP入口，手动：-Dp62.budget.measurement=true）")
@EnabledIfSystemProperty(named = "p62.budget.measurement", matches = "true")
class P62BudgetMeasurementPgTest {

    private static final long SEED = 20260930L;
    private static final int CONCURRENCY_TOTAL = 16;
    private static final int CONCURRENCY_STRESS = 64;
    private static final int HOTSPOT_MOD_G1 = 10;
    // 合同默认 60/300 与 30/300；-Dp62.warmup.seconds/-Dp62.formal.seconds 仅用于
    // 正式测量前的短分布验证（产出样本标注 shortVerify=true，不作为 U08 判定样本）
    private static final int G1_WARMUP_SECONDS = Integer.getInteger("p62.warmup.seconds", 60);
    private static final int G1_FORMAL_SECONDS = Integer.getInteger("p62.formal.seconds", 300);
    private static final int STRESS_WARMUP_SECONDS = Integer.getInteger("p62.stress.warmup.seconds", 30);
    private static final int STRESS_FORMAL_SECONDS = Integer.getInteger("p62.stress.formal.seconds", 300);
    private static final int OBJECTS_PER_TENANT_G1 = 1000;
    private static final int OBJECTS_PER_TENANT_STRESS = 10000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String runId;
    private static Path evidenceDir;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(10);

    private static int port;
    private static ConfigurableApplicationContext appRef;
    private static final Map<Long, TenantFixture> fixtures = new HashMap<>();

    /** 每租户测量夹具：库存表单/动作/轻流程定义与对象记录清单。 */
    private static final class TenantFixture {
        Long tenant;
        long userId;
        String bearer;
        String formKey;
        String stockTable;
        String actionId;
        String lightProcessKey;
        List<String> recordIds = new ArrayList<>();
    }

    @BeforeAll
    void boot() throws Exception {
        runId = System.getProperty("p62.runId");
        String dir = System.getProperty("p62.evidence.dir");
        if (runId == null || runId.isBlank() || dir == null || dir.isBlank()) {
            throw new IllegalStateException(
                    "必须提供 -Dp62.runId 与 -Dp62.evidence.dir（固定 runId 不可覆盖采集目录）");
        }
        evidenceDir = Path.of(dir);
        Files.createDirectories(evidenceDir);

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
        // 连接池身份（复核03 后修正）：实际池为 Druid（dynamic-datasource 全局 druid 块），
        // 旧键 hikari.maximum-pool-size 无效——maxActive=5 导致 16 并发连接饥饿（10s 超时）。
        // 显式设 Druid 池并 boot 后断言实际值（env-frozen 记录真实池身份）
        props.put("spring.datasource.dynamic.druid.initial-size", "10");
        props.put("spring.datasource.dynamic.druid.min-idle", "10");
        props.put("spring.datasource.dynamic.druid.max-active", "64");
        props.put("spring.datasource.dynamic.druid.max-wait", "10000");
        // 复核02 G5a 能力开关：测量套件恢复段使用批次受理，需显式开启（默认关）
        props.put("sw.bpm.txn-batch.enabled", "true");
        // G1a 轻流程场景吞吐配置（冻结并写入 env-frozen）：命令调度与节点异步执行池
        props.put("sw.bpm.command.poll-interval-millis", "100");
        props.put("sw.bpm.command.p0-poll-interval-millis", "100");
        props.put("sw.bpm.command.batch-size", "50");
        props.put("flowable.async-executor-activate", "true");
        props.put("flowable.process.async.executor.core-pool-size", "8");
        props.put("flowable.process.async.executor.max-pool-size", "8");
        props.put("flowable.process.async.executor.async-job-lock-time-in-millis", "60000");
        props.put("sw.security.debug-auth.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
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
                    // debug-auth kickOut 走 Redis 缓存：隔离测量无 Redis，替换为无操作缓存（不注入权限、不影响回查）
                    context.addBeanFactoryPostProcessor(bf -> {
                        if (bf.containsBeanDefinition("loginUserCacheService")) {
                            ((org.springframework.beans.factory.support.DefaultListableBeanFactory) bf)
                                    .setAllowBeanDefinitionOverriding(true);
                            ((org.springframework.beans.factory.support.BeanDefinitionRegistry) bf)
                                    .registerBeanDefinition("loginUserCacheService",
                                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                                    NoopLoginUserCacheService.class));
                        }
                    });
                })
                .run();
        // 压测下 DEBUG SQL 日志引入磁盘 I/O 背景负载，污染时序样本（固定合同要求无其他背景负载）
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.WARN);
        appRef = app;
        jdbc = app.getBean(JdbcTemplate.class);
        long druidMaxActive = findDruidMaxActive();
        if (druidMaxActive < 48) {
            throw new IllegalStateException("Druid maxActive=" + druidMaxActive
                    + " < 48：连接池配置未生效（16并发+调度+异步节点将饥饿），禁止进入测量");
        }
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));

        seedTenant(0L, 91999L, "p62_budget_t0", OBJECTS_PER_TENANT_G1, 100000, "budget_reserve");
        seedTenant(100L, 92999L, "p62_budget_t100", OBJECTS_PER_TENANT_G1, 100000, "budget_reserve");
        // 冻结环境档案在种子后写出（含全部夹具身份）
        writeEnvFrozen();
        System.out.println("[P62-EV] budget boot ok pgPort=" + pg.getPort() + " httpPort=" + port
                + " heapMaxMiB=" + Runtime.getRuntime().maxMemory() / 1024 / 1024
                + " runId=" + runId);
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

    // ==================== G1a 场景一：实时动作（预算 P99≤300ms） ====================

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("G1a 实时动作：16并发（两租户各8）× 预热60s + 正式5min，真实HTTP入口")
    void realTimeActionBudget() throws Exception {
        runLoad(new LoadSpec("realtime-action", CONCURRENCY_TOTAL,
                G1_WARMUP_SECONDS, G1_FORMAL_SECONDS, HOTSPOT_MOD_G1, this::invokeRealtimeOnce));
        MeasurementReport report = reportFromSampleFile("realtime-action", 300.0, "SUCCEEDED");
        assertThatPass(report);
    }

    // ==================== G1a 场景二 + G1b：生产轻流程（受理 P99≤2s + 配对） ====================

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("G1a 轻流程：16并发 × 预热60s + 正式5min，入口至持久受理 P99≤2s（真实HTTP入口）")
    void lightProcessAcceptanceBudget() throws Exception {
        runLoad(new LoadSpec("light-process-acceptance", CONCURRENCY_TOTAL,
                G1_WARMUP_SECONDS, G1_FORMAL_SECONDS, HOTSPOT_MOD_G1, this::submitLightOnce));
        MeasurementReport report = reportFromSampleFile("light-process-acceptance", 2000.0, "ACCEPTED");
        assertThatPass(report);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("G1b 受理→目标提交配对：同轮按 biz_record_id 关联，有效对数/未完成数/分布（PG时钟双侧）")
    void lightProcessAcceptanceToTargetPairs() throws Exception {
        Path samples = evidenceDir.resolve("light-process-acceptance-samples.csv.gz");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(samples),
                "先运行 lightProcessAcceptanceBudget 生成同轮样本");
        waitForDrain();
        List<PairRow> pairs = new ArrayList<>();
        List<String[]> acceptedRows = new ArrayList<>();
        readGzipLines(samples, line -> {
            String[] parts = line.split(",", -1);
            // 列: 0 phase,1 seq,2 ts,3 tenant,4 worker,5 object_id,6 request_id,7 latency,8 outcome
            if (parts.length >= 9 && "FORMAL".equals(parts[0]) && parts[8].startsWith("ACCEPTED")) {
                acceptedRows.add(parts);
            }
        });
        Map<String, String[]> recordMeta = new HashMap<>();
        for (String[] parts : acceptedRows) {
            recordMeta.put(parts[6], parts);
        }
        List<String> batch = new ArrayList<>(1000);
        for (String recordId : recordMeta.keySet()) {
            batch.add(recordId);
            if (batch.size() >= 1000) {
                collectPairs(batch, pairs, recordMeta);
                batch = new ArrayList<>(1000);
            }
        }
        if (!batch.isEmpty()) {
            collectPairs(batch, pairs, recordMeta);
        }
        long incomplete = recordMeta.size() - pairs.size();
        List<Double> latencies = pairs.stream().mapToDouble(PairRow::latencyMs).sorted().boxed().toList();
        long succeededPairs = pairs.stream().filter(p -> "SUCCEEDED".equals(p.status())).count();
        long rejectedPairs = pairs.size() - succeededPairs;
        String summary = ("g1b.pairs scenario=light-process-acceptance accepted=%d validPairs=%d"
                + " succeededPairs=%d rejectedPairs=%d incomplete=%d"
                + " pairP50=%.1fms pairP95=%.1fms pairP99=%.1fms pairMax=%.1fms"
                + " boundary=accept_row_ts(<=accept-commit) -> target_row_ts(<=target-visible;"
                + " post-drain re-read probes visibility) runId=%s")
                .formatted(recordMeta.size(), pairs.size(), succeededPairs, rejectedPairs, incomplete,
                        percentile(latencies, 0.50), percentile(latencies, 0.95),
                        percentile(latencies, 0.99), percentile(latencies, 1.0), runId);
        StringBuilder pairCsv = new StringBuilder(
                "record_id,command_id,invocation_id,tenant,accept_row_ts,target_row_ts,status,"
                        + "latency_ms\n");
        for (PairRow p : pairs) {
            pairCsv.append(String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%s,%s,%.1f%n",
                    p.recordId(), p.commandId(), p.invocationId(), p.tenant(), p.acceptTs(),
                    p.targetTs(), p.status(), p.latencyMs()));
        }
        writeNew(evidenceDir.resolve("light-process-acceptance-pairs.csv.gz"),
                out -> {
                    try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
                        gz.write(pairCsv.toString().getBytes(StandardCharsets.UTF_8));
                    }
                });
        // 提交后只读可见性探针：重读前 100 对行，记录 PG 当前时钟作为可见上界证据
        String pgNow = jdbc.queryForObject("SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS.MS')",
                String.class);
        long visibleProbe = 0;
        for (PairRow p : pairs.subList(0, Math.min(100, pairs.size()))) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE id::text = ?"
                            + " AND status IN ('SUCCEEDED','REJECTED')", Integer.class, p.invocationId());
            if (n != null && n == 1) {
                visibleProbe++;
            }
        }
        summary += "\nvisibility-probe reRead=100 visible=" + visibleProbe
                + " observedAtPgClock=" + pgNow
                + " (提交后只读可见；accept_row_ts<=受理提交时刻, target_row_ts<=目标可见时刻)";
        writeEvidence("light-process-acceptance-pairs.txt", summary);
        System.out.println("[P62-EV] " + summary);
        org.assertj.core.api.Assertions.assertThat(pairs.size()).as("有效配对数>0").isGreaterThan(0);
    }

    /** 配对前等待同轮命令队列与节点异步任务排干（有界）；超时按未完成如实计入。 */
    private void waitForDrain() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8 * 60_000L;
        long lastLog = 0;
        while (System.currentTimeMillis() < deadline) {
            Long pendingCommands = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_bpm_command WHERE command_key LIKE 'FLOW_START:%'"
                            + " AND status IN ('PENDING','PROCESSING')", Long.class);
            Long pendingJobs = jdbc.queryForObject("SELECT COUNT(*) FROM act_ru_job", Long.class);
            if ((pendingCommands == null || pendingCommands == 0)
                    && (pendingJobs == null || pendingJobs == 0)) {
                return;
            }
            if (System.currentTimeMillis() - lastLog > 30_000) {
                lastLog = System.currentTimeMillis();
                System.out.println("[P62-EV] g1b drain-wait pendingCommands=" + pendingCommands
                        + " pendingJobs=" + pendingJobs);
            }
            Thread.sleep(2000);
        }
        System.out.println("[P62-EV] g1b drain-wait timeout after 8min（未完成样本如实计入）");
    }

    private record PairRow(String recordId, String commandId, String invocationId, String tenant,
                           String acceptTs, String targetTs, String status, double latencyMs) {
    }

    private void collectPairs(List<String> recordIds, List<PairRow> pairs,
                              Map<String, String[]> recordMeta) {
        String in = String.join(",", recordIds.stream().map(id -> "'" + id + "'").toList());
        // 目标提交行：variable 源下调用行 biz_record_id=动作目标 id，与受理记录分属两对象；
        // 同轮关联经流程实例链：受理记录(business_key) -> sw_bpm_instance.process_instance_id
        //   -> 调用行 invocation_key='NODE:<pid>:<activity>'（同实例唯一）
        Map<String, Map<String, Object>> invocations = new HashMap<>();
        jdbc.queryForList("SELECT i.business_key AS rid, v.id::text AS invocation_id, v.status,"
                        + " to_char(v.update_time, 'YYYY-MM-DD HH24:MI:SS.MS') AS target_ts"
                        + " FROM sw_bpm_instance i JOIN sw_form_txn_invocation v"
                        + " ON v.invocation_key = 'NODE:' || i.process_instance_id || ':act-1'"
                        + " WHERE i.business_key IN (" + in + ")"
                        + " AND v.status IN ('SUCCEEDED','REJECTED')")
                .forEach(row -> invocations.put(String.valueOf(row.get("rid")), row));
        // 受理行 + 受理命令（FLOW_START 行）——record/command/target 同对象三元
        Map<String, String> acceptTs = new HashMap<>();
        jdbc.queryForList("SELECT id::text AS rid, to_char(create_time, 'YYYY-MM-DD HH24:MI:SS.MS')"
                        + " AS accept_ts FROM " + fixtures.get(0L).stockTable
                        + " WHERE id::text IN (" + in + ")"
                        + " UNION ALL SELECT id::text, to_char(create_time, 'YYYY-MM-DD HH24:MI:SS.MS')"
                        + " FROM " + fixtures.get(100L).stockTable + " WHERE id::text IN (" + in + ")")
                .forEach(row -> acceptTs.put(String.valueOf(row.get("rid")),
                        String.valueOf(row.get("accept_ts"))));
        Map<String, String> commandIds = new HashMap<>();
        String commandKeys = recordIds.stream().map(id -> "'FLOW_START:" + id + "'")
                .collect(java.util.stream.Collectors.joining(","));
        jdbc.queryForList("SELECT substring(command_key from 12) AS rid, id::text AS cid"
                        + " FROM sw_bpm_command WHERE command_key IN (" + commandKeys + ")")
                .forEach(row -> commandIds.put(String.valueOf(row.get("rid")),
                        String.valueOf(row.get("cid"))));
        for (String recordId : recordIds) {
            Map<String, Object> inv = invocations.get(recordId);
            String acc = acceptTs.get(recordId);
            if (inv == null || acc == null) {
                continue;
            }
            String[] meta = recordMeta.get(recordId);
            java.time.LocalDateTime a = LocalDateTime.parse(acc, TS);
            java.time.LocalDateTime t = LocalDateTime.parse(String.valueOf(inv.get("target_ts")), TS);
            double latency = java.time.Duration.between(a, t).toNanos() / 1_000_000.0;
            if (latency < 0) {
                continue;
            }
            pairs.add(new PairRow(recordId, commandIds.getOrDefault(recordId, ""),
                    String.valueOf(inv.get("invocation_id")), meta[3], acc,
                    String.valueOf(inv.get("target_ts")), String.valueOf(inv.get("status")), latency));
        }
    }

    // ==================== 恢复段（U08 套件内自检；权威证据=真实中断演练 G2a） ====================

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("恢复段（套件内，先于负载场景单独度量）：100条无外部依赖命令收敛≤120s、重复效果=0")
    void recoveryDrainBudget() throws Exception {
        TenantFixture t0 = fixtures.get(0L);
        com.sw.ck.bpm.process.service.TxnBatchService batchService =
                app.getBean(com.sw.ck.bpm.process.service.TxnBatchService.class);
        BpmCommandMapper commandMapper = app.getBean(BpmCommandMapper.class);
        List<Long> commandIds = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
            request.setBatchKey("budget-recover-" + runId + "-" + i);
            request.setActionId(t0.actionId);
            TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
            item.setItemKey("recover-" + i);
            item.setRecordId(t0.recordIds.get(i % t0.recordIds.size()));
            item.setQuantity("1");
            request.setItems(List.of(item));
            TxnBatchView view = asTenant(0L, t0.userId, () -> batchService.submit(request));
            commandIds.add(view.getCommandId());
        }
        long begin = System.currentTimeMillis();
        long deadline = begin + 120_000L;
        int completed = 0;
        while (System.currentTimeMillis() < deadline) {
            completed = 0;
            for (Long id : commandIds) {
                BpmCommand c = asTenant(0L, t0.userId, () -> commandMapper.selectById(id));
                if (c != null && "COMPLETED".equals(c.getStatus())) {
                    completed++;
                }
            }
            if (completed >= 100) {
                break;
            }
            Thread.sleep(500);
        }
        long elapsed = System.currentTimeMillis() - begin;
        long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref LIKE 'BATCH:budget-recover-"
                        + runId + "-%'",
                Long.class);
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:budget-recover-"
                        + runId + "-%'",
                Long.class);
        org.assertj.core.api.Assertions.assertThat(elapsed)
                .as("100条命令收敛 %d ms ≤120s", elapsed).isLessThanOrEqualTo(120_000L);
        org.assertj.core.api.Assertions.assertThat(effects).as("效果权威恰100").isEqualTo(100L);
        org.assertj.core.api.Assertions.assertThat(invocations).as("调用记录恰100（重复效果=0）").isEqualTo(100L);
        writeEvidence("recovery-drain.txt", "elapsedMs=" + elapsed + " completed=" + completed
                + " effects=100 invocations=100 (≤120s budget, duplicate-effects=0) runId=" + runId);
        System.out.println("[P62-EV] budget.recovery elapsedMs=" + elapsed + " completed=100/100"
                + " duplicate-effects=0");
    }

    // ==================== G2b 压力边界：64并发只度量 ====================

    @Test
    @DisplayName("G2b 压力边界：64并发（两租户各32）× 各1万对象 × 每租户50%争同一对象 × 5min 只度量")
    void stressBoundaryObservation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("p62.budget.stress"), "压力边界按需运行：-Dp62.budget.stress=true");
        seedTenant(0L, 92901L, "p62_stress_t0", OBJECTS_PER_TENANT_STRESS, 100000, "stress_reserve");
        TenantFixture t100 = seedTenant(100L, 92902L, "p62_stress_t100",
                OBJECTS_PER_TENANT_STRESS, 100000, "stress_reserve");
        // 每租户热点对象余额收紧到 5000：50% 争用下先锁等待后合法拒绝（1604），形成完整竞争画像
        jdbc.update("UPDATE " + t100.stockTable + " SET qty_available = 5000 WHERE id = ?",
                t100.recordIds.get(0));
        jdbc.update("UPDATE " + fixtures.get(0L).stockTable + " SET qty_available = 5000 WHERE id = ?",
                fixtures.get(0L).recordIds.get(0));
        // 复核04 G2b 剩余项：真实 OA 业务代表读（已授权用户待办 /workflow/tasks/todo）
        // 与压力窗口全程并行——每租户 1 个真实待办对象（APPROVAL 节点实例 approve 至测量用户），
        // 逐请求记录 tenant/user/endpoint/业务对象/业务码/发起时间/耗时/拒绝，不再以 /api/auth/menus 代 OA
        seedOaBusiness();
        // 资源采样：5s 一轮（堆/线程/PG 活跃与锁等待）
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = new Thread(() -> {
            StringBuilder csv = new StringBuilder("ts,heap_used_mib,threads,pg_active,pg_lock_waits\n");
            while (sampling.get()) {
                try {
                    long heapUsed = (Runtime.getRuntime().totalMemory()
                            - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
                    long pgActive = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM pg_stat_activity WHERE state='active'"
                                    + " AND pid <> pg_backend_pid()", Long.class);
                    long lockWaits = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type='Lock'",
                            Long.class);
                    csv.append(String.format(Locale.ROOT, "%s,%d,%d,%d,%d%n",
                            LocalDateTime.now().format(TS), heapUsed, Thread.activeCount(),
                            pgActive, lockWaits));
                } catch (Exception e) {
                    csv.append(LocalDateTime.now().format(TS)).append(",sample-error\n");
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            try {
                writeNew(evidenceDir.resolve("stress-resources.csv"), out ->
                        out.write(csv.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                System.out.println("[P62-EV] stress resource csv write failed: " + e);
            }
        }, "p62-stress-sampler");
        sampler.setDaemon(true);
        sampler.start();
        // OA 并行请求（复核03 G2b）：2 条代表 OA 读路径（/api/auth/menus，页面加载依赖）
        // 与压力窗口全程并行，逐请求记录 endpoint/时间/结果/耗时
        AtomicBoolean oaRunning = new AtomicBoolean(true);
        Thread oaWorker = oaParallelWorker(oaRunning);
        oaWorker.start();
        // OA 业务代表读并行 worker（复核04 G2b 剩余项）：两租户各自真实待办读，逐请求落 CSV
        Thread oaBizWorker0 = oaBusinessWorker(oaRunning, 0L);
        Thread oaBizWorker100 = oaBusinessWorker(oaRunning, 100L);
        oaBizWorker0.start();
        oaBizWorker100.start();
        try {
            runLoad(new LoadSpec("stress-boundary", CONCURRENCY_STRESS,
                    STRESS_WARMUP_SECONDS, STRESS_FORMAL_SECONDS, 2, this::invokeRealtimeOnce));
            MeasurementReport report = reportFromSampleFile("stress-boundary", Double.NaN, "SUCCEEDED",
                CONCURRENCY_STRESS, STRESS_WARMUP_SECONDS, STRESS_FORMAL_SECONDS);
            System.out.println("[P62-EV] g2b observation-only verdict=" + report.verdict());
        } finally {
            sampling.set(false);
            oaRunning.set(false);
            sampler.join(10_000);
            oaWorker.join(10_000);
            oaBizWorker0.join(10_000);
            oaBizWorker100.join(10_000);
        }
        // worker 收敛后再落 OA 业务读报告（含逐租户 CSV 与首响应原文）
        writeOaBusinessReport(oaBizWindowRows);
    }

    /** OA 并行读 worker（复核03 G2b）：压力窗口内持续请求 /api/auth/menus，逐请求落 CSV。 */
    private Thread oaParallelWorker(AtomicBoolean running) {
        return new Thread(() -> {
            StringBuilder csv = new StringBuilder("endpoint,ts,latency_ms,http_status\n");
            long count = 0;
            long non200 = 0;
            long errors = 0;
            String bearer = "Bearer test_" + fixtures.get(0L).userId;
            while (running.get()) {
                long begin = System.nanoTime();
                String outcome;
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/api/auth/menus"))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Authorization", bearer)
                            .GET()
                            .build();
                    HttpResponse<String> response = HTTP.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    outcome = String.valueOf(response.statusCode());
                    if (response.statusCode() != 200) {
                        non200++;
                    }
                } catch (Exception e) {
                    outcome = "ERROR:" + e.getClass().getSimpleName();
                    errors++;
                }
                double latency = (System.nanoTime() - begin) / 1_000_000.0;
                count++;
                csv.append(String.format(Locale.ROOT, "/api/auth/menus,%s,%.1f,%s%n",
                        LocalDateTime.now().format(TS), latency, outcome));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                writeNew(evidenceDir.resolve("oa-parallel-requests.csv"), out ->
                        out.write(csv.toString().getBytes(StandardCharsets.UTF_8)));
                String line = "oa-parallel endpoint=/api/auth/menus requests=" + count
                        + " non200=" + non200 + " errors=" + errors
                        + " window=stress-formal-parallel identity=test_"
                        + fixtures.get(0L).userId;
                Files.writeString(evidenceDir.resolve("oa-parallel-summary.txt"), line + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                System.out.println("[P62-EV] " + line);
            } catch (Exception e) {
                System.out.println("[P62-EV] oa csv write failed: " + e);
            }
        }, "p62-oa-parallel");
    }

    // ==================== 复核04 G2b 剩余项：真实 OA 业务代表读（同压力窗口） ====================

    /**
     * 每租户真实 OA 业务对象：发布 APPROVAL 节点流程并绑定本租户 OA 表单，
     * 以测量用户为发起人发起 1 个实例 → 该用户出现 1 条真实待办（/workflow/tasks/todo 可读）。
     */
    private void seedOaBusiness() throws Exception {
        for (Long tenant : List.of(0L, 100L)) {
            TenantFixture fx = fixtures.get(tenant);
            String formKey = "p62_oa_todo_t" + tenant;
            long userId = fx.userId;
            BpmProcessDefService processDefService = appRef.getBean(BpmProcessDefService.class);
            com.sw.ck.bpm.process.service.ProcessStartService startService =
                    appRef.getBean(com.sw.ck.bpm.process.service.ProcessStartService.class);
            asTenant(tenant, userId, () -> {
                FormDefDTO draft = appRef.getBean(FormDefService.class)
                        .createDraft(formKey, "OA业务待办", null, null);
                appRef.getBean(FormDefService.class).saveConfig(draft.getId(), stockDefinition());
                appRef.getBean(FormDefService.class).publish(draft.getId());
                return null;
            });
            String processKey = asTenant(tenant, userId, () -> {
                var def = processDefService.createDef("OA业务待办流程-" + tenant, formKey);
                List<GraphElement> elements = List.of(
                        node("start", "START", Map.of()),
                        node("approve", "APPROVAL", Map.of("name", "OA待办审批",
                                "approver", Map.of("type", "DESIGNATED",
                                        "value", List.of(String.valueOf(userId))))),
                        node("end", "END", Map.of()),
                        edge("e1", "start", "approve"),
                        edge("e2", "approve", "end"));
                ProcessGraph graph = ProcessGraph.builder()
                        .processKey(def.getProcessKey()).name("OA业务待办流程-" + tenant)
                        .formKey(formKey).version(1).elements(elements).build();
                String json = appRef.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                        .writeValueAsString(graph);
                processDefService.saveDraftGraph(def.getId(), json);
                BpmProcessDef published = processDefService.publish(def.getId());
                if (!"PUBLISHED".equals(published.getStatus())) {
                    throw new IllegalStateException("OA 业务流程发布失败: " + def.getProcessKey());
                }
                return published.getProcessKey();
            });
            asTenant(tenant, userId, () -> {
                com.sw.ck.bpm.process.dto.StartCommand cmd =
                        new com.sw.ck.bpm.process.dto.StartCommand();
                cmd.setFormKey(formKey);
                cmd.setRecordId("OA-TODO-" + tenant + "-" + userId);
                cmd.setSubmitter(userId);
                cmd.setTenantId(tenant);
                cmd.setSubmittedData(data("material", "OA-TODO-" + tenant,
                        "qty_available", "1", "qty_reserved", "0"));
                startService.start(cmd);
                return null;
            });
            long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ACT_RU_TASK WHERE ASSIGNEE_ = ?",
                    Long.class, String.valueOf(userId));
            if (pending < 1) {
                throw new IllegalStateException("OA 业务待办未生成: tenant=" + tenant
                        + " user=" + userId + " processKey=" + processKey);
            }
            System.out.println("[P62-EV] oa-business seeded tenant=" + tenant + " user=" + userId
                    + " formKey=" + formKey + " processKey=" + processKey + " pendingTasks=" + pending);
        }
    }

    /** OA 业务代表读样本：tenant,user,endpoint,bizCode,bizObjects,httpStatus,ts,latencyMs,outcome。 */
    private final List<String> oaBizWindowRows = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * OA 业务代表读 worker（复核04 G2b 剩余项）：压力窗口内持续请求本租户
     * `/api/workflow/tasks/todo`（真实待办读，业务对象=待办任务），逐请求记录原始值。
     */
    private Thread oaBusinessWorker(AtomicBoolean running, Long tenant) {
        return new Thread(() -> {
            TenantFixture fx = fixtures.get(tenant);
            String bearer = "Bearer test_" + fx.userId;
            long count = 0;
            long non200 = 0;
            long errors = 0;
            long minTotal = Long.MAX_VALUE;
            String firstBody = null;
            while (running.get()) {
                long begin = System.nanoTime();
                String httpStatus;
                String bizCode = "";
                String bizObjects = "";
                String outcome;
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port
                                    + "/api/workflow/tasks/todo?pageNum=1&pageSize=10"))
                            .timeout(REQUEST_TIMEOUT)
                            .header("Authorization", bearer)
                            .GET()
                            .build();
                    HttpResponse<String> response = HTTP.send(request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    httpStatus = String.valueOf(response.statusCode());
                    if (response.statusCode() != 200) {
                        non200++;
                        outcome = "REJECTED:" + response.statusCode();
                    } else {
                        bizCode = extractJsonNumber(response.body(), "code");
                        String total = extractJsonScalar(response.body(), "total");
                        if (total == null) {
                            throw new IllegalStateException("待办响应缺 total/非成功业务码");
                        }
                        long totalValue = Long.parseLong(total);
                        bizObjects = "todoTotal=" + totalValue;
                        minTotal = Math.min(minTotal, totalValue);
                        if (firstBody == null) {
                            firstBody = response.body();
                        }
                        outcome = "OK";
                    }
                } catch (Exception e) {
                    httpStatus = "-";
                    outcome = "ERROR:" + e.getClass().getSimpleName();
                    errors++;
                }
                double latency = (System.nanoTime() - begin) / 1_000_000.0;
                count++;
                oaBizWindowRows.add(String.format(Locale.ROOT,
                        "%d,%d,/api/workflow/tasks/todo,%s,%s,%s,%s,%.1f,%s",
                        tenant, fx.userId, bizCode, bizObjects, httpStatus,
                        LocalDateTime.now().format(TS), latency, outcome));
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                StringBuilder csv = new StringBuilder(
                        "tenant,user,endpoint,bizCode,bizObjects,httpStatus,ts,latencyMs,outcome\n");
                for (String row : oaBizWindowRows) {
                    if (row.startsWith(tenant + ",")) {
                        csv.append(row).append('\n');
                    }
                }
                writeNew(evidenceDir.resolve("oa-business-todo-requests-t" + tenant + ".csv"),
                        out -> out.write(csv.toString().getBytes(StandardCharsets.UTF_8)));
                if (firstBody != null) {
                    Files.writeString(evidenceDir.resolve("oa-business-todo-response-t" + tenant + ".json"),
                            firstBody, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                }
                System.out.println("[P62-EV] oa-business tenant=" + tenant + " requests=" + count
                        + " non200=" + non200 + " errors=" + errors + " minTodoTotal="
                        + (minTotal == Long.MAX_VALUE ? -1 : minTotal));
            } catch (Exception e) {
                System.out.println("[P62-EV] oa business csv write failed: " + e);
            }
        }, "p62-oa-business-t" + tenant);
    }

    /**
     * OA 业务读报告：按实际样本文件回读统计（请求数/非 200/拒绝/错误/耗时分位），
     * 并把窗口边界（首末请求时点）与负载正式窗口对齐，供"窗口外不作并行证据"核验。
     */
    private void writeOaBusinessReport(List<String> rows) throws Exception {
        String summary = buildOaBusinessSummary(rows);
        System.out.println("[P62-EV] " + summary.replace('\n', ' '));
    }

    private String buildOaBusinessSummary(List<String> rows) throws Exception {
        Path sample = evidenceDir.resolve("stress-boundary-samples.csv.gz");
        final String[] firstFormal = {null};
        final String[] lastFormal = {null};
        final long[] formal = {0};
        readGzipLines(sample, line -> {
            String[] parts = line.split(",", -1);
            if (parts.length < 9 || !"FORMAL".equals(parts[0])) {
                return;
            }
            formal[0]++;
            if (firstFormal[0] == null) {
                firstFormal[0] = parts[2];
            }
            lastFormal[0] = parts[2];
        });
        StringBuilder sb = new StringBuilder();
        sb.append("oa-business endpoint=/api/workflow/tasks/todo window=stress-formal-parallel"
                + " loadFormalSamples=").append(formal[0])
                .append(" loadFormalFirstTs=").append(firstFormal[0])
                .append(" loadFormalLastTs=").append(lastFormal[0]).append('\n');
        for (Long tenant : List.of(0L, 100L)) {
            List<Double> latencies = new ArrayList<>();
            long requests = 0;
            long non200 = 0;
            long errors = 0;
            long nonZeroCode = 0;
            long minTotal = Long.MAX_VALUE;
            long maxTotal = Long.MIN_VALUE;
            String firstTs = null;
            String lastTs = null;
            for (String row : rows) {
                String[] parts = row.split(",", -1);
                if (parts.length < 9 || !parts[0].equals(String.valueOf(tenant))) {
                    continue;
                }
                requests++;
                if (firstTs == null) {
                    firstTs = parts[6];
                }
                lastTs = parts[6];
                if (!"200".equals(parts[5])) {
                    non200++;
                }
                if (parts[8].startsWith("ERROR")) {
                    errors++;
                }
                if (!"0".equals(parts[3])) {
                    nonZeroCode++;
                }
                String total = parts[4].replace("todoTotal=", "");
                long totalValue = Long.parseLong(total);
                minTotal = Math.min(minTotal, totalValue);
                maxTotal = Math.max(maxTotal, totalValue);
                latencies.add(Double.parseDouble(parts[7]));
            }
            latencies.sort(Double::compare);
            sb.append(String.format(Locale.ROOT,
                    "oa-business tenant=%d user=%d endpoint=/api/workflow/tasks/todo requests=%d"
                            + " non200=%d errors=%d nonZeroBizCode=%d todoTotalMin=%d todoTotalMax=%d"
                            + " p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms"
                            + " firstTs=%s lastTs=%s%n",
                    tenant, fixtures.get(tenant).userId, requests, non200, errors, nonZeroCode,
                    minTotal == Long.MAX_VALUE ? -1 : minTotal,
                    maxTotal == Long.MIN_VALUE ? -1 : maxTotal,
                    percentile(latencies, 0.50), percentile(latencies, 0.95),
                    percentile(latencies, 0.99),
                    latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1),
                    firstTs, lastTs));
        }
        writeNew(evidenceDir.resolve("oa-business-summary.txt"),
                out -> out.write(sb.toString().getBytes(StandardCharsets.UTF_8)));
        return sb.toString();
    }

    // ==================== 负载框架 ====================

    /** 单请求动作：返回 [objectId, requestId, latencyMs, outcome]。 */
    private interface RequestAction {
        String[] call(Long tenant, int worker) throws Exception;
    }

    private record LoadSpec(String scenario, int concurrency, int warmupSeconds, int formalSeconds,
                            int hotspotMod, RequestAction action) {
    }

    private String[] invokeRealtimeOnce(Long tenant, int worker) throws Exception {
        TenantFixture fx = fixtures.get(tenant);
        String recordId = pickRecord(fx, worker);
        String invocationKey = "BUDGET-" + runId + "-" + tenant + "-" + worker + "-"
                + java.util.UUID.randomUUID();
        long begin = System.nanoTime();
        String body = "{\"recordId\":\"" + recordId + "\",\"quantity\":\"1\",\"invocationKey\":\""
                + invocationKey + "\"}";
        String outcome = post("/api/form/action/" + fx.actionId + "/invoke", fx.bearer, body,
                json -> json.contains("\"SUCCEEDED\"") ? "SUCCEEDED" : "REJECTED");
        return new String[]{recordId, invocationKey,
                String.valueOf((System.nanoTime() - begin) / 1_000_000.0), outcome};
    }

    private String[] submitLightOnce(Long tenant, int worker) throws Exception {
        TenantFixture fx = fixtures.get(tenant);
        String material = "B-" + runId + "-" + tenant + "-" + worker + "-"
                + java.util.UUID.randomUUID();
        // 实际动作目标：预置 1000 对象按固定序列选择（10% 热点跨线程共享），
        // 经 target_record_id 透传为流程变量，TXN_ACTION recordIdSource=variable 解析——
        // 样本 object_id 即真实动作目标（复核03 G1a 修正：不再以新业务键冒充目标）
        String targetRecordId = pickRecord(fx, worker);
        String[] captured = new String[1];
        long begin = System.nanoTime();
        String body = "{\"material\":\"" + material + "\",\"qty_available\":\"1000\","
                + "\"qty_reserved\":\"0\",\"target_record_id\":\"" + targetRecordId + "\"}";
        String outcome = post("/api/form/data/" + fx.formKey, fx.bearer, body,
                json -> {
                    captured[0] = json;
                    return json != null && !json.isEmpty() ? "ACCEPTED" : "REJECTED";
                });
        // 配对键=服务端真实记录 id（HTTP data 回包）；object_id=真实动作目标
        String realRecordId = captured[0] == null ? material
                : captured[0].endsWith("\"") ? captured[0].substring(0, captured[0].length() - 1)
                : captured[0];
        return new String[]{targetRecordId, realRecordId,
                String.valueOf((System.nanoTime() - begin) / 1_000_000.0), outcome};
    }

    /** 单请求 HTTP 调用；返回结果分类（SUCCEEDED/ACCEPTED/REJECTED:<code>/TIMEOUT/ERROR）。 */
    private String post(String pathWithQuery, String bearer, String jsonBody,
                        java.util.function.Function<String, String> outcomeOf) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + pathWithQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return "REJECTED:" + response.statusCode();
            }
            String body = response.body();
            String code = extractJsonNumber(body, "code");
            if (!"0".equals(code)) {
                return "REJECTED:" + code;
            }
            String data = extractJsonField(body, "data");
            return outcomeOf.apply(data == null ? "" : data);
        } catch (java.net.http.HttpTimeoutException e) {
            return "TIMEOUT";
        } catch (IOException e) {
            return "ERROR:" + e.getClass().getSimpleName();
        }
    }

    /** 最小 JSON 数值字段提取（R 契约固定 {code,msg,data}，避免引入解析器热路径开销）。 */
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
        String v = json.substring(colon + 1, end).trim();
        return v.startsWith("-") || v.isEmpty() ? v : v;
    }

    /** R 契约字段的实际渲染可能是字符串（如 PageResult.total="1"）：取带引号或不带引号的原始标量。 */
    private static String extractJsonScalar(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i) + 1;
        while (colon < json.length() && json.charAt(colon) == ' ') {
            colon++;
        }
        if (colon >= json.length()) {
            return null;
        }
        if (json.charAt(colon) == '"') {
            int end = json.indexOf('"', colon + 1);
            return end < 0 ? null : json.substring(colon + 1, end);
        }
        int end = colon;
        while (end < json.length() && (json.charAt(end) == '-' || json.charAt(end) == '.'
                || Character.isDigit(json.charAt(end)))) {
            end++;
        }
        return end == colon ? null : json.substring(colon, end);
    }

    /** 提取 data 字段的原始值（字符串取引号内，对象/数组取原文到平衡点由调用方处理）。 */
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
        while (end < json.length() && json.charAt(end) != ',') {
            end++;
        }
        return json.substring(start, end);
    }

    /** 固定种子目标选择：每 worker 一条贯穿全程的前进随机序列（复核03 G1a 修正——
     * 旧实现每次调用重建同种子 Random，序列恒定导致每 worker 恒命中同一对象）。
     * 热点=每租户 records[0]，由两租户各 8/32 worker 跨线程共享；其余均匀分布。 */
    private final Map<Integer, Random> workerRandoms = new java.util.concurrent.ConcurrentHashMap<>();

    private String pickRecord(TenantFixture fx, int worker) {
        List<String> records = fx.recordIds;
        Random random = workerRandoms.computeIfAbsent(worker,
                w -> new Random(SEED * 31 + w * 2L + (fx.tenant == 0L ? 0 : 1)));
        if (random.nextInt(hotspotMod) == 0) {
            return records.get(0);
        }
        return records.get(1 + random.nextInt(records.size() - 1));
    }

    private int hotspotMod = HOTSPOT_MOD_G1;

    private void runLoad(LoadSpec spec) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(spec.concurrency());
        CountDownLatch start = new CountDownLatch(1);
        Path sampleFile = evidenceDir.resolve(spec.scenario() + "-samples.csv.gz");
        SampleWriter writer = new SampleWriter(sampleFile);
        writer.start();
        AtomicLong submitCount = new AtomicLong();
        this.hotspotMod = spec.hotspotMod();
        // 预热：不计正式样本（phase=WARMUP 留档审计）
        List<Future<?>> warmupFutures = new ArrayList<>();
        long warmupEnd = System.currentTimeMillis() + spec.warmupSeconds() * 1000L;
        for (int w = 0; w < spec.concurrency(); w++) {
            final int worker = w;
            final Long tenant = worker % 2 == 0 ? 0L : 100L;
            warmupFutures.add(pool.submit(() -> {
                try {
                    start.await();
                    while (System.currentTimeMillis() < warmupEnd) {
                        String[] s = spec.action().call(tenant, worker);
                        writer.offer(new String[]{"WARMUP", String.valueOf(submitCount.incrementAndGet()),
                                LocalDateTime.now().format(TS), String.valueOf(tenant),
                                String.valueOf(worker), s[0], s[1], s[2], s[3]});
                    }
                } finally {
                    // worker 结束
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : warmupFutures) {
            f.get();
        }
        // 正式：全量逐请求记录
        List<Future<?>> formalFutures = new ArrayList<>();
        long formalEnd = System.currentTimeMillis() + spec.formalSeconds() * 1000L;
        for (int w = 0; w < spec.concurrency(); w++) {
            final int worker = w;
            final Long tenant = worker % 2 == 0 ? 0L : 100L;
            formalFutures.add(pool.submit(() -> {
                try {
                    while (System.currentTimeMillis() < formalEnd) {
                        String[] s = spec.action().call(tenant, worker);
                        writer.offer(new String[]{"FORMAL", String.valueOf(submitCount.incrementAndGet()),
                                LocalDateTime.now().format(TS), String.valueOf(tenant),
                                String.valueOf(worker), s[0], s[1], s[2], s[3]});
                    }
                } catch (Exception e) {
                    System.out.println("[P62-EV] worker " + worker + " aborted: " + e);
                }
                return null;
            }));
        }
        for (Future<?> f : formalFutures) {
            f.get();
        }
        pool.shutdown();
        writer.finish();
        System.out.println("[P62-EV] load done scenario=" + spec.scenario()
                + " requests=" + submitCount.get() + " samples=" + sampleFile.getFileName()
                + " sha256=" + sha256(sampleFile));
    }

    /** 单写者线程样本落盘：gzip CSV，CREATE_NEW 不可覆盖；热路径仅入队。 */
    private static final class SampleWriter {
        private final BlockingQueue<String[]> queue = new ArrayBlockingQueue<>(100_000);
        private final String[] poison = new String[0];
        private final Path file;
        private Thread thread;
        private volatile Throwable error;

        SampleWriter(Path file) {
            this.file = file;
        }

        void start() throws IOException {
            OpenOption[] options = {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};
            thread = new Thread(() -> {
                try (var out = Files.newOutputStream(file, options);
                     var gz = new java.util.zip.GZIPOutputStream(out, 64 * 1024);
                     var bw = new BufferedWriter(new java.io.OutputStreamWriter(gz, StandardCharsets.UTF_8),
                             256 * 1024)) {
                    bw.write("phase,seq,ts_iso,tenant,worker,object_id,request_id,latency_ms,outcome\n");
                    while (true) {
                        String[] row = queue.take();
                        if (row.length == 0) {
                            break;
                        }
                        bw.write(String.join(",", row));
                        bw.write('\n');
                    }
                    bw.flush();
                } catch (Throwable t) {
                    error = t;
                }
            }, "p62-sample-writer");
            thread.start();
        }

        void offer(String[] row) {
            try {
                boolean ok = queue.offer(row, 30, TimeUnit.SECONDS);
                if (!ok) {
                    throw new IllegalStateException("样本队列溢出：写入跟不上负载（测量环境故障）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void finish() throws Exception {
            offer(poison);
            thread.join(120_000);
            if (error != null) {
                throw new IllegalStateException("样本落盘失败（固定 runId 不可覆盖）", error);
            }
        }
    }

    /** 回读样本文件复算统计（不由内存计数替代）。 */
    private record MeasurementReport(String scenario, long formalSamples, long legal, long illegal,
                                     long timeouts, long errors, long rejected, double rejectRate,
                                     double p50, double p95, double p99, double max,
                                     double budgetP99, String verdict, String sha256) {
    }

    private MeasurementReport reportFromSampleFile(String scenario, double budgetMillis,
                                                   String legalOutcome) throws Exception {
        return reportFromSampleFile(scenario, budgetMillis, legalOutcome,
                CONCURRENCY_TOTAL, G1_WARMUP_SECONDS, G1_FORMAL_SECONDS);
    }

    private MeasurementReport reportFromSampleFile(String scenario, double budgetMillis,
                                                   String legalOutcome, int reportConcurrency,
                                                   int reportWarmupSeconds,
                                                   int reportFormalSeconds) throws Exception {
        Path sampleFile = evidenceDir.resolve(scenario + "-samples.csv.gz");
        List<Double> legal = new ArrayList<>();
        final long[] formal = {0};
        final long[] warmup = {0};
        final long[] timeouts = {0};
        final long[] errors = {0};
        final long[] rejected = {0};
        Map<String, Long> outcomes = new LinkedHashMap<>();
        readGzipLines(sampleFile, line -> {
            String[] parts = line.split(",", -1);
            if (parts.length < 9 || "phase".equals(parts[0])) {
                return;
            }
            if ("WARMUP".equals(parts[0])) {
                warmup[0]++;
                return;
            }
            if (!"FORMAL".equals(parts[0])) {
                return;
            }
            formal[0]++;
            String outcome = parts[8];
            outcomes.merge(outcome, 1L, Long::sum);
            double latency = Double.parseDouble(parts[7]);
            if (legalOutcome.equals(outcome)) {
                legal.add(latency);
            } else if (outcome.startsWith("REJECTED")) {
                rejected[0]++;
            } else if ("TIMEOUT".equals(outcome)) {
                timeouts[0]++;
            } else if (outcome.startsWith("ERROR")) {
                errors[0]++;
            }
        });
        legal.sort(Double::compare);
        long illegal = rejected[0] + timeouts[0] + errors[0];
        // 目标分布复算（复核03 G1a/G2b 完成条件）：每租户 正式样本数/热点命中数与频率/
        // 唯一目标数/热点跨worker数 —— 由样本文件逐行统计
        Map<String, Long> tenantTotal = new LinkedHashMap<>();
        Map<String, Long> tenantHotspot = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> tenantTargets = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> hotspotWorkers = new LinkedHashMap<>();
        readGzipLines(sampleFile, line -> {
            String[] parts = line.split(",", -1);
            if (parts.length < 9 || !"FORMAL".equals(parts[0])) {
                return;
            }
            String tenant = parts[3];
            String target = parts[5];
            String worker = parts[4];
            tenantTotal.merge(tenant, 1L, Long::sum);
            tenantTargets.computeIfAbsent(tenant, k -> new java.util.HashSet<>()).add(target);
            TenantFixture fx = fixtures.get(Long.valueOf(tenant));
            if (fx != null && !fx.recordIds.isEmpty() && fx.recordIds.get(0).equals(target)) {
                tenantHotspot.merge(tenant, 1L, Long::sum);
                hotspotWorkers.computeIfAbsent(tenant, k -> new java.util.HashSet<>()).add(worker);
            }
        });
        double rejectRate = formal[0] == 0 ? 1 : (double) rejected[0] / formal[0];
        String verdict = Double.isNaN(budgetMillis) ? "OBSERVATION-ONLY"
                : (formal[0] >= 5000 && percentile(legal, 0.99) <= budgetMillis
                        && illegal == 0 && rejectRate <= 0.01 ? "PASS" : "FAIL");
        MeasurementReport report = new MeasurementReport(scenario, formal[0], legal.size(), illegal,
                timeouts[0], errors[0], rejected[0], rejectRate,
                percentile(legal, 0.50), percentile(legal, 0.95), percentile(legal, 0.99),
                legal.isEmpty() ? 0 : legal.get(legal.size() - 1), budgetMillis, verdict,
                sha256(sampleFile));
        String summary = ("scenario=%s entry=HTTP(http://127.0.0.1:%d) concurrency=%d"
                + " warmup=%ds formal=%ds formalSamples=%d warmupSamples=%d legal=%d illegal=%d"
                + " (rejected=%d timeout=%d error=%d) rejectRate=%.4f"
                + " p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms budgetP99=%s verdict=%s"
                + " seed=%d runId=%s shortVerify=%s samplesSha256=%s").formatted(
                scenario, port, reportConcurrency, reportWarmupSeconds, reportFormalSeconds,
                report.formalSamples(), warmup[0], report.legal(), report.illegal(), report.rejected(),
                report.timeouts(), report.errors(), report.rejectRate(), report.p50(), report.p95(),
                report.p99(), report.max(),
                Double.isNaN(budgetMillis) ? "N/A" : budgetMillis + "ms", verdict, SEED, runId,
                isShortVerify(), report.sha256());
        String name = Double.isNaN(budgetMillis) ? "stress-boundary-report.txt"
                : scenario + "-report.txt";
        StringBuilder distribution = new StringBuilder();
        for (String tenant : tenantTotal.keySet()) {
            long total = tenantTotal.get(tenant);
            long hot = tenantHotspot.getOrDefault(tenant, 0L);
            distribution.append(String.format(Locale.ROOT,
                    "\ntarget-distribution tenant=%s formal=%d hotspotHits=%d hotspotFreq=%.4f"
                            + " uniqueTargets=%d hotspotSharedWorkers=%d",
                    tenant, total, hot, total == 0 ? 0 : (double) hot / total,
                    tenantTargets.get(tenant).size(),
                    hotspotWorkers.getOrDefault(tenant, java.util.Set.of()).size()));
        }
        writeEvidence(name, summary + "\noutcomes=" + outcomes + distribution);
        System.out.println("[P62-EV] budget." + summary + distribution);
        return report;
    }

    private static void assertThatPass(MeasurementReport report) {
        org.assertj.core.api.Assertions.assertThat(report.verdict())
                .as("%s 预算裁决（formal=%d legal=%d illegal=%d）", report.scenario(),
                        report.formalSamples(), report.legal(), report.illegal())
                .isEqualTo("PASS");
    }

    // ==================== 文件与哈希（CREATE_NEW 不可覆盖） ====================

    private interface LineConsumer {
        void accept(String line) throws IOException;
    }

    private static void readGzipLines(Path gz, LineConsumer consumer) throws IOException {
        try (var in = Files.newInputStream(gz, StandardOpenOption.READ);
             var stream = new java.util.zip.GZIPInputStream(in, 64 * 1024);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(stream,
                     StandardCharsets.UTF_8), 256 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.accept(line);
            }
        }
    }

    private interface Sink {
        void write(java.io.OutputStream out) throws IOException;
    }

    private static void writeNew(Path file, Sink sink) throws IOException {
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

    /** 解包 dynamic-datasource（baomidou 4.3.1）：DynamicRoutingDataSource.dataSourceMap →
     * ItemDataSource.realDataSource → DruidDataSource，读取真实 maxActive（环境合同字段）。 */
    private static long findDruidMaxActive() throws Exception {
        for (javax.sql.DataSource ds : app.getBeanProvider(javax.sql.DataSource.class)
                .stream().toList()) {
            if (ds instanceof com.baomidou.dynamic.datasource.DynamicRoutingDataSource routing) {
                for (javax.sql.DataSource inner : routing.getDataSources().values()) {
                    Long v = druidMaxActiveOf(inner);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        throw new IllegalStateException("未找到 Druid 池（无法核实 maxActive）");
    }

    private static Long druidMaxActiveOf(Object cur) throws Exception {
        for (int depth = 0; cur != null && depth < 6; depth++) {
            if (cur instanceof com.alibaba.druid.pool.DruidDataSource druid) {
                return (long) druid.getMaxActive();
            }
            cur = unwrapField(cur, "realDataSource");
        }
        return null;
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

    private static boolean isShortVerify() {
        return !"300".equals(String.valueOf(G1_FORMAL_SECONDS))
                || !"60".equals(String.valueOf(G1_WARMUP_SECONDS))
                || !"300".equals(String.valueOf(STRESS_FORMAL_SECONDS));
    }

    private static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 冻结环境档案：堆/参数/池/身份/源码身份（可独立复算测量组的对象）。 */
    private void writeEnvFrozen() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("runId=").append(runId).append('\n');
        sb.append("recordedAt=").append(LocalDateTime.now().format(TS)).append('\n');
        sb.append("entry=HTTP real Tomcat port=").append(port).append('\n');
        sb.append("javaVersion=").append(System.getProperty("java.version")).append('\n');
        sb.append("osName=").append(System.getProperty("os.name")).append('\n');
        sb.append("osArch=").append(System.getProperty("os.arch")).append('\n');
        sb.append("cores=").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("heapMaxMiB=").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append('\n');
        sb.append("jvmInputArgs=").append(String.join(" ",
                ProcessHandle.current().info().arguments().orElse(new String[0]))).append('\n');
        sb.append("envMAVEN_OPTS=").append(System.getenv().getOrDefault("MAVEN_OPTS", "")).append('\n');
        sb.append("buildCommit=").append(System.getProperty("p62.build.commit", "")).append('\n');
        sb.append("pgVersion=").append(jdbc.queryForObject("SELECT version()", String.class)).append('\n');
        sb.append("pgPort=").append(pg.getPort()).append('\n');
        sb.append("hikariMaxPool(stale-key,ineffective)=").append(app.getEnvironment()
                .getProperty("sw.datasource.dynamic.hikari.maximum-pool-size")).append('\n');
        sb.append("druidMaxActive(actual)=").append(findDruidMaxActive()).append('\n');
        sb.append("dispatcherPollMillis=").append(app.getEnvironment()
                .getProperty("sw.bpm.command.poll-interval-millis")).append('\n');
        sb.append("dispatcherBatchSize=").append(app.getEnvironment()
                .getProperty("sw.bpm.command.batch-size")).append('\n');
        sb.append("dispatcherStaleSeconds=").append(app.getEnvironment()
                .getProperty("sw.bpm.command.stale-seconds")).append('\n');
        sb.append("flowableAsyncCorePool=").append(app.getEnvironment()
                .getProperty("flowable.process.async.executor.core-pool-size")).append('\n');
        sb.append("debugAuth=enabled(dev profile, loopback) perTenantBearer\n");
        sb.append("identity=debug token test_<uid> -> UserDetailsProvider 正式回查\n");
        for (Map.Entry<Long, TenantFixture> e : fixtures.entrySet()) {
            TenantFixture fx = e.getValue();
            sb.append("fixture tenant=").append(e.getKey()).append(" userId=").append(fx.userId)
                    .append(" formKey=").append(fx.formKey).append(" actionId=").append(fx.actionId)
                    .append(" processKey=").append(fx.lightProcessKey)
                    .append(" objects=").append(fx.recordIds.size())
                    .append(" hotspotId=").append(fx.recordIds.get(0)).append('\n');
        }
        sb.append("budgetContract=realtime P99<=300ms entry->commit; light acceptance P99<=2s entry->persistent-acceptance;"
                + " client-observed loopback latency is declared upper bound of server entry->commit\n");
        Files.writeString(evidenceDir.resolve("env-frozen.txt"), sb.toString(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    // ==================== 种子 ====================

    private TenantFixture seedTenant(Long tenant, long userId, String formKey, int objects,
                                     double balance, String actionName) throws Exception {
        // 租户注册（I5 租户有效性：未注册租户的 debug 身份装载直接失败→401）
        jdbc.update("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id,"
                        + " version, name, code, status, description, domain_name) "
                        + "VALUES (?, current_timestamp, current_timestamp, 0, 0, 0, ?, ?, 0,"
                        + " '预算测量隔离租户', 'localhost') ON CONFLICT (id) DO NOTHING",
                tenant, "预算测量租户" + tenant, "p62-budget-t" + tenant);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, ?, 'seed-not-a-login-secret', '预算测量操作员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", userId, "budget-op-" + userId, tenant);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                + "VALUES (?, ?, ?, 2) ON CONFLICT (id) DO NOTHING",
                userId, tenant, userId);
        // 表单提交授权（form:data:submit 菜单 350 由 V0.1.0 登记；role 2 授权随种子补齐）
        jdbc.update("INSERT INTO sys_role_menu (id, create_time, update_time, deleted, tenant_id,"
                        + " version, role_id, menu_id) "
                        + "SELECT ?, current_timestamp, current_timestamp, 0, 0, 0, 2, 350 "
                        + "WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 2"
                        + " AND menu_id = 350 AND deleted = 0) ON CONFLICT (id) DO NOTHING",
                350000L + userId);
        FormDefService formDefService = app.getBean(FormDefService.class);
        com.sw.ck.form.txn.service.TxnActionService actionService =
                app.getBean(com.sw.ck.form.txn.service.TxnActionService.class);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        FormSubmitService submitService = app.getBean(FormSubmitService.class);

        asTenant(tenant, userId, () -> {
            FormDefDTO draft = formDefService.createDraft(formKey, "预算测量库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
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
                    actionName, "预算预占", "RESERVE", null, cfg)).id();
            actionService.publish(actionId);
            return actionId;
        });

        // 生产轻流程（固定代表：START→TXN_ACTION→END 单动作成功路径）发布并绑定表单
        fx.lightProcessKey = asTenant(tenant, userId, () -> {
            var def = processDefService.createDef("预算轻流程-" + tenant, formKey);
            List<GraphElement> elements = List.of(
                    node("start", "START", Map.of()),
                    node("act-1", "TXN_ACTION", Map.of(
                            "name", "库存预占", "actionId", fx.actionId,
                            "recordIdSource", "variable", "recordIdVariable", "variable:targetRecordId",
                            "quantity", "1",
                            "failureStrategy", "BLOCK")),
                    node("end", "END", Map.of()),
                    edge("e1", "start", "act-1"),
                    edge("e2", "act-1", "end"));
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name("预算轻流程-" + tenant)
                    .formKey(formKey).version(1).elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            processDefService.saveDraftGraph(def.getId(), json);
            BpmProcessDef published = processDefService.publish(def.getId());
            if (!"PUBLISHED".equals(published.getStatus())) {
                throw new IllegalStateException("轻流程发布失败: " + def.getProcessKey());
            }
            return published.getProcessKey();
        });

        // 种子期停用表单绑定（发布自动激活）：否则每笔种子提交都会触发轻流程实例
        // （种子数据无 target_record_id → 节点无目标 → 失败任务风暴）。种子完成后再激活，
        // 测量流量全程绑定生效（fixture 管理，不影响测量语义）
        jdbc.update("UPDATE sw_bpm_form_binding SET active = false"
                        + " WHERE process_def_key = ? AND form_key = ?",
                fx.lightProcessKey, formKey);
        // 固定种子对象记录：首条=热点（其余 90% 请求均匀分布其后 N-1 条）
        for (int i = 0; i < objects; i++) {
            String recordId = asTenant(tenant, userId, () -> submitService.submitForm(formKey,
                    data("material", "OBJ-" + tenant + "-" + fx.recordIds.size(),
                            "qty_available", String.valueOf(balance), "qty_reserved", "0"),
                    null, null, null));
            fx.recordIds.add(recordId);
        }
        jdbc.update("UPDATE sw_bpm_form_binding SET active = true"
                        + " WHERE process_def_key = ? AND form_key = ?",
                fx.lightProcessKey, formKey);
        long activeBindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_form_binding WHERE process_def_key = ?"
                        + " AND form_key = ? AND active = true", Long.class,
                fx.lightProcessKey, formKey);
        if (activeBindings != 1L) {
            throw new IllegalStateException("绑定重新激活失败: " + fx.lightProcessKey);
        }
        fixtures.put(tenant, fx);
        System.out.println("[P62-EV] budget seeded tenant=" + tenant + " objects=" + objects
                + " action=" + fx.actionId + " process=" + fx.lightProcessKey);
        return fx;
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
        return "{\"schemaVersion\":1,\"title\":\"预算测量库存\",\"fields\":["
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

    private static <T> T asTenant(Long tenant, long userId, Callable<T> action) {
        com.sw.ck.security.holder.LoginUser previous =
                com.sw.ck.security.holder.LoginUserHolder.get();
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenant);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "form:action:manage")));
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

    /** 生成 RSA PKCS#8 BASE64 测试私钥（同一批 P62 测试共用）。 */
    public static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** debug-auth 的 Redis 缓存无操作替换：隔离测量环境无 Redis；权限仍经 UserDetailsProvider 正式回查。 */
    public static class NoopLoginUserCacheService extends com.sw.ck.security.cache.LoginUserCacheService {
        public NoopLoginUserCacheService() {
            super(null, null);
        }

        @Override
        public void cache(com.sw.ck.security.holder.LoginUser loginUser) {
            // no-op
        }

        @Override
        public com.sw.ck.security.holder.LoginUser get(Long userId) {
            return null;
        }

        @Override
        public void evict(Long userId) {
            // no-op
        }

        @Override
        public void markTokenRevoked(String rawToken) {
            // no-op
        }

        @Override
        public boolean isTokenRevoked(String rawToken) {
            return false;
        }
    }
}
