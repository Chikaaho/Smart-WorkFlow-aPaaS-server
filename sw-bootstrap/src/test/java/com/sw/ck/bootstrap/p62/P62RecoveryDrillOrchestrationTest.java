package com.sw.ck.bootstrap.p62;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 复核02 G2a：隔离消费进程 A 真实中断（SIGKILL）与新进程 B 恢复演练（手动门控）。
 *
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62RecoveryDrillOrchestrationTest \
 *   -Dp62.recovery.drill=true -Dp62.runId=&lt;runId&gt; -Dp62.evidence.dir=&lt;绝对路径&gt; \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>流程：编排器持有隔离 PG；fork 进程 A（真实应用，正常调度语义）受理 100 条批次
 * 命令；对 A 执行 destroyForcibly（SIGKILL）并留 pid/存活/退出证据（真实进程中断，
 * 非停轮询）；fork 同库进程 B（正常调度），以 B READY 为"新进程可服务"起点计时，
 * 断言 100 条已受理命令全部合法收敛 ≤120s、零重复效果；命令/调用/台账与库指纹
 * 全部读回留证。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G2a 真实中断恢复演练（SIGKILL A→B 恢复；手动：-Dp62.recovery.drill=true）")
@EnabledIfSystemProperty(named = "p62.recovery.drill", matches = "true")
class P62RecoveryDrillOrchestrationTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 92301L;
    private static Path EVIDENCE;

    private static EmbeddedPostgres pg;
    private static String pgUrl;
    private static JdbcTemplate jdbc;

    @BeforeAll
    void boot() throws Exception {
        String runId = System.getProperty("p62.runId");
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
        System.out.println("[P62-EV] g2a orchestrator pid=" + ProcessHandle.current().pid()
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
    @DisplayName("G2a：消费进程A真实中断(SIGKILL)，同库新进程B可服务起≤120s收敛100条、零重复效果")
    void recoveryAcrossRealProcessInterruption() throws Exception {
        String runId = System.getProperty("p62.runId");
        String testClasspath = System.getProperty("java.class.path");
        String javaBin = ProcessHandle.current().info().command().orElse("java");

        // 1. fork 进程 A（正常应用语义）：受理 100 条 → 真实消费 20 → 1 条在途 claim → 驻留
        Process a = new ProcessBuilder(javaBin, "-Xmx1g", "-cp", testClasspath,
                "com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker", pgUrl, EVIDENCE.toString(),
                "accept", runId, "a-accepted-readyflag.txt", "true")
                .redirectErrorStream(true)
                .redirectOutput(EVIDENCE.resolve("process-a.log").toFile())
                .start();
        long pidA = a.pid();
        Path accepted = EVIDENCE.resolve("a-accepted.txt");
        long waitDeadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < waitDeadline && !Files.exists(accepted)) {
            Thread.sleep(500);
        }
        assertThat(Files.exists(accepted)).as("进程A受理完成（a-accepted.txt）").isTrue();
        String acceptedContent = Files.readString(accepted, StandardCharsets.UTF_8);
        System.out.println("[P62-EV] g2a a-accepted: " + acceptedContent.split("\n")[0]);
        List<Long> commandIds = parseCommandIds(acceptedContent);

        // 中断前现场快照（库指纹：版本+端口+终态/在途行计数）
        String dbFingerprintBefore = dbFingerprint();
        long completedBeforeKill = countStatus(commandIds, "COMPLETED");
        long inFlightBeforeKill = countStatus(commandIds, "PROCESSING");
        long pendingBeforeKill = countStatus(commandIds, "PENDING");

        // 2. 真实中断：SIGKILL 进程 A 并留退出证据（非停轮询、非优雅关闭）
        a.destroyForcibly();
        boolean exited = a.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(exited).as("进程A已真实退出").isTrue();
        assertThat(a.isAlive()).as("进程A不再存活").isFalse();
        int exitCodeA = a.exitValue();
        writeNew("a-interrupted.txt", "pid=" + pidA + " signal=SIGKILL(destroyForcibly)"
                + " exited=" + exited + " exitCode=" + exitCodeA
                + " aliveAfter=" + a.isAlive()
                + " at=" + LocalDateTime.now() + "\n"
                + "interruptSnapshot: completed=" + completedBeforeKill
                + " inFlight=" + inFlightBeforeKill + " pending=" + pendingBeforeKill + "\n"
                + "dbFingerprintBefore=" + dbFingerprintBefore + "\n");
        System.out.println("[P62-EV] g2a killed pid=" + pidA + " exitCode=" + exitCodeA
                + " snapshot completed=" + completedBeforeKill
                + " inFlight=" + inFlightBeforeKill + " pending=" + pendingBeforeKill);

        // 3. fork 进程 B（正常调度，同一持久库）：READY=新进程可服务
        Process b = new ProcessBuilder(javaBin, "-Xmx1g", "-cp", testClasspath,
                "com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker", pgUrl, EVIDENCE.toString(),
                "recover", runId, "worker-ready.txt", "true")
                .redirectErrorStream(true)
                .redirectOutput(EVIDENCE.resolve("worker.log").toFile())
                .start();
        long pidB = b.pid();
        Path ready = EVIDENCE.resolve("worker-ready.txt");
        waitDeadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < waitDeadline && !Files.exists(ready)) {
            Thread.sleep(300);
        }
        assertThat(Files.exists(ready)).as("新进程B可服务（worker-ready.txt）").isTrue();

        // 4. 从 B 可服务起计时：100 条全部 COMPLETED ≤120s（含在途租约恢复）
        long convergeStart = System.currentTimeMillis();
        long convergeDeadline = convergeStart + 120_000L;
        int completed = 0;
        while (System.currentTimeMillis() < convergeDeadline) {
            completed = countStatus(commandIds, "COMPLETED");
            if (completed >= 100) {
                break;
            }
            Thread.sleep(500);
        }
        long elapsed = System.currentTimeMillis() - convergeStart;

        // 5. 零重复效果 + 库读回
        long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref LIKE 'BATCH:drill-batch-"
                        + runId + "-%'", Long.class);
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:drill-batch-"
                        + runId + "-%'", Long.class);
        long ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_reservation WHERE id IN ("
                        + "SELECT (result_json->>'reservationId')::text FROM sw_form_txn_invocation"
                        + " WHERE invocation_key LIKE 'BATCH:drill-batch-" + runId + "-%')", Long.class);
        assertThat(elapsed).as("B可服务起收敛 %dms ≤120s", elapsed).isLessThanOrEqualTo(120_000L);
        assertThat(completed).as("100条已受理命令全部收敛").isEqualTo(100);
        assertThat(effects).as("效果权威恰100（零重复）").isEqualTo(100L);
        assertThat(invocations).as("调用记录恰100（重复效果=0）").isEqualTo(100L);
        assertThat(ledgerRows).as("预占台账恰100（幂等键唯一）").isEqualTo(100L);

        writeNew("recovery.txt", "recovery elapsedMs=" + elapsed + " completed=100/100"
                + " effects=100 invocations=100 ledger=100"
                + " (≤120s budget, duplicate-effects=0)\n"
                + "pidA=" + pidA + " exitCodeA=" + exitCodeA + " (SIGKILL)\n"
                + "pidB=" + pidB + " readyFile=worker-ready.txt\n"
                + "dbFingerprintAfter=" + dbFingerprint() + "\n"
                + "jvm=" + System.getProperty("java.version")
                + " runId=" + runId + "\n");
        System.out.println("[P62-EV] g2a.recovery elapsedMs=" + elapsed + " completed=100/100"
                + " duplicate-effects=0 pidB=" + pidB);

        b.destroy();
        assertThat(b.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                .as("进程B清理退出").isTrue();
    }

    // ==================== 辅助 ====================

    private List<Long> parseCommandIds(String acceptedContent) {
        List<Long> ids = new ArrayList<>();
        for (String line : acceptedContent.split("\n")) {
            if (line.startsWith("commandIds=")) {
                for (String token : line.substring("commandIds=".length())
                        .replace("[", "").replace("]", "").split(",\\s*")) {
                    if (!token.isBlank()) {
                        ids.add(Long.parseLong(token.trim()));
                    }
                }
            }
        }
        assertThat(ids).as("受理命令清单100条").hasSize(100);
        return ids;
    }

    private int countStatus(List<Long> commandIds, String status) {
        String in = commandIds.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("0");
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE id IN (" + in + ") AND status = ?",
                Long.class, status);
        return n == null ? 0 : n.intValue();
    }

    /** 库指纹：实例身份 + 版本 + 关键行计数（证明 A/B 同库）。 */
    private String dbFingerprint() {
        String version = jdbc.queryForObject("SELECT current_setting('server_version')", String.class);
        Long port = jdbc.queryForObject("SELECT inet_server_port()", Long.class);
        Long commandRows = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command", Long.class);
        return "pgVersion=" + version + " pgPort=" + port + " commandRows=" + commandRows;
    }

    private void writeNew(String fileName, String content) throws Exception {
        Files.writeString(EVIDENCE.resolve(fileName), content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE);
    }
}
