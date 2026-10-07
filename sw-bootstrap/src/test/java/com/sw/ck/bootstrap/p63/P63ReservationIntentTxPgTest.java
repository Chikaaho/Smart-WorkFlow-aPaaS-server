package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.iot.job.IotReservationDispatchJob;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.sw.ck.bpm.process.listener.BpmIotReservationIntentRecorder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P63 G05 意图事务与动作幂等真实持久化集成（Embedded PG + 全量应用上下文）：
 * 1) 重放 PROCESS_APPROVED 事件只产生一个意图（唯一键幂等）；
 * 2) 意图持久化失败→审批整体回滚（实例不终态、零意图行）；
 * 3) 非成功终态（REJECT）零意图；
 * 4) 提交后到点调度失败（设备已失效）只更新预约为 FAILED 可查，不回滚已批准审批；
 * 5) 表单批准时刻=冻结预约时刻（Asia/Shanghai 精确到秒）。
 * 动作映射口径：本轮定义配置为单设备动作槽（deliveryMode+reservation 单块），
 * 映射=定义配置(1动作)→实例意图(1)→命令(1)→回执(同命令)；唯一键(tenant,instance)与该模型一致。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G05 意图事务/幂等/失败路径 PG 端到端")
class P63ReservationIntentTxPgTest {

    private static final Long TENANT = 0L;
    private static final Long INITIATOR = 91301L;
    private static final Long APPROVER = 91302L;
    private static final String FORM_KEY = "p63_intent_form";
    private static final String DEVICE_KEY = "p63-demo-device";
    private static final Long DEVICE_ID = 91311L;

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private FormDefService formDefService;
    private FormSubmitService submitService;
    private BpmProcessDefService processDefService;
    private CommandAcceptService acceptService;
    private BpmCommandMapper commandMapper;
    private BpmIotReservationIntentRecorder intentRecorder;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-intent-tx-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-intent-tx-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // IoT 启用但不含 loopback provider：调度失败路径由设备失效触发，不依赖传输对端
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", "p63-intent-tx-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-intent-tx", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63IntentTxLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        acceptService = app.getBean(CommandAcceptService.class);
        commandMapper = app.getBean(BpmCommandMapper.class);
        intentRecorder = app.getBean(BpmIotReservationIntentRecorder.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-intent-initiator', 'seed-not-a-login-secret', '意图链发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-intent-approver', 'seed-not-a-login-secret', '意图链审批人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91301, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91302, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER);

        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 'P63意图链受控设备', 'ONLINE', 'P63PROD', ?, 'PUBLISHED', 1) "
                + "ON CONFLICT (id) DO NOTHING", DEVICE_ID, TENANT, DEVICE_KEY, DEVICE_KEY);
        seedForm();
        System.out.println("[P63-EV] intent.tx boot ok pgPort=" + pg.getPort());
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

    // ==================== 场景 ====================

    @Test
    @DisplayName("重放 PROCESS_APPROVED 事件只产生一个意图；表单批准时刻=冻结预约时刻（精确到秒）")
    void replayKeepsSingleIntentAndFrozenTime() {
        // 动态未来时刻（原硬编码日期跨天后会正确地被判 EXPIRED——那是过期语义而非缺陷）
        java.time.LocalDateTime dueLocal = java.time.LocalDateTime
                .now(java.time.ZoneId.of("Asia/Shanghai")).plusHours(2)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String dueText = dueLocal.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", "意图重放验收", "plan_time", dueText),
                null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        publishApprovalProcessWithTz("Asia/Shanghai");
        String taskId = awaitTask(processInstanceId);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> {
            ApprovalActionRequest req = new ApprovalActionRequest();
            req.setComment("核准预约");
            return acceptService.acceptTaskAction(taskId,
                    ApprovalAction.APPROVE, req, CommandChannelEnum.NORMAL);
        });
        awaitCommandCompleted(accepted.getCommandId());
        Map<String, Object> intent = awaitIntentRow(processInstanceId);
        long intentId = ((Number) intent.get("id")).longValue();

        // 真实重放：按命令消费同口径（消费身份回查 + 消费事务内）以同身份事件再触发一次
        asUser(APPROVER, () -> {
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(
                            app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> intentRecorder.onProcessApproved(
                    new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED,
                            INITIATOR, TENANT, APPROVER, processInstanceId)));
            return null;
        });
        int count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                Integer.class, processInstanceId);
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT id, status, due_at_utc FROM sw_iot_command_reservation "
                        + "WHERE process_instance_id = ?", processInstanceId);
        assertThat(count).as("重放不产生第二条意图").isEqualTo(1);
        assertThat(((Number) after.get("id")).longValue()).as("意图身份复用不变").isEqualTo(intentId);
        // 表单批准时刻=冻结预约时刻：due_local(Asia/Shanghai) 的 UTC 转换精确一致
        String dueUtcExpected = java.time.LocalDateTime.ofInstant(
                dueLocal.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant(),
                java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Object dueUtc = after.get("due_at_utc");
        assertThat(String.valueOf(dueUtc)).as("冻结预约时刻与表单批准时刻精确一致")
                .startsWith(dueUtcExpected);
        assertThat(intent.get("status")).isEqualTo("PENDING");

        System.out.println("[P63-EV] g05.replay instance=" + processInstanceId + " intent=" + intentId
                + " replay=0-duplicate due_at_utc=" + dueUtc + " tz=Asia/Shanghai exact-match=true");
    }

    @Test
    @DisplayName("意图持久化失败→审批整体回滚：实例不终态、任务仍在、零意图行")
    void intentFailureRollsBackApproval() {
        // 非法时区让 recorder fail-closed 抛错，阻断审批提交事务
        publishApprovalProcessWithTz("Mars/Olympus");
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", "意图回滚验收", "plan_time", "2026-10-07 09:00:00"),
                null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> {
            ApprovalActionRequest req = new ApprovalActionRequest();
            req.setComment("尝试通过");
            return acceptService.acceptTaskAction(taskId,
                    ApprovalAction.APPROVE, req, CommandChannelEnum.NORMAL);
        });
        // 命令调度器消费审批：recorder fail-closed 抛错 → 审批完成事务回滚 → 命令按失败/重试语义记录
        awaitCommandTerminal(accepted.getCommandId());

        String instanceStatus = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_instance WHERE business_key = ?", String.class, recordId);
        int tasksLeft = app.getBean(org.flowable.engine.TaskService.class)
                .createTaskQuery().processInstanceId(processInstanceId).list().size();
        int intents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                Integer.class, processInstanceId);
        assertThat(instanceStatus).as("审批事务回滚：实例不进入终态").isEqualTo("RUNNING");
        assertThat(tasksLeft).as("审批任务保留可重试").isEqualTo(1);
        assertThat(intents).as("零意图行（无半提交）").isZero();

        System.out.println("[P63-EV] g05.rollback instance=" + processInstanceId + " task=" + taskId
                + " instance=RUNNING task-kept=1 intents=0 fail-closed=true");
    }

    @Test
    @DisplayName("非成功终态（REJECT）零意图")
    void nonSuccessTerminalZeroIntent() {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", "拒绝零意图验收", "plan_time", "2026-10-07 10:00:00"),
                null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> {
            ApprovalActionRequest req = new ApprovalActionRequest();
            req.setComment("驳回申请");
            return acceptService.acceptTaskAction(taskId,
                    ApprovalAction.REJECT, req, CommandChannelEnum.NORMAL);
        });
        awaitCommandCompleted(accepted.getCommandId());
        String status = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_instance WHERE business_key = ?", String.class, recordId);
        int intents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                Integer.class, processInstanceId);
        assertThat(status).isEqualTo("REJECTED");
        assertThat(intents).as("驳回终态零预约副作用").isZero();

        System.out.println("[P63-EV] g05.reject instance=" + processInstanceId
                + " instance=REJECTED intents=0");
    }

    @Test
    @DisplayName("提交后调度失败（设备已失效）只更新预约 FAILED 可查，不回滚已批准审批；零外发")
    void postCommitDispatchFailureKeepsApproval() {
        publishApprovalProcessWithTz("Asia/Shanghai");
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", "提交后失败验收", "plan_time",
                        futureDueText(java.time.Duration.ofSeconds(3))),
                null, null, null));
        String processInstanceId = awaitInstanceStarted(recordId);
        String taskId = awaitTask(processInstanceId);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> {
            ApprovalActionRequest req = new ApprovalActionRequest();
            req.setComment("核准后设备下线");
            return acceptService.acceptTaskAction(taskId,
                    ApprovalAction.APPROVE, req, CommandChannelEnum.NORMAL);
        });
        awaitCommandCompleted(accepted.getCommandId());
        Map<String, Object> intent = awaitIntentRow(processInstanceId);
        assertThat(intent.get("status")).isEqualTo("PENDING");

        // 到点前设备失效（软删）：调度认领后按明确校验失败收敛，不伪造外发
        jdbc.update("UPDATE sw_iot_device SET deleted = 1 WHERE device_key = ?", DEVICE_KEY);
        try {
            // 有界等待 3s 至预约时刻，再经真实持久调度入口手动触发一次认领下发
            Thread.sleep(3500);
            app.getBean(IotReservationDispatchJob.class).dispatchDueReservations();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            jdbc.update("UPDATE sw_iot_device SET deleted = 0 WHERE device_key = ?", DEVICE_KEY);
        }
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT status, reject_reason FROM sw_iot_command_reservation "
                        + "WHERE process_instance_id = ?", processInstanceId);
        int commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_device_command WHERE idempotent_key LIKE 'RESERVATION:%'",
                Integer.class);
        String instanceStatus = jdbc.queryForObject(
                "SELECT status FROM sw_bpm_instance WHERE business_key = ?", String.class, recordId);
        assertThat(after.get("status")).as("调度失败只更新预约状态为 FAILED 可查")
                .isEqualTo("FAILED");
        assertThat(String.valueOf(after.get("reject_reason"))).as("失败原因可读可定位").isNotBlank();
        assertThat(commands).as("设备失效→零外发（未产生命令行）").isZero();
        assertThat(instanceStatus).as("已批准审批不被调度失败回滚").isEqualTo("APPROVED");

        System.out.println("[P63-EV] g05.postcommit instance=" + processInstanceId
                + " reservation=FAILED reason-persisted commands=0 zero-send=true approval=APPROVED-kept");
    }

    // ==================== 种子与辅助 ====================

    private void seedForm() {
        asUser(INITIATOR, () -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P63意图链", null, null);
            formDefService.saveConfig(draft.getId(), intentDefinition());
            formDefService.publish(draft.getId());
            return null;
        });
    }

    private String intentDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P63意图链\",\"fields\":["
                + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false},"
                + "{\"name\":\"plan_time\",\"type\":\"DATE\",\"label\":\"预约时间\",\"required\":false,"
                + "\"format\":\"datetime\"}]}";
    }

    /** 每个场景发布一个独立定义（processKey 独立，互不污染）。 */
    private void publishApprovalProcessWithTz(String timezoneId) {
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService
                .createDef("P63意图场景-" + timezoneId + "-" + System.nanoTime(), FORM_KEY));
        List<GraphElement> elements = List.of(
                node("start", "START", null),
                node("approver", "APPROVAL", Map.of("name", "人工审批",
                        "approver", Map.of("type", "DESIGNATED", "value", String.valueOf(APPROVER)))),
                node("end", "END", null),
                edge("e1", "start", "approver"),
                edge("e2", "approver", "end"));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey(def.getProcessKey())
                .name(def.getName())
                .formKey(FORM_KEY)
                .version(1)
                .elements(elements)
                .build();
        asUser(INITIATOR, () -> {
            processDefService.saveDraftGraph(def.getId(), toJson(graph));
            processDefService.setIotDeviceAction(def.getId(), reservationActionJson(timezoneId));
            processDefService.publish(def.getId());
            return null;
        });
    }

    private String reservationActionJson(String timezoneId) {
        return "{\"enabled\":true,\"deliveryMode\":\"RESERVATION\","
                + "\"deviceSource\":\"FIXED\",\"deviceId\":" + DEVICE_ID + ","
                + "\"commandKey\":\"power_off\",\"paramField\":null,"
                + "\"reservation\":{\"dueField\":\"plan_time\",\"timezoneId\":\"" + timezoneId
                + "\",\"lateWindowSeconds\":60}}";
    }

    private String futureDueText(java.time.Duration ahead) {
        // 与提交侧同口径：YYYY-MM-DD HH:mm:ss（Asia/Shanghai 语义，由 recorder 冻结为 UTC）
        java.time.LocalDateTime due = java.time.LocalDateTime
                .now(java.time.ZoneId.of("Asia/Shanghai")).plus(ahead);
        return due.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String awaitInstanceStarted(String recordId) {
        long deadline = System.currentTimeMillis() + 180_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT process_instance_id FROM sw_bpm_instance WHERE business_key = ?", recordId);
            if (!rows.isEmpty()) {
                return String.valueOf(rows.get(0).get("process_instance_id"));
            }
            sleepBriefly();
        }
        throw new AssertionError("180s 内流程实例未创建: record=" + recordId);
    }

    private String awaitTask(String processInstanceId) {
        org.flowable.engine.TaskService taskService = app.getBean(org.flowable.engine.TaskService.class);
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            var task = taskService.createTaskQuery().processInstanceId(processInstanceId).singleResult();
            if (task != null) {
                return task.getId();
            }
            sleepBriefly();
        }
        throw new AssertionError("60s 内未产生人工审批任务: instance=" + processInstanceId);
    }

    /** 有界等待命令进入终态（COMPLETED 或重试耗尽 FAILED），供失败场景断言。 */
    private void awaitCommandTerminal(Long commandId) {
        long deadline = System.currentTimeMillis() + 120_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                    String.class, commandId);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("120s 内审批命令未进入终态: command=" + commandId + " status=" + status);
    }

    private void awaitCommandCompleted(Long commandId) {
        long deadline = System.currentTimeMillis() + 120_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                    String.class, commandId);
            if ("COMPLETED".equals(status)) {
                return;
            }
            assertThat(status).as("审批命令不得进入失败/过期终态").isNotIn("FAILED", "EXPIRED");
            sleepBriefly();
        }
        throw new AssertionError("120s 内审批命令未 COMPLETED: command=" + commandId);
    }

    private Map<String, Object> awaitIntentRow(String processInstanceId) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                    processInstanceId);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            sleepBriefly();
        }
        throw new AssertionError("60s 内未产生预约意图行: instance=" + processInstanceId);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> data(String... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target).build();
    }

    private static <T> T asUser(Long userId, Callable<T> action) {
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

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair pair = gen.generateKeyPair();
            return java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
