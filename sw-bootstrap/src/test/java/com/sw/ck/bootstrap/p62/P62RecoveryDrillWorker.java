package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.TxnBatchSubmitRequest;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.queue.CommandDispatcher;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.service.TxnBatchService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.form.txn.model.TxnActionConfig;
import com.sw.ck.form.txn.model.TxnActionSaveRequest;
import com.sw.ck.form.txn.service.TxnActionService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P62 复核02 G2a：真实中断恢复演练进程入口（隔离环境，不直接由 Maven 运行）。
 *
 * <p>由 {@link P62RecoveryDrillOrchestrationTest} 以两个模式 fork：</p>
 * <ul>
 *   <li>{@code accept}：进程 A（正常调度语义但轮询停摆）受理 100 条批次命令，经真实
 *       dispatcher 消费前 20 条（COMPLETED），再真实 claim 1 条不执行（在途 PROCESSING，
 *       模拟被中断的执行中状态），写 {@code a-accepted.txt} 后驻留，由编排器 SIGKILL；</li>
 *   <li>{@code recover}：进程 B（正常调度）连同一持久库，输出 {@code worker-ready.txt}
 *       （pid/port/时刻）即"新进程可服务"，开始领取并恢复剩余命令，由编排器计时收敛。</li>
 * </ul>
 * <pre>
 * java -cp &lt;test classpath&gt; com.sw.ck.bootstrap.p62.P62RecoveryDrillWorker \
 *   &lt;jdbcUrl&gt; &lt;evidenceDir&gt; &lt;accept|recover&gt; &lt;runId&gt;
 * </pre>
 */
public final class P62RecoveryDrillWorker {

    private static final Long TENANT = 0L;
    private static final Long USER = 92301L;
    private static final String FORM_KEY = "p62_drill_stock";

    private P62RecoveryDrillWorker() {
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        Path evidenceDir = Path.of(args[1]);
        String mode = args[2];
        String runId = args.length > 3 ? args[3] : "default";
        String readyFile = args.length > 4 ? args[4] : null;
        boolean batchEnabled = args.length > 5 && Boolean.parseBoolean(args[5]);
        Files.createDirectories(evidenceDir);

        Map<String, Object> props = baseProps(jdbcUrl);
        if ("accept".equals(mode)) {
            // 进程 A：轮询停摆（24h）——受理在事务内完成，命令保持 PENDING；消费由
            // accept 流程经真实 dispatcher 逐条驱动（可精确控制中断现场），不靠调度循环。
            props.put("sw.bpm.command.poll-interval-millis", String.valueOf(24 * 3600 * 1000L));
            props.put("sw.bpm.command.p0-poll-interval-millis", String.valueOf(24 * 3600 * 1000L));
            props.put("sw.bpm.command.reconcile-interval-millis", String.valueOf(24 * 3600 * 1000L));
        }
        // 能力开关（复核02 G5a）：批次受理与消费需显式开启（旧版本语义进程=false）
        props.put("sw.bpm.txn-batch.enabled", String.valueOf(batchEnabled));
        // G5a 隔离演练需要 HTTP debug-auth（编排器以受权用户发起真实请求）
        props.put("sw.security.debug-auth.enabled", "true");

        ConfigurableApplicationContext app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-drill", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62DrillLoginContextProvider", provider);
                    // debug-auth kickOut 走 Redis 缓存：隔离演练无 Redis，替换为无操作缓存
                    context.addBeanFactoryPostProcessor(bf -> {
                        if (bf.containsBeanDefinition("loginUserCacheService")) {
                            ((org.springframework.beans.factory.support.BeanDefinitionRegistry) bf)
                                    .registerBeanDefinition("loginUserCacheService",
                                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                                    P62BudgetMeasurementPgTest.NoopLoginUserCacheService.class));
                        }
                    });
                })
                .run();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(ch.qos.logback.classic.Level.WARN);

        switch (mode) {
            case "accept" -> acceptAndHold(app, evidenceDir, runId);
            case "recover" -> readyAndHold(app, evidenceDir, readyFile);
            case "seed-isolation" -> seedIsolationAndHold(app, evidenceDir, runId, readyFile);
            case "plain" -> readyAndHold(app, evidenceDir, readyFile);
            default -> throw new IllegalArgumentException("未知模式: " + mode);
        }
    }

    /** 进程 A：受理 100 条 → 真实消费 20 条 → 真实 claim 1 条不执行 → 驻留待 SIGKILL。 */
    private static void acceptAndHold(ConfigurableApplicationContext app, Path evidenceDir,
                                      String runId) throws Exception {
        var jdbc = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'drill-operator', 'seed-not-a-login-secret', '恢复演练员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92301, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);

        TxnBatchService batchService = app.getBean(TxnBatchService.class);
        PersistentBpmCommandQueue queue = app.getBean(PersistentBpmCommandQueue.class);
        CommandDispatcher dispatcher = app.getBean(CommandDispatcher.class);

        // 种子（幂等）：表单/动作发布 + 记录
        asOperator(() -> {
            FormDefService formDefService = app.getBean(FormDefService.class);
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "恢复演练库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        String stockTable = asOperator(() -> app.getBean(FormDefService.class)
                .getFormDefByKey(FORM_KEY)).getPhysicalTableName();
        String actionId = asOperator(() -> {
            FormDefService formDefService = app.getBean(FormDefService.class);
            TxnActionService actionService = app.getBean(TxnActionService.class);
            String formId = formDefService.getFormDefByKey(FORM_KEY).getId();
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = actionService.create(formId, new TxnActionSaveRequest(
                    "drill_reserve", "恢复演练预占", "RESERVE", null, cfg)).id();
            actionService.publish(id);
            return id;
        });
        FormSubmitService submitService = app.getBean(FormSubmitService.class);
        List<String> records = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            String recordId = asOperator(() -> submitService.submitForm(FORM_KEY,
                    data("material", "DRILL-" + runId + "-" + records.size(),
                            "qty_available", "1000", "qty_reserved", "0"),
                    null, null, null));
            records.add(recordId);
        }

        // 受理 100 条（dispatch 停摆 → 全部 PENDING；持久受理在提交事务内）
        List<Long> commandIds = new ArrayList<>(100);
        for (int i = 0; i < 100; i++) {
            TxnBatchSubmitRequest request = new TxnBatchSubmitRequest();
            request.setBatchKey("drill-batch-" + runId + "-" + i);
            request.setActionId(actionId);
            TxnBatchSubmitRequest.Item item = new TxnBatchSubmitRequest.Item();
            item.setItemKey("drill-" + runId + "-" + i);
            item.setRecordId(records.get(i));
            item.setQuantity("1");
            request.setItems(List.of(item));
            TxnBatchView view = asOperator(() -> batchService.submit(request));
            commandIds.add(view.getCommandId());
        }

        // 真实消费前 20 条（生产 dispatcher 路径：claim→handle→效果→COMPLETED）
        List<Long> completedByA = new ArrayList<>(20);
        List<Long> claimedByA = new ArrayList<>(1);
        List<CommandEnvelope> firstBatch = claimUntil(queue, commandIds, 21);
        for (int i = 0; i < firstBatch.size(); i++) {
            CommandEnvelope envelope = firstBatch.get(i);
            if (i < 20) {
                asOperator(() -> {
                    dispatcher.dispatchOneForTest(envelope);
                    return null;
                });
                completedByA.add(envelope.getCommandId());
            } else {
                // 第 21 条只 claim 不执行：真实"执行中被中断"现场（租约由 A 持有至进程死亡）
                claimedByA.add(envelope.getCommandId());
            }
        }

        long effectsByA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_effect WHERE biz_ref LIKE 'BATCH:drill-batch-"
                        + runId + "-%'", Long.class);
        String snapshot = "pid=" + ProcessHandle.current().pid()
                + " accepted=100 completedByA=" + completedByA.size()
                + " claimedInFlightByA=" + claimedByA
                + " effectsByA=" + effectsByA
                + " pgPort=" + jdbc.queryForObject(
                        "SELECT inet_server_port()", Long.class)
                + " at=" + java.time.LocalDateTime.now();
        writeNewFile(evidenceDir.resolve("a-accepted.txt"),
                snapshot + "\ncommandIds=" + commandIds + "\nstockTable=" + stockTable
                        + "\nactionId=" + actionId + "\nrunId=" + runId + "\n");
        System.out.println("[P62-EV] g2a.accept " + snapshot);
        // 驻留直至被编排器 SIGKILL（真实中断）
        Thread.sleep(15 * 60 * 1000L);
        app.close();
    }

    /** 领取恰好 count 条已受理命令（轮询等待受理可见）。 */
    private static List<CommandEnvelope> claimUntil(PersistentBpmCommandQueue queue,
                                                    List<Long> commandIds, int count)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        List<CommandEnvelope> claimed = new ArrayList<>(count);
        while (claimed.size() < count && System.currentTimeMillis() < deadline) {
            List<CommandEnvelope> batch = asOperator(() ->
                    queue.claimDue(List.of(CommandChannelEnum.NORMAL), count - claimed.size()));
            for (CommandEnvelope envelope : batch) {
                if (commandIds.contains(envelope.getCommandId()) && claimed.stream().noneMatch(
                        c -> c.getCommandId().equals(envelope.getCommandId()))) {
                    claimed.add(envelope);
                } else {
                    throw new AssertionError("领取到非本轮命令: " + envelope.getCommandId());
                }
            }
            if (claimed.size() < count) {
                Thread.sleep(200);
            }
        }
        if (claimed.size() < count) {
            throw new AssertionError("领取不足 " + count + " 条，仅 " + claimed.size());
        }
        return claimed;
    }

    /** 进程 B/通用：READY 后即开始消费；驻留直至编排器终止。 */
    private static void readyAndHold(ConfigurableApplicationContext app, Path evidenceDir,
                                     String readyFile) throws Exception {
        writeNewFile(evidenceDir.resolve(readyFile == null ? "worker-ready.txt" : readyFile),
                "pid=" + ProcessHandle.current().pid()
                        + " port=" + app.getEnvironment().getProperty("local.server.port")
                        + " at=" + java.time.LocalDateTime.now()
                        + " state=SERVABLE\n");
        System.out.println("[P62-EV] g2a.recover ready");
        Thread.sleep(15 * 60 * 1000L);
        app.close();
    }

    /**
     * G5a 隔离演练旧版本进程（能力开关关）：种子用户/表单/动作/记录，经真实队列 API
     * 入队一条旧类型 FLOW_START（本进程调度器将按旧语义消费），写 manifest 后驻留。
     */
    private static void seedIsolationAndHold(ConfigurableApplicationContext app, Path evidenceDir,
                                             String runId, String readyFile) throws Exception {
        var jdbc = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'iso-operator', 'seed-not-a-login-secret', '隔离演练员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (92301, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        asOperator(() -> {
            FormDefService formDefService = app.getBean(FormDefService.class);
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "隔离演练库存", null, null);
            formDefService.saveConfig(draft.getId(), stockDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
        String actionId = asOperator(() -> {
            FormDefService formDefService = app.getBean(FormDefService.class);
            TxnActionService actionService = app.getBean(TxnActionService.class);
            String formId = formDefService.getFormDefByKey(FORM_KEY).getId();
            TxnActionConfig cfg = new TxnActionConfig();
            cfg.setBalanceField("qty_available");
            cfg.setReservedField("qty_reserved");
            cfg.setExpiresInSeconds(600L);
            String id = actionService.create(formId, new TxnActionSaveRequest(
                    "iso_reserve", "隔离演练预占", "RESERVE", null, cfg)).id();
            actionService.publish(id);
            return id;
        });
        FormSubmitService submitService = app.getBean(FormSubmitService.class);
        List<String> records = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            String recordId = asOperator(() -> submitService.submitForm(FORM_KEY,
                    data("material", "ISO-" + runId + "-" + records.size(),
                            "qty_available", "1000", "qty_reserved", "0"),
                    null, null, null));
            records.add(recordId);
        }
        // 旧类型 FLOW_START 经真实队列 API 入队（旧消费者存活期按旧语义消费）
        PersistentBpmCommandQueue queue = app.getBean(PersistentBpmCommandQueue.class);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        com.sw.ck.bpm.process.queue.CommandEnvelope legacy = new com.sw.ck.bpm.process.queue.CommandEnvelope();
        legacy.setCommandType(com.sw.ck.bpm.process.entity.CommandTypeEnum.FLOW_START);
        legacy.setChannel(CommandChannelEnum.NORMAL);
        legacy.setCommandKey("FLOW_START:iso-" + runId + "-" + records.get(0));
        legacy.setTenantId(TENANT);
        legacy.setInitiatorId(USER);
        legacy.setPayload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "formKey", FORM_KEY, "recordId", records.get(0), "submitter", String.valueOf(USER))));
        Long legacy1Id = tx.execute(status -> queue.enqueue(legacy));

        String readyPath = readyFile == null ? "iso-ready.txt" : readyFile;
        writeNewFile(evidenceDir.resolve(readyPath),
                "pid=" + ProcessHandle.current().pid()
                        + " port=" + app.getEnvironment().getProperty("local.server.port")
                        + " formKey=" + FORM_KEY + " actionId=" + actionId
                        + " recordIds=" + records
                        + " legacy1CommandId=" + legacy1Id
                        + " batchEnabled=false at=" + java.time.LocalDateTime.now() + "\n");
        System.out.println("[P62-EV] g5a.old ready port=" + app.getEnvironment()
                .getProperty("local.server.port") + " legacy1=" + legacy1Id);
        Thread.sleep(15 * 60 * 1000L);
        app.close();
    }

    /** 测试与 worker 共用的基础属性（同一持久库）。 */
    static Map<String, Object> baseProps(String pgUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-drill-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", RsaKeyHolder.RSA_KEY);
        props.put("sw.security.login.digest-secret", "p62-drill-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("logging.level.root", "WARN");
        return props;
    }

    static void writeNewFile(Path file, String content) throws Exception {
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static <T> T asOperator(java.util.concurrent.Callable<T> action) {
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

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"恢复演练库存\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    /** 固定 RSA（worker 与编排进程同值，避免每次生成分叉）。 */
    static final class RsaKeyHolder {
        static final String RSA_KEY = generated();

        private RsaKeyHolder() {
        }

        private static String generated() {
            try {
                java.security.KeyPairGenerator generator =
                        java.security.KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                return java.util.Base64.getEncoder()
                        .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
