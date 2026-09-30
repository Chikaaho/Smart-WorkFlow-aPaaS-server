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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 S6 补证（G2）：隔离进程真实中断与新进程恢复演练（手动门控运行）。
 *
 * <pre>
 * MAVEN_OPTS="-Xmx2g" mvn -pl sw-bootstrap -am test -Dtest=P62RecoveryDrillOrchestrationTest \
 *   -Dp62.recovery.drill=true -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>流程：进程 A（本测试 JVM，dispatch 轮询设为 24h 停摆）受理 100 条批次命令；
 * fork 独立 JVM 进程 B（{@link P62RecoveryDrillWorker}，正常调度）连同一持久库；
 * 以 B 的 READY 标记为"新进程可服务"起点计时，断言 100 条全部合法收敛 ≤120s、
 * 零重复效果；保留 A/B PID、源码身份、时间与 DB 读回。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G2 真实双进程恢复演练（手动：-Dp62.recovery.drill=true）")
@EnabledIfSystemProperty(named = "p62.recovery.drill", matches = "true")
class P62RecoveryDrillOrchestrationTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 92301L;
    private static final Path EVIDENCE = Path.of("build", "p62-recovery-drill");

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String stockTable;

    private TxnBatchService batchService;
    private BpmCommandMapper commandMapper;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = P62RecoveryDrillWorker.baseProps(pgUrl);
        // 进程 A：dispatch 停摆（真实中断语义——受理后原进程不再消费任何命令）
        props.put("sw.bpm.command.poll-interval-millis", String.valueOf(24 * 3600 * 1000L));
        props.put("sw.bpm.command.p0-poll-interval-millis", String.valueOf(24 * 3600 * 1000L));
        props.put("sw.bpm.command.reconcile-interval-millis", String.valueOf(24 * 3600 * 1000L));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-drill-a", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62DrillALoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        batchService = app.getBean(TxnBatchService.class);
        commandMapper = app.getBean(BpmCommandMapper.class);
        Files.createDirectories(EVIDENCE);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'drill-operator', 'seed-not-a-login-secret', '恢复演练员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92301, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        asOperator(() -> {
            FormDefDTO draft = formDefService().createDraft("p62_drill_stock", "恢复演练库存", null, null);
            formDefService().saveConfig(draft.getId(), stockDefinition());
            formDefService().publish(draft.getId());
            return null;
        });
        stockTable = asOperator(() -> formDefService().getFormDefByKey("p62_drill_stock"))
                .getPhysicalTableName();
        System.out.println("[P62-EV] recovery boot ok pid=" + ProcessHandle.current().pid()
                + " pgPort=" + pg.getPort());
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
    @DisplayName("100 条已受理命令：新进程可服务起 ≤120s 全部合法收敛、零重复效果")
    void recoveryAcrossRealProcessBoundary() throws Exception {
        // 1. 进程 A 受理 100 条单项批次（dispatch 停摆，全部保持 PENDING）
        List<Long> commandIds = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            String record = seedRecord("DRILL-" + i, "1000");
            TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
            request.setBatchKey("drill-batch-" + i);
            request.setActionId(actionId());
            request.setItems(List.of(item("drill-" + i, record, "1")));
            TxnBatchView view = asOperator(() -> batchService.submit(request));
            commandIds.add(view.getCommandId());
        }
        long pending = pendingCount(commandIds);
        assertThat(pending).as("进程 A 停摆：100 条全部保持 PENDING").isEqualTo(100L);

        // 2. fork 独立 JVM 进程 B（正常调度，同一持久库）
        String testClasspath = System.getProperty("java.class.path");
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-Xmx1g",
                "-cp", testClasspath, "com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker",
                "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified",
                EVIDENCE.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(EVIDENCE.resolve("worker.log").toFile());
        Process worker = builder.start();
        long workerPid = worker.pid();

        // 3. 等待 B 可服务（READY 标记 = context 就绪）
        Path ready = EVIDENCE.resolve("worker-ready.txt");
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline && !Files.exists(ready)) {
            Thread.sleep(300);
        }
        assertThat(Files.exists(ready)).as("新进程可服务（READY）").isTrue();

        // 4. 从 B 可服务起计时：100 条全部 COMPLETED ≤120s
        long convergeStart = System.currentTimeMillis();
        long convergeDeadline = convergeStart + 120_000L;
        int completed = 0;
        while (System.currentTimeMillis() < convergeDeadline) {
            completed = 0;
            for (Long id : commandIds) {
                BpmCommand c = asOperator(() -> commandMapper.selectById(id));
                if (c != null && "COMPLETED".equals(c.getStatus())) {
                    completed++;
                }
            }
            if (completed >= 100) {
                break;
            }
            Thread.sleep(500);
        }
        long elapsed = System.currentTimeMillis() - convergeStart;

        long effects = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref LIKE 'BATCH:drill-batch-%'",
                Long.class);
        long invocations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_form_txn_invocation WHERE invocation_key LIKE 'BATCH:drill-batch-%'",
                Long.class);
        assertThat(elapsed).as("收敛 %dms ≤120s", elapsed).isLessThanOrEqualTo(120_000L);
        assertThat(completed).isEqualTo(100);
        assertThat(effects).as("效果权威恰 100（零重复）").isEqualTo(100L);
        assertThat(invocations).as("调用记录恰 100（零重复）").isEqualTo(100L);

        String summary = "recovery elapsedMs=" + elapsed + " completed=100/100 effects=100"
                + " invocations=100 workerPid=" + workerPid
                + " orchestratorPid=" + ProcessHandle.current().pid()
                + " classpath-source=fe-current jvm=" + System.getProperty("java.version");
        Files.writeString(EVIDENCE.resolve("recovery.txt"), summary + "\n");
        System.out.println("[P62-EV] g2.recovery " + summary);
        worker.destroy();
        worker.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ==================== 辅助 ====================

    private long pendingCount(List<Long> commandIds) {
        long n = 0;
        for (Long id : commandIds) {
            BpmCommand c = asOperator(() -> commandMapper.selectById(id));
            if (c != null && "PENDING".equals(c.getStatus())) {
                n++;
            }
        }
        return n;
    }

    private String cachedActionId;

    private String actionId() {
        if (cachedActionId != null) {
            return cachedActionId;
        }
        String formId = asOperator(() -> formDefService().getFormDefByKey("p62_drill_stock").getId());
        cachedActionId = asOperator(() -> {
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = actionService().create(formId, new TxnActionSaveRequest(
                    "drill_reserve", "恢复演练预占", "RESERVE", null, cfg)).id();
            actionService().publish(id);
            return id;
        });
        return cachedActionId;
    }

    private FormDefService formDefService() {
        return app.getBean(FormDefService.class);
    }

    private TxnActionService actionService() {
        return app.getBean(TxnActionService.class);
    }

    private String seedRecord(String material, String available) {
        return asOperator(() -> app.getBean(FormSubmitService.class).submitForm("p62_drill_stock",
                data("material", material, "qty_available", available, "qty_reserved", "0"),
                null, null, null));
    }

    private TxnBatchSubmitRequest.Item item(String key, String recordId, String quantity) {
        TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
        item.setItemKey(key);
        item.setRecordId(recordId);
        item.setQuantity(quantity);
        return item;
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new HashMap<>();
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

    private static String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"恢复演练库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }
}
