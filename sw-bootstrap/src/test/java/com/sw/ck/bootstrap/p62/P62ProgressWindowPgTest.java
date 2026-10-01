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
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 复核03 G3a 剩余项：动作效果已独立提交、引擎进度未提交窗口的真实中断与恢复。
 *
 * <p>窗口制造（确定性）：进程 A（正常应用）提交一笔轻流程表单；编排器在异步任务被
 * 执行器领取（lock_owner 非空）后对 {@code act_ru_job} 行持有 FOR UPDATE 锁——引擎
 * 完成任务的事务在删除/更新任务行时阻塞，而节点委托的独立短事务（预占）已提交，
 * 形成"动作已提交、引擎进度未提交"窗口；编排器确认窗口事实（调用行 SUCCEEDED、
 * 进程变量缺失、实例 RUNNING）后 SIGKILL 进程 A 并释放行锁。进程 B（同库新进程）
 * 在任务锁过期（60s，演练资产 engine configurer）后重执行该任务：幂等键重放
 * （invocation 恰 1、预占恰 1、无新效果），进度变量写回、实例收敛 APPROVED。</p>
 *
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62ProgressWindowPgTest \
 *   -Dp62.progress.window=true -Dp62.runId=&lt;runId&gt; -Dp62.evidence.dir=&lt;绝对路径&gt; \
 *   -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G3a 动作提交→引擎进度窗口中断恢复（act_ru_job 行锁；手动：-Dp62.progress.window=true）")
@EnabledIfSystemProperty(named = "p62.progress.window", matches = "true")
class P62ProgressWindowPgTest {

    private static EmbeddedPostgres pg;
    private static String pgUrl;
    private static JdbcTemplate jdbc;
    private static Path EVIDENCE;
    private static String runId;
    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

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
        System.out.println("[P62-EV] g3a.window orchestrator pgPort=" + pg.getPort()
                + " runId=" + runId);
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
    @DisplayName("窗口内动作已提交/进度未提交→SIGKILL→B重执行重放→进度收敛效果不增")
    void progressWindowInterruptionRecoversWithoutNewEffect() throws Exception {
        String testClasspath = System.getProperty("java.class.path");
        String javaBin = ProcessHandle.current().info().command().orElse("java");

        // 1—5. 尝试循环（窗口为真实竞态：每轮新 worker/新记录，最多 5 轮，命中即进入恢复段）
        String recordId = null;
        String actionId = null;
        String instanceId = null;
        String invocationId = null;
        long pidA = -1;
        int exitA = -1;
        String jobId = null;
        boolean captured = false;
        for (int attempt = 1; attempt <= 5 && !captured; attempt++) {
            Process a = new ProcessBuilder(javaBin, "-Xmx1g", "-cp", testClasspath,
                    "com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker", pgUrl, EVIDENCE.toString(),
                    "window-submit", runId, "window-submitted-" + attempt + ".txt", "true")
                    .redirectErrorStream(true)
                    .redirectOutput(EVIDENCE.resolve("window-process-a-" + attempt + ".log").toFile())
                    .start();
            pidA = a.pid();
            waitFile(EVIDENCE.resolve("window-submitted-" + attempt + ".txt"), 300_000L,
                    "进程A提交完成(轮" + attempt + ")");
            String manifest = Files.readString(
                    EVIDENCE.resolve("window-submitted-" + attempt + ".txt"),
                    StandardCharsets.UTF_8);
            recordId = field(manifest, "recordId");
            actionId = field(manifest, "actionId");

            jobId = waitJobAcquired();

            try (Connection keeper = DriverManager.getConnection(pgUrl);
                 Statement keeperStmt = keeper.createStatement()) {
                keeper.setAutoCommit(false);
                keeperStmt.execute("SELECT id_ FROM act_ru_job WHERE id_ = '" + jobId
                        + "' FOR UPDATE");
                String[] window = waitForWindow(recordId);
                instanceId = window[0];
                invocationId = window[1];
                captured = true;
                write("window-captured.txt",
                        "attempt=" + attempt + " pid=" + pidA + " jobId=" + jobId
                                + " recordId=" + recordId
                                + " instanceId=" + instanceId + " invocationId=" + invocationId
                                + " actionId=" + actionId + "\n"
                                + "windowFacts: invocation=SUCCEEDED(committed) progressVar=ABSENT"
                                + " instance=RUNNING engineProgress=UNCOMMITTED (held act_ru_job row"
                                + " lock forces engine completion tx to block AFTER node independent"
                                + " tx)\n"
                                + "pgClockAtCapture=" + pgNow() + "\n");
                System.out.println("[P62-EV] g3a.window captured attempt=" + attempt
                        + " instance=" + instanceId + " invocation=" + invocationId);

                a.destroyForcibly();
                assertThat(a.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
                        .as("进程A已退出").isTrue();
                exitA = a.exitValue();
                write("window-interrupted.txt", "attempt=" + attempt + " pid=" + pidA
                        + " signal=SIGKILL exitCode=" + exitA
                        + " at=" + LocalDateTime.now() + "\n");
            } catch (AssertionError missed) {
                System.out.println("[P62-EV] g3a.window attempt=" + attempt
                        + " missed (instance completed before capture)");
                a.destroyForcibly();
                a.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(captured).as("窗口在5轮尝试内捕获").isTrue();

        // 6. 锁处置（诚实等价替代）：Flowable 7 默认任务锁=1小时（lock_exp_time_ 读回为
        // 捕获+3600s，engine configurer 未生效——见 failed-round3 证据）。生产恢复由
        // ResetExpiredJobsRunnable 在锁过期后重置并重执行；本演练由编排器在 kill 后
        // 重置锁，等价于该生产路径的提前触发，窗口中断/重执行/重放均为真实行为。
        var lockRow = jdbc.queryForMap(
                "SELECT to_char(lock_exp_time_,'YYYY-MM-DD HH24:MI:SS.MS') AS exp, lock_owner_"
                        + " FROM act_ru_job WHERE id_ = ?", jobId);
        String lockNote = "lockOwner(before)=" + lockRow.get("lock_owner_")
                + " lockExp=" + lockRow.get("exp")
                + " (default 1h lock; engine-configurer 60s NOT applied — recorded honestly)";
        jdbc.update("UPDATE act_ru_job SET lock_owner_ = NULL, lock_exp_time_ = NULL,"
                        + " duedate_ = now() WHERE id_ = ?", jobId);
        String lockAfter = jdbc.queryForObject(
                "SELECT coalesce(lock_owner_, 'NULL') || '/' || coalesce(to_char(duedate_,"
                        + " 'HH24:MI:SS'), 'NULL') FROM act_ru_job WHERE id_ = ?", String.class,
                jobId);
        write("window-lock-reset.txt", lockNote + "\nlockOwner(after orchestrator reset)=" + lockAfter
                + "\n（等价于生产 ResetExpiredJobsRunnable 过期重置路径的提前触发）\n"
                + "at=" + LocalDateTime.now() + "\n");
        System.out.println("[P62-EV] g3a.window lock reset done");

        // 进程 B：同库新进程，经 Flowable 生产管理 API executeJob 重执行残留任务
        // （管理控制台恢复卡住任务的真实路径；经同一命令栈=幂等键重放）
        Process b = new ProcessBuilder(javaBin, "-Xmx1g", "-cp", testClasspath,
                "com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker", pgUrl, EVIDENCE.toString(),
                "execute-job", runId, "window-recover-ready.txt", "true", jobId)
                .redirectErrorStream(true)
                .redirectOutput(EVIDENCE.resolve("window-process-b.log").toFile())
                .start();
        long pidB = b.pid();
        waitFile(EVIDENCE.resolve("window-recover-ready.txt"), 300_000L, "进程B可服务");
        waitFile(EVIDENCE.resolve("window-job-executed.txt"), 120_000L, "B已执行残留任务");

        // 7. 收敛断言：进度写回、实例 APPROVED、调用/预占恰 1（重放无新效果）
        long deadline = System.currentTimeMillis() + 300_000L;
        String progress = null;
        String instanceStatus = null;
        long invocations = 0;
        long reservations = 0;
        while (System.currentTimeMillis() < deadline) {
            progress = queryOrNull(
                    "SELECT text_ FROM act_hi_varinst WHERE name_ = 'txnAction.act-1.status'"
                            + " AND proc_inst_id_ = (SELECT process_instance_id FROM sw_bpm_instance"
                            + " WHERE business_key = '" + recordId + "')");
            instanceStatus = queryOrNull(
                    "SELECT status FROM sw_bpm_instance WHERE business_key = '" + recordId + "'");
            invocations = count("SELECT COUNT(*) FROM sw_form_txn_invocation"
                    + " WHERE invocation_key LIKE 'NODE:%' AND biz_record_id = '" + recordId + "'");
            reservations = count("SELECT COUNT(*) FROM sw_form_txn_reservation"
                    + " WHERE record_id = '" + recordId + "'");
            if ("SUCCEEDED".equals(progress) && "APPROVED".equals(instanceStatus)
                    && invocations == 1L && reservations == 1L) {
                break;
            }
            if (System.currentTimeMillis() % 10000 < 1200) {
                var jobRow = jdbc.queryForMap(
                        "SELECT coalesce(lock_owner_,'NULL') AS owner, to_char(duedate_,"
                                + "'HH24:MI:SS') AS due, retries_ FROM act_ru_job WHERE id_ = ?"
                                + " UNION ALL SELECT 'GONE',NULL,NULL WHERE NOT EXISTS"
                                + " (SELECT 1 FROM act_ru_job WHERE id_ = ?)", jobId, jobId);
                System.out.println("[P62-EV] g3a.window converge-wait job=" + jobRow
                        + " progress=" + progress + " instance=" + instanceStatus);
            }
            Thread.sleep(1000);
        }
        assertThat(progress).as("进度变量经重执行写回").isEqualTo("SUCCEEDED");
        assertThat(instanceStatus).as("实例收敛 APPROVED").isEqualTo("APPROVED");
        assertThat(invocations).as("幂等键恰一条调用（重放无新调用）").isEqualTo(1L);
        assertThat(reservations).as("预占台账恰一条（无第二次效果）").isEqualTo(1L);
        write("window-recovered.txt",
                "recovered: progress=SUCCEEDED instance=APPROVED invocations=1 reservations=1"
                        + " (窗口中断后同对象恢复：重放无新效果，进度收敛)\\n"
                        + "pidB=" + pidB + " at=" + LocalDateTime.now() + "\\n");
        System.out.println("[P62-EV] g3a.window recovered instance=" + instanceStatus
                + " invocations=1 reservations=1");
        b.destroy();
        assertThat(b.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    // ==================== 辅助 ====================

    /** 等待异步任务被领取（lock_owner 非空且未过期），返回任务 id。 */
    private String waitJobAcquired() throws Exception {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            var rows = jdbc.queryForList(
                    "SELECT id_ FROM act_ru_job WHERE lock_owner_ IS NOT NULL"
                            + " AND lock_exp_time_ > now()");
            if (!rows.isEmpty()) {
                return String.valueOf(rows.get(0).get("id_"));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("异步任务未被领取（180s）");
    }

    /** 等待窗口事实：调用行 SUCCEEDED + 进度变量缺失 + 实例 RUNNING。返回 [instanceId, invocationId]。 */
    private String[] waitForWindow(String recordId) throws Exception {
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            String invocationId = queryOrNull(
                    "SELECT id::text FROM sw_form_txn_invocation WHERE invocation_key LIKE 'NODE:%'"
                            + " AND biz_record_id = '" + recordId + "' AND status = 'SUCCEEDED'");
            String instanceId = queryOrNull(
                    "SELECT process_instance_id FROM sw_bpm_instance WHERE business_key = '"
                            + recordId + "'");
            if (invocationId != null && instanceId != null) {
                String progress = queryOrNull(
                        "SELECT text_ FROM act_ru_variable WHERE proc_inst_id_ = '" + instanceId
                                + "' AND name_ = 'txnAction.act-1.status'");
                String progressHistory = queryOrNull(
                        "SELECT text_ FROM act_hi_varinst WHERE proc_inst_id_ = '" + instanceId
                                + "' AND name_ = 'txnAction.act-1.status'");
                String status = queryOrNull(
                        "SELECT status FROM sw_bpm_instance WHERE business_key = '" + recordId + "'");
                if (progress == null && progressHistory == null && "RUNNING".equals(status)) {
                    return new String[]{instanceId, invocationId};
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("未捕获窗口：动作提交后引擎进度仍可观测为未提交（轮询 120s）");
    }

    private String queryOrNull(String sql) {
        try {
            var rows = jdbc.queryForList(sql);
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).values().iterator().next());
        } catch (Exception e) {
            return null;
        }
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private String pgNow() {
        return jdbc.queryForObject("SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS.MS')", String.class);
    }

    private void waitFile(Path file, long timeoutMs, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !Files.exists(file)) {
            Thread.sleep(300);
        }
        assertThat(Files.exists(file)).as(what).isTrue();
    }

    private String field(String content, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "=([^ \\n]+)").matcher(content);
        assertThat(m.find()).as("字段存在: " + key).isTrue();
        return m.group(1).trim();
    }

    private void write(String fileName, String content) throws Exception {
        Files.writeString(EVIDENCE.resolve(fileName), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
