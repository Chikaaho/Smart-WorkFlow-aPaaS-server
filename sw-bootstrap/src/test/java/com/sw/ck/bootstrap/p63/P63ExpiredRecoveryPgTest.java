package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.service.CommandAcceptService;
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
 * P63 G05b EXPIRED 原请求恢复真实持久集成（Embedded PG + 全量应用上下文 + 真实 dispatcher）：
 * 审批命令 PENDING → deadline 越过（测试装置置过去）→ 对账 expireDue 判 EXPIRED（效果未发生）
 * → 原办理用户以同键同载荷重新受理 → 复用同键行重置入队（旧过期事实保留 failure_reason）
 * → dispatcher 正常消费 → 命令 COMPLETED，同一未提交业务动作由原用户恢复成功。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G05b EXPIRED 原用户恢复 PG 端到端")
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
    @DisplayName("命令 EXPIRED（效果未发生）→ 原用户同键重受理 → requeue 恢复 → dispatcher 消费 COMPLETED")
    void expiredCommandRecoveredByOriginalUser() throws Exception {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                Map.of("topic", "EXPIRED恢复验收"), null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);

        CommandAcceptRespDTO first = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("首次提交（将错过准入截止）"),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        Long commandId = first.getCommandId();

        // 测试装置：deadline 置为过去（构造"错过准入截止"前置状态），真实对账 expireDue 判定
        jdbc.update("UPDATE sw_bpm_command SET deadline_at = now() - interval '5 seconds' WHERE id = ?", commandId);
        com.sw.ck.bpm.process.queue.TieredCommandReconcileJob reconcile =
                app.getBean(com.sw.ck.bpm.process.queue.TieredCommandReconcileJob.class);
        reconcile.reconcileOnce(java.time.LocalDateTime.now());
        String status = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
        assertThat(status).as("效果未发生 + 截止已过 → EXPIRED").isEqualTo("EXPIRED");
        String reasonBefore = jdbc.queryForObject(
                "SELECT failure_reason FROM sw_bpm_command WHERE id = ?", String.class, commandId);
        assertThat(reasonBefore).contains("准入截止到期");

        // 原办理用户同键同载荷重新受理 → 复用同键行重置入队（旧过期事实保留）
        CommandAcceptRespDTO second = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("首次提交（将错过准入截止）"),
                com.sw.ck.bpm.process.entity.CommandChannelEnum.NORMAL));
        assertThat(second.getCommandId()).as("同键行恢复，不新插").isEqualTo(commandId);
        String afterRequeue = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
        assertThat(afterRequeue).as("同键行重置为 PENDING").isEqualTo("PENDING");
        String reasonAfterRequeue = jdbc.queryForObject(
                "SELECT failure_reason FROM sw_bpm_command WHERE id = ?", String.class, commandId);
        assertThat(reasonAfterRequeue).as("恢复等待期旧过期事实保留（PENDING 态可查）")
                .contains("准入截止到期").contains("已由原用户重新提交恢复");

        // 真实 dispatcher 消费 → COMPLETED（成功终态清 reason 为既有语义）
        awaitCommandCompleted(commandId);
        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
        assertThat(finalStatus).as("原用户恢复后业务动作完成一次").isEqualTo("COMPLETED");

        System.out.println("[P63-EV] g05b.expired-recovery command=" + commandId
                + " first=PENDING->EXPIRED->requeue->COMPLETED original-user-recovered=true"
                + " reason-preserved-until-recovery=true");
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

    private void awaitCommandCompleted(Long commandId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM sw_bpm_command WHERE id = ?", String.class, commandId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("命令未在时限内到终态: " + commandId);
    }

    private static String generatedRsaPkcs8Base64() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        return java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}
