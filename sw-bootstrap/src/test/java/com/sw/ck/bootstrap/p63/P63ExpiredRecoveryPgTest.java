package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G05b EXPIRED 恢复契约（审查03§4 修正后）真实持久集成（Embedded PG + 全量应用上下文
 * + 真实 dispatcher）：
 * <ul>
 *   <li>准入截止两条顺序都守准入：截止已过时 claimDue 领取被拒（dispatcher 先行），
 *       expireDue 收敛 EXPIRED（对账先行），不依赖对账抢先改状态才阻止执行；</li>
 *   <li>原 EXPIRED 命令行的状态/结果/失败原因/审计永不改写；原用户同键同载荷重提交生成
 *       可辨认的恢复命令新行（commandKey 追加 :R1 代数）；异载荷 2426 拒绝；</li>
 *   <li>恢复命令真实消费成功一次；恢复行也过期时生成下一代 :R2，每代原记录独立保留；
 *       已生效后同载荷重放只幂等命中，不自动重发。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G05b EXPIRED 恢复契约（原终态保留+恢复链+领取守准入）PG 端到端")
class P63ExpiredRecoveryPgTest {

    private static final Long TENANT = 0L;
    private static final Long INITIATOR = 91401L;
    private static final Long APPROVER = 91402L;
    private static final String FORM_KEY = "p63_expired_form";

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private com.sw.ck.form.service.FormDefService formDefService;
    private com.sw.ck.form.service.FormSubmitService submitService;
    private com.sw.ck.bpm.process.service.BpmProcessDefService processDefService;
    private CommandAcceptService acceptService;
    private PersistentBpmCommandQueue commandQueue;
    private com.sw.ck.bpm.process.queue.TieredCommandReconcileJob reconcile;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-expired-recovery-jwt-secret-0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-expired-recovery-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "false");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-expired-recovery", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63ExpiredRecoveryLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(com.sw.ck.form.service.FormDefService.class);
        submitService = app.getBean(com.sw.ck.form.service.FormSubmitService.class);
        processDefService = app.getBean(com.sw.ck.bpm.process.service.BpmProcessDefService.class);
        acceptService = app.getBean(CommandAcceptService.class);
        commandQueue = app.getBean(PersistentBpmCommandQueue.class);
        reconcile = app.getBean(com.sw.ck.bpm.process.queue.TieredCommandReconcileJob.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-expiry-initiator', 'seed-not-a-login-secret', '过期恢复发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-expiry-approver', 'seed-not-a-login-secret', '过期恢复审批人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91403, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91404, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER);
        seedFormAndProcess();
        System.out.println("[P63-EV] expired.recovery boot ok pgPort=" + pg.getPort());
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

    private void seedFormAndProcess() {
        asUser(INITIATOR, () -> {
            var draft = formDefService.createDraft(FORM_KEY, "P63过期恢复表单", null, null);
            formDefService.saveConfig(draft.getId(),
                    "{\"schemaVersion\":1,\"title\":\"P63过期恢复表单\",\"fields\":["
                            + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false}]}");
            formDefService.publish(draft.getId());
            var def = processDefService.createDef("P63过期恢复流程-" + System.nanoTime(), FORM_KEY);
            var parsed = com.alibaba.fastjson2.JSON.parseObject(
                    processGraph(), com.sw.ck.bpm.api.dto.ProcessGraph.class);
            processDefService.saveDraftGraph(def.getId(), com.alibaba.fastjson2.JSON.toJSONString(parsed));
            processDefService.publish(def.getId());
            return null;
        });
    }

    private String processGraph() {
        return "{\"processKey\":null,\"name\":\"P63过期恢复流程\",\"formKey\":\"" + FORM_KEY + "\","
                + "\"elements\":["
                + "{\"id\":\"n_start\",\"kind\":\"node\",\"type\":\"START\",\"config\":{},\"x\":100,\"y\":300},"
                + "{\"id\":\"n_a1\",\"kind\":\"node\",\"type\":\"APPROVAL\",\"config\":{\"name\":\"审批\",\"participant\":{\"strategy\":\"FIXED_USER\",\"value\":[\"" + APPROVER + "\"]}},\"x\":300,\"y\":300},"
                + "{\"id\":\"n_end\",\"kind\":\"node\",\"type\":\"END\",\"config\":{},\"x\":500,\"y\":300},"
                + "{\"id\":\"e1\",\"kind\":\"edge\",\"source\":\"n_start\",\"target\":\"n_a1\"},"
                + "{\"id\":\"e2\",\"kind\":\"edge\",\"source\":\"n_a1\",\"target\":\"n_end\"}],"
                + "\"contractVersion\":2}";
    }

    @Test
    @DisplayName("截止已过两顺序守准入 → 原EXPIRED行永不改写 → :R1恢复命令消费成功一次 → 已生效重放幂等命中")
    void expiredOriginalPreservedAndRecoveryChainExecutesOnce() throws Exception {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                Map.of("topic", "EXPIRED恢复验收A"), null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);

        String comment = "原用户首次提交（错过准入截止）";
        CommandAcceptRespDTO first = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req(comment),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        Long originId = first.getCommandId();

        // 测试装置：deadline 置为过去（构造"错过准入截止"前置状态）
        jdbc.update("UPDATE sw_bpm_command SET deadline_at = now() - interval '5 seconds' WHERE id = ?", originId);

        // 顺序一（dispatcher 先行）：截止已过 → 领取必须被拒，命令保持 PENDING 可被对账收敛
        var claimedAfterDeadline = commandQueue.claimDue(
                List.of(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL), 10);
        assertThat(claimedAfterDeadline)
                .as("准入截止已过的 PENDING 命令不得被领取（不依赖对账抢先改状态）")
                .noneMatch(env -> env.getCommandId().equals(originId));
        String statusStillPending = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, originId);
        assertThat(statusStillPending).as("领取被拒后保持 PENDING").isEqualTo("PENDING");

        // 顺序二（对账先行）：expireDue 收敛 EXPIRED（效果未发生）
        reconcile.reconcileOnce(java.time.LocalDateTime.now());
        String expiredStatus = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, originId);
        assertThat(expiredStatus).as("效果未发生 + 截止已过 → EXPIRED").isEqualTo("EXPIRED");
        String originReason = jdbc.queryForObject(
                "SELECT failure_reason FROM sw_bpm_command WHERE id = ?", String.class, originId);
        java.time.LocalDateTime originFinished = jdbc.queryForObject(
                "SELECT finished_at FROM sw_bpm_command WHERE id = ?", java.time.LocalDateTime.class, originId);
        assertThat(originReason).contains("准入截止到期");

        // 原用户同键同载荷重提交 → 生成恢复命令新行 :R1（原行不改写）
        CommandAcceptRespDTO recovery = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req(comment),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        Long recoveryId = recovery.getCommandId();
        assertThat(recoveryId).as("EXPIRED 不复用同键行，恢复走新行").isNotEqualTo(originId);
        assertThat(recovery.getCommandKey()).as("恢复命令可辨认（:R1 代数）")
                .isEqualTo("TASK_APPROVE:" + taskId + ":" + APPROVER + ":R1");

        Map<String, Object> originAfterRecovery = jdbc.queryForMap(
                "SELECT status, failure_reason, finished_at, result FROM sw_bpm_command WHERE id = ?", originId);
        assertThat(originAfterRecovery.get("status")).as("原 EXPIRED 终态长期保留").isEqualTo("EXPIRED");
        assertThat(String.valueOf(originAfterRecovery.get("failure_reason")))
                .as("原过期事实（失败原因）不改写").isEqualTo(originReason);
        assertThat(((java.sql.Timestamp) originAfterRecovery.get("finished_at")).toLocalDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .as("原审计时间不改写").isEqualTo(originFinished.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        assertThat(originAfterRecovery.get("result")).as("原行无结果（效果未发生）").isNull();

        // 同键异载荷 → 2426 明确拒绝（终态后载荷不可替换）
        try {
            asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                    ApprovalAction.APPROVE, req("另一个意见（异载荷）"),
                    com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
            throw new AssertionError("异载荷重提交必须被拒绝");
        } catch (BaseException e) {
            assertThat(e.getCode()).as("同键异载荷 2426").isEqualTo(2426);
        }

        // 真实 dispatcher 消费恢复命令 → 恰好一次完成；原行仍 EXPIRED
        awaitCommandTerminal(recoveryId, "COMPLETED");
        Map<String, Object> originFinal = jdbc.queryForMap(
                "SELECT status, failure_reason FROM sw_bpm_command WHERE id = ?", originId);
        assertThat(originFinal.get("status")).as("恢复成功后原行仍为 EXPIRED（审计长期可查）")
                .isEqualTo("EXPIRED");
        assertThat(String.valueOf(originFinal.get("failure_reason"))).isEqualTo(originReason);

        // 已生效后同载荷重放 → 幂等命中恢复行（取原结果，不自动重发）
        CommandAcceptRespDTO replay = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req(comment),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        assertThat(replay.getCommandId()).as("已生效重放返回恢复命令原结果").isEqualTo(recoveryId);
        int rowsForKeyFamily = jdbc.queryForObject(
                "SELECT count(*) FROM sw_bpm_command WHERE command_key LIKE ?",
                Integer.class, "TASK_APPROVE:" + taskId + ":" + APPROVER + "%");
        assertThat(rowsForKeyFamily).as("键族恰好2行：原EXPIRED + :R1").isEqualTo(2);

        // 业务恰好办理一次：任务完成、实例结束
        org.flowable.engine.TaskService taskService = app.getBean(org.flowable.engine.TaskService.class);
        assertThat(taskService.createTaskQuery().processInstanceId(processInstanceId).count())
                .as("审批任务已被恢复命令完成").isZero();
        org.flowable.engine.HistoryService historyService =
                app.getBean(org.flowable.engine.HistoryService.class);
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult().getEndTime())
                .as("流程实例已结束").isNotNull();

        System.out.println("[P63-EV] g05b.contract origin=" + originId + "(EXPIRED preserved)"
                + " recovery=" + recoveryId + "(:R1 COMPLETED once)"
                + " claim-after-deadline=denied mismatch=2426 replay=idempotent");
    }

    @Test
    @DisplayName("恢复命令也错过准入截止 → 对账判 EXPIRED 且原行/恢复行均保留 → 重提交生成 :R2")
    void expiredRecoveryChainsNextGeneration() throws Exception {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                Map.of("topic", "EXPIRED恢复验收B"), null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);

        String comment = "恢复链验收";
        CommandAcceptRespDTO first = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req(comment),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        Long originId = first.getCommandId();
        jdbc.update("UPDATE sw_bpm_command SET deadline_at = now() - interval '5 seconds' WHERE id = ?", originId);
        reconcile.reconcileOnce(java.time.LocalDateTime.now());
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                String.class, originId)).isEqualTo("EXPIRED");

        CommandAcceptRespDTO recovery = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req(comment),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        Long recoveryId = recovery.getCommandId();
        assertThat(recovery.getCommandKey()).endsWith(":R1");

        // 恢复命令同样错过准入截止 → EXPIRED（恢复行也是终态，不改写）
        jdbc.update("UPDATE sw_bpm_command SET deadline_at = now() - interval '5 seconds' WHERE id = ?", recoveryId);
        var claimed = commandQueue.claimDue(
                List.of(com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL), 10);
        assertThat(claimed).noneMatch(env -> env.getCommandId().equals(recoveryId));
        reconcile.reconcileOnce(java.time.LocalDateTime.now());
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                String.class, recoveryId)).as("恢复行过期同样收敛 EXPIRED").isEqualTo("EXPIRED");

        // 第三次重提交 → 下一代 :R2；前两行 EXPIRED 记录独立保留
        CommandAcceptRespDTO next = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req(comment),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        assertThat(next.getCommandKey()).as("恢复链推进到 :R2").endsWith(":R2");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                String.class, originId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                String.class, recoveryId)).isEqualTo("EXPIRED");

        awaitCommandTerminal(next.getCommandId(), "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                String.class, originId)).as("终局回读：原行保持 EXPIRED").isEqualTo("EXPIRED");

        System.out.println("[P63-EV] g05b.chain origin=" + originId + " r1=" + recoveryId
                + " r2=" + next.getCommandId() + " each-generation-preserved=true");
    }

    private ApprovalActionRequest req(String comment) {
        ApprovalActionRequest r = new ApprovalActionRequest();
        r.setComment(comment);
        return r;
    }

    private <T> T asUser(Long userId, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "workflow:def:publish")));
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

    private String awaitInstanceStarted(String recordId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            var rows = jdbc.queryForList(
                    "SELECT process_instance_id FROM sw_bpm_instance WHERE business_key = ?", recordId);
            if (!rows.isEmpty()) {
                return String.valueOf(rows.get(0).get("process_instance_id"));
            }
            Thread.sleep(300);
        }
        throw new AssertionError("实例未在时限内创建: " + recordId);
    }

    private String awaitTask(String processInstanceId) throws InterruptedException {
        org.flowable.engine.TaskService taskService = app.getBean(org.flowable.engine.TaskService.class);
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            var task = taskService.createTaskQuery().processInstanceId(processInstanceId).list();
            if (!task.isEmpty()) {
                return task.get(0).getId();
            }
            Thread.sleep(300);
        }
        throw new AssertionError("任务未在时限内创建: " + processInstanceId);
    }

    private void awaitCommandTerminal(Long commandId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000L;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
            if ("COMPLETED".equals(last) || "FAILED".equals(last)) {
                assertThat(last).as("命令应 " + expected).isEqualTo(expected);
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("命令未在时限内到终态: " + commandId + " last=" + last);
    }

    private static String generatedRsaPkcs8Base64() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        return java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}
