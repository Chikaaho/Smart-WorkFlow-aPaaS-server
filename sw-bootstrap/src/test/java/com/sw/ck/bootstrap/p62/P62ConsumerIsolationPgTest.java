package com.sw.ck.bootstrap.p62;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 复核02 G5a：消费者隔离演练（真实 PG + 真实进程 + 部署能力开关，非仅权限演示）。
 *
 * <p>方向兼容合同：本阶段不支持旧/新消费者同时处理新类型——新能力默认关闭；停止旧
 * 消费者并核清执行中对象后，协调升级（全部消费者 + 配套 Web）再开启新入口；停新入口
 * 保留新数据向前修复。禁止仅依赖旧枚举抛错实现隔离。</p>
 *
 * <p>本演练以部署开关 {@code sw.bpm.txn-batch.enabled}（受理侧 + 消费侧 handler 注册
 * 同一开关）为隔离门禁，按三个真实进程阶段推进：</p>
 * <ol>
 *   <li><b>旧消费者存活（开关关）</b>：持 form:action:invoke 授权用户 HTTP 发起新类型
 *       批量受理 → 2425 能力门禁拒绝（非 403 权限拒绝）、零批次行落库；旧类型
 *       FLOW_START 由旧消费者按旧语义正常消费（旧消费者存活且未被枚举炸掉）。</li>
 *   <li><b>协调升级</b>：SIGKILL 旧消费者（真实退出），核清在途（无 PROCESSING）；
 *       升级窗口前已受理未消费的旧在途命令由新版本消费者（开关开）承接完成；同授权
 *       用户 HTTP 发起新类型 → 受理成功并由新消费者完成，效果/调用/台账落库。</li>
 *   <li><b>停新入口（开关关）</b>：新类型受理再次被能力门禁拒绝；已产生的新类型数据
 *       （批次/效果/调用行）保留可查，历史连续。</li>
 * </ol>
 *
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62ConsumerIsolationPgTest \
 *   -Dp62.isolation.drill=true -Dp62.runId=&lt;runId&gt; -Dp62.evidence.dir=&lt;绝对路径&gt; \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G5a 消费者隔离演练（能力开关+真实进程；手动：-Dp62.isolation.drill=true）")
@EnabledIfSystemProperty(named = "p62.isolation.drill", matches = "true")
class P62ConsumerIsolationPgTest {

    private static final Long USER = 92301L;
    private static final String BEARER = "Bearer test_" + USER;

    private static EmbeddedPostgres pg;
    private static String pgUrl;
    private static JdbcTemplate jdbc;
    private static Path EVIDENCE;
    private static String runId;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    @BeforeAll
    void boot() throws Exception {
        runId = System.getProperty("p62.runId");
        String dir = System.getProperty("p62.evidence.dir");
        if (runId == null || runId.isBlank() || dir == null || dir.isBlank()) {
            throw new IllegalStateException(
                    "必须提供 -Dp62.runId 与 -Dp62.evidence.dir（固定 runId 不可覆盖采集目录）");
        }
        EVIDENCE = Path.of(dir);
        Files.createDirectories(EVIDENCE);
        pg = EmbeddedPostgres.builder().start();
        pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort()
                + "/postgres?stringtype=unspecified&user=postgres&password=postgres";
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(pgUrl));
        jdbc.execute("SELECT 1");
        System.out.println("[P62-EV] g5a orchestrator pid=" + ProcessHandle.current().pid()
                + " pgPort=" + pg.getPort() + " runId=" + runId);
    }

    @AfterAll
    void tearDown() {
        try {
            if (pg != null) {
                pg.close();
            }
        } catch (Exception ignored) {
            // 关闭容错
        }
    }

    @Test
    @DisplayName("消费者隔离：旧消费者存活期新类型零受理(2425)→退出核清→协调开启受理→停新入口保留数据")
    void consumerIsolationAcrossCoordinatedUpgrade() throws Exception {
        String testClasspath = System.getProperty("java.class.path");
        String javaBin = ProcessHandle.current().info().command().orElse("java");

        // ============ 阶段一：旧版本进程（开关关）存活 ============
        Process old = fork(javaBin, testClasspath, "seed-isolation", false, "iso-old-ready.txt");
        long pidOld = old.pid();
        Path oldReady = EVIDENCE.resolve("iso-old-ready.txt");
        waitFile(oldReady, 300_000L, "旧版本进程就绪（iso-old-ready.txt）");
        String[] oldManifest = parseReady(oldReady);
        int portOld = Integer.parseInt(oldManifest[0]);
        String actionId = oldManifest[1];
        String recordId1 = oldManifest[3];
        long legacy1Id = Long.parseLong(oldManifest[5]);
        write("phase1-old-consumer-alive.txt", "pid=" + pidOld + " port=" + portOld
                + " batchEnabled=false actionId=" + actionId + " legacy1CommandId=" + legacy1Id + "\n");

        // 1a. 旧消费者按旧语义消费旧类型（存活且不受新类型影响）
        waitCommandStatus(legacy1Id, "COMPLETED", 180_000L,
                "旧消费者消费旧类型 FLOW_START（存活证明）");
        write("phase1-legacy-consumed-by-old.txt",
                "legacy1CommandId=" + legacy1Id + " status=COMPLETED byPid=" + pidOld + "\n");

        // 1b. 持授权用户 HTTP 发起新类型：能力门禁拒绝（2425，非权限 403）
        String batchKey1 = "iso-batch-" + runId + "-p1";
        String body1 = batchBody(batchKey1, actionId, "it-p1", recordId1);
        HttpResponse<String> resp1 = postBatch(portOld, body1);
        long batchesAfterDeny = countRows("sw_bpm_command_batch");
        assertThat(codeOf(resp1.body())).as("能力门禁拒绝码 2425").isEqualTo(2425);
        assertThat(batchesAfterDeny).as("旧消费者存活期新类型零受理").isZero();
        write("phase1-new-type-denied.txt",
                "httpStatus=" + resp1.statusCode() + "\nrawResponse=" + resp1.body()
                        + "\nbatches=" + batchesAfterDeny
                        + " identity=authorized user " + USER + " (form:action:invoke via role 2)\n");

        // ============ 阶段二：协调升级 ============
        old.destroyForcibly();
        boolean oldExited = old.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(oldExited).as("旧消费者已真实退出").isTrue();
        long inFlight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE status='PROCESSING'", Long.class);
        assertThat(inFlight).as("旧消费者退出后在途核清（无 PROCESSING）").isZero();
        write("phase2-old-exited-inflight-clear.txt", "pid=" + pidOld
                + " exitCode=" + old.exitValue() + " processingRows=" + inFlight + "\n");

        // 升级窗口在途：克隆真实 FLOW_START 行形态插入一条"已受理未消费"旧命令
        long legacy2Id = insertLegacyInFlight(legacy1Id, recordId(oldManifest, 1));
        String pendingLegacy2 = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id=?", String.class, legacy2Id);
        assertThat(pendingLegacy2).isEqualTo("PENDING");
        write("phase2-legacy-inflight.txt", "legacy2CommandId=" + legacy2Id
                + " status=PENDING (克隆真实队列行形态，模拟升级窗口前已受理在途)\n");

        // 新版本消费者（开关开）承接旧在途
        Process upgraded = fork(javaBin, testClasspath, "plain", true, "iso-new-ready.txt");
        long pidNew = upgraded.pid();
        Path newReady = EVIDENCE.resolve("iso-new-ready.txt");
        waitFile(newReady, 300_000L, "新版本消费者就绪（iso-new-ready.txt）");
        int portNew = Integer.parseInt(parseReady(newReady)[0]);
        waitCommandStatus(legacy2Id, "COMPLETED", 180_000L,
                "新版本消费者承接旧在途 FLOW_START");
        write("phase2-legacy-inflight-taken-over.txt", "legacy2CommandId=" + legacy2Id
                + " status=COMPLETED byPid=" + pidNew + " batchEnabled=true\n");

        // 协调开启后：同授权用户新类型受理成功并被新消费者完成
        String batchKey2 = "iso-batch-" + runId + "-p2";
        String body2 = batchBody(batchKey2, actionId, "it-p2", recordId(oldManifest, 1));
        HttpResponse<String> resp2 = postBatch(portNew, body2);
        assertThat(codeOf(resp2.body())).as("协调开启后受理成功").isZero();
        long batchId = jdbc.queryForObject(
                "SELECT id FROM sw_bpm_command_batch WHERE batch_key=?", Long.class, batchKey2);
        waitBatchCompleted(batchKey2, 180_000L);
        long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref = ?",
                Long.class, "BATCH:" + batchKey2);
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:"
                        + batchKey2 + ":%'", Long.class);
        assertThat(effects).as("新类型效果恰 1").isEqualTo(1L);
        assertThat(invocations).as("新类型调用恰 1").isEqualTo(1L);
        write("phase2-new-type-accepted-consumed.txt", "httpStatus=" + resp2.statusCode()
                + "\nrawResponse=" + resp2.body() + "\nbatchId=" + batchId
                + " batch=COMPLETED item=SUCCEEDED effects=" + effects + " invocations=" + invocations
                + " byPid=" + pidNew + "\n");

        // ============ 阶段三：停新入口，保留新数据向前修复 ============
        upgraded.destroyForcibly();
        assertThat(upgraded.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
                .as("新版本消费者退出").isTrue();
        Process rolledBack = fork(javaBin, testClasspath, "plain", false, "iso-off-ready.txt");
        Path offReady = EVIDENCE.resolve("iso-off-ready.txt");
        waitFile(offReady, 300_000L, "停新入口进程就绪（iso-off-ready.txt）");
        int portOff = Integer.parseInt(parseReady(offReady)[0]);
        String batchKey3 = "iso-batch-" + runId + "-p3";
        String body3 = batchBody(batchKey3, actionId, "it-p3", recordId(oldManifest, 2));
        HttpResponse<String> resp3 = postBatch(portOff, body3);
        assertThat(codeOf(resp3.body())).as("停新入口后新类型再次被能力门禁拒绝").isEqualTo(2425);
        long batchesKept = countRows("sw_bpm_command_batch");
        long effectsKept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref = ?",
                Long.class, "BATCH:" + batchKey2);
        long invocationsKept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:"
                        + batchKey2 + ":%'", Long.class);
        assertThat(batchesKept).as("新数据保留（批次行）").isEqualTo(1L);
        assertThat(effectsKept).as("新数据保留（效果行）").isEqualTo(1L);
        assertThat(invocationsKept).as("新数据保留（调用行）").isEqualTo(1L);
        write("phase3-new-entry-stopped-data-kept.txt", "httpStatus=" + resp3.statusCode()
                + "\nrawResponse=" + resp3.body() + "\nbatches=" + batchesKept
                + " effects=" + effectsKept + " invocations=" + invocationsKept
                + " (停新入口保留新数据，历史连续，向前修复)\n");

        rolledBack.destroy();
        assertThat(rolledBack.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        System.out.println("[P62-EV] g5a.isolation phase1 deny=2425 batches=0; phase2 takeover+accepted"
                + " effects=1; phase3 deny=2425 kept=1/1/1");
    }

    // ==================== 进程/HTTP/读回辅助 ====================

    private Process fork(String javaBin, String classpath, String mode, boolean batchEnabled,
                         String readyFile) throws Exception {
        return new ProcessBuilder(javaBin, "-Xmx1g", "-cp", classpath,
                "com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker", pgUrl, EVIDENCE.toString(),
                mode, runId, readyFile, String.valueOf(batchEnabled))
                .redirectErrorStream(true)
                .redirectOutput(EVIDENCE.resolve("worker-" + mode + "-" + readyFile + ".log").toFile())
                .start();
    }

    private void waitFile(Path file, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !Files.exists(file)) {
            Thread.sleep(500);
        }
        assertThat(Files.exists(file)).as(what).isTrue();
    }

    /** ready 文件字段：port, actionId, recordIds[...], legacy1CommandId。 */
    /** ready 字段 tolerant 解析：seed-isolation 含完整 manifest；plain 仅 pid/port/at/state。 */
    private String[] parseReady(Path ready) throws Exception {
        String content = Files.readString(ready, StandardCharsets.UTF_8);
        String port = field(content, "port");
        String actionId = optionalField(content, "actionId");
        String recordIds;
        Matcher ids = Pattern.compile("recordIds=(\\[[^\\]]*\\])").matcher(content);
        if (ids.find()) {
            recordIds = ids.group(1);
        } else {
            recordIds = "[]";
        }
        String legacy = optionalField(content, "legacy1CommandId");
        return new String[]{port, actionId, recordIds,
                recordIds.startsWith("[") && recordIds.length() > 2 ? firstRecord(recordIds) : "",
                "legacy", legacy};
    }

    private String optionalField(String content, String key) {
        Matcher m = Pattern.compile(key + "=([^ \\n]+)").matcher(content);
        return m.find() ? m.group(1).trim() : "";
    }

    private String firstRecord(String recordIds) {
        return recordId(recordIds, 0);
    }

    private String recordId(String[] manifest, int index) {
        return recordId(manifest[2], index);
    }

    private String recordId(String recordIds, int index) {
        Matcher m = Pattern.compile("\\[([^\\]]*)\\]").matcher(recordIds);
        assertThat(m.find()).as("recordIds 形态合法").isTrue();
        return m.group(1).split(",\\s*")[index].trim();
    }

    private String field(String content, String key) {
        Matcher m = Pattern.compile(key + "=([^ \\n]+)").matcher(content);
        assertThat(m.find()).as("ready 字段存在: " + key).isTrue();
        return m.group(1).trim();
    }

    private long insertLegacyInFlight(long sourceId, String recordId) {
        long newId = System.currentTimeMillis() * 1000L + 7L;
        jdbc.update("INSERT INTO sw_bpm_command (id, tenant_id, command_key, command_type, channel,"
                        + " status, payload, retry_count, initiator_id, logical_command_id,"
                        + " payload_fingerprint, tier, completion_point, next_retry_at)"
                        + " SELECT ?, tenant_id, ?, command_type, channel, 'PENDING',"
                        + " regexp_replace(payload, '\"recordId\":\"[^\"]*\"', ?, 1, 1), 0,"
                        + " initiator_id, ?, payload_fingerprint, tier, completion_point, NULL"
                        + " FROM sw_bpm_command WHERE id=?",
                newId, "FLOW_START:iso-" + runId + "-inflight",
                "\"recordId\":\"" + recordId + "\"",
                "FLOW_START:" + recordId, sourceId);
        return newId;
    }

    private String batchBody(String batchKey, String actionId, String itemKey, String recordId) {
        return "{\"batchKey\":\"" + batchKey + "\",\"actionId\":\"" + actionId
                + "\",\"items\":[{\"itemKey\":\"" + itemKey + "\",\"recordId\":\"" + recordId
                + "\",\"quantity\":\"1\"}]}";
    }

    private HttpResponse<String> postBatch(int port, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/workflow/txn-batch"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", BEARER)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static int codeOf(String responseBody) {
        Matcher m = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)").matcher(responseBody);
        assertThat(m.find()).as("R 响应含 code 字段: " + responseBody).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private void waitCommandStatus(long commandId, String status, long timeoutMs,
                                   String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String actual = null;
        while (System.currentTimeMillis() < deadline) {
            actual = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command WHERE id=?", String.class, commandId);
            if (status.equals(actual)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError(what + " 超时: commandId=" + commandId + " 期望=" + status
                + " 实际=" + actual);
    }

    private void waitBatchCompleted(String batchKey, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command_batch WHERE batch_key=?", String.class, batchKey);
            if ("COMPLETED".equals(status)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("批次未收敛: " + batchKey + " 状态=" + status);
    }

    private long countRows(String table) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    private void write(String fileName, String content) throws Exception {
        Files.writeString(EVIDENCE.resolve(fileName), content + "at=" + LocalDateTime.now() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
