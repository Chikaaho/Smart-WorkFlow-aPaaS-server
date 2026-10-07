package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.listener.BpmIotReservationIntentRecorder;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.iot.job.CommandCompensationJob;
import com.sw.ck.iot.job.IotReservationDispatchJob;
import com.sw.ck.iot.model.ManualVerifyRequest;
import com.sw.ck.iot.model.DeviceReceiptDecision;
import com.sw.ck.iot.service.impl.DeviceReceiptServiceImpl;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G07b 预约 UNKNOWN 链（Embedded PG + 全量上下文 + 真实对端 9778 noreceipt 模式）：
 * 预约到点真实下发 → 对端 200 受理但不回执（命令真实停留 SENT）→ 受控时钟把 expiry_time
 * 置过期 → 真实补偿入口 execute() 收敛 UNKNOWN → 可查询；有限触发下外发计数不增（UNKNOWN
 * 不自动重发）；人工核实：有权限+可信依据收敛 SUCCESS（审计含依据），无权限/无依据/非UNKNOWN
 * 分别拒绝。设备凭证为任务约定测试值，证据脱敏不含秘密。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G07b 预约 UNKNOWN 查询/不重发/人工核实 PG 端到端")
class P63ReservationUnknownPgTest {

    private static final Long TENANT = 0L;
    private static final Long INITIATOR = 91701L;
    private static final Long APPROVER = 91702L;
    private static final String FORM_KEY = "p63_unknown_form";
    private static final String DEVICE_KEY = "p63-unknown-device";
    private static final Long DEVICE_ID = 91711L;
    private static final Path PEER_LOG = Path.of("/tmp/p63ev/peer-received-noreceipt.jsonl");

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private FormSubmitService submitService;
    private CommandAcceptService acceptService;
    private IotReservationDispatchJob dispatchJob;
    private CommandCompensationJob compensationJob;
    private DeviceReceiptServiceImpl receiptService;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "18779");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-unknown-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-unknown-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.tencent.provider-mode", "loopback");
        props.put("sw.iot.loopback.peer-url", "http://127.0.0.1:9778/device");
        props.put("sw.iot.receipt.secret", "p63-acceptance-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-unknown", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63UnknownLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        FormDefService formService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        acceptService = app.getBean(CommandAcceptService.class);
        dispatchJob = app.getBean(IotReservationDispatchJob.class);
        compensationJob = app.getBean(CommandCompensationJob.class);
        receiptService = app.getBean(DeviceReceiptServiceImpl.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-unknown-init', 'seed-not-a-login-secret', 'UNKNOWN链发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-unknown-appr', 'seed-not-a-login-secret', 'UNKNOWN链审批人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91701, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91702, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 'UNKNOWN链受控设备', 'ONLINE', 'P63PROD', ?, 'PUBLISHED', 1) "
                + "ON CONFLICT (id) DO NOTHING", DEVICE_ID, TENANT, DEVICE_KEY, DEVICE_KEY);

        // G03a 功能权威种子：产品 + 已发布物模型（power_off），预约链 fail-closed 依据
        jdbc.update("INSERT INTO sw_iot_product (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, model_status, published_model_id) "
                + "VALUES (91590, now(), now(), 0, ?, 0, 'P63PROD', 'P63预约产品', 'MQTT', 'PUBLISHED', 91591) "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO sw_iot_thing_model (id, create_time, update_time, deleted, tenant_id, version, "
                + "product_id, model_version, status, content_json, publish_time) "
                + "VALUES (91591, now(), now(), 0, ?, 0, 91590, 1, 'PUBLISHED', "
                + "'{\"properties\":[{\"id\":\"power_off\"},{\"id\":\"power_on\"}],\"events\":[],\"actions\":[]}', now()) "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("UPDATE sw_iot_device SET product_ref_id = 91590 WHERE tenant_id = ? AND deleted = 0", TENANT);

        asUser(INITIATOR, () -> {
            var draft = formService.createDraft(FORM_KEY, "P63未知链表单", null, null);
            formService.saveConfig(draft.getId(),
                    "{\"schemaVersion\":1,\"title\":\"P63未知链表单\",\"fields\":["
                            + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false},"
                            + "{\"name\":\"plan_time\",\"type\":\"DATE\",\"label\":\"预约时间\",\"required\":false,"
                            + "\"format\":\"datetime\"}]}");
            formService.publish(draft.getId());
            return null;
        });
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService
                .createDef("P63未知链场景-" + System.nanoTime(), FORM_KEY));
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
            processDefService.setIotDeviceAction(def.getId(),
                    "{\"enabled\":true,\"deliveryMode\":\"RESERVATION\",\"deviceSource\":\"FIXED\","
                            + "\"deviceId\":" + DEVICE_ID + ",\"commandKey\":\"power_off\","
                            + "\"reservation\":{\"dueField\":\"plan_time\",\"timezoneId\":\"Asia/Shanghai\","
                            + "\"lateWindowSeconds\":3600}}");
            processDefService.publish(def.getId());
            return null;
        });
        System.out.println("[P63-EV] unknown boot ok pgPort=" + pg.getPort());
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
    @DisplayName("预约下发SENT无回执→受控超时收敛UNKNOWN可查→有限触发不重发→人工核实正/负全分支")
    void reservationUnknownChain() throws Exception {
        long peerBefore = peerLines();
        // 1. 预约 PENDING（已过点但在迟到窗口内）
        // 冻结时未来 15 秒（合法未来预约，余量覆盖审批消费耗时），有界等待到点后触发真实调度入口
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", "UNKNOWN链", "plan_time", dueText(java.time.Duration.ofSeconds(15))),
                null, null, null));
        String pi = awaitInstanceStarted(recordId);
        String taskId = awaitTask(pi);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("核准预约（将SENT无回执）"), CommandChannelEnum.NORMAL));
        awaitCommandCompleted(accepted.getCommandId());
        Long reservationId = awaitIntentRow(pi);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM sw_iot_command_reservation WHERE id = ?", String.class, reservationId))
                .as("冻结时未来：意图 PENDING").isEqualTo("PENDING");
        Thread.sleep(16_000); // 有界场景等待：预约时刻到达（合法冻结后的到点）
        dispatchJob.dispatchDueReservations();

        // 2. 对端真实受理（200）但不回执 → 命令真实停留 SENT
        awaitCommandStatus(pi, "SENT", 60_000L);
        Long commandId = jdbc.queryForObject(
                "SELECT id FROM sw_iot_device_command WHERE approval_biz_id = ?", Long.class, pi);
        long peerAfterSend = peerLines();
        assertThat(peerAfterSend).as("对端受理计数 +1").isGreaterThan(peerBefore);

        // 3. 受控时钟：expiry_time 置过期（真实补偿入口以 expiry<=now 判定）
        jdbc.update("UPDATE sw_iot_device_command SET expiry_time = ? WHERE id = ?",
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1), commandId);
        compensationJob.execute();
        assertThat(statusOf(commandId)).as("回执超时收敛 UNKNOWN").isEqualTo("UNKNOWN");

        // 4. 有限触发不重发：补偿/调度再次运行，命令计数与对端接收计数均不增
        compensationJob.execute();
        dispatchJob.dispatchDueReservations();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_iot_device_command WHERE approval_biz_id = ?",
                Integer.class, pi)).as("UNKNOWN 不产生第二条命令").isEqualTo(1);
        assertThat(peerLines()).as("UNKNOWN 不自动重发（对端接收计数不变）").isEqualTo(peerAfterSend);

        // 5. 人工核实负例：无权限 / 无依据 / 非UNKNOWN
        try {
            asUser(INITIATOR, () -> receiptService.verifyManually(commandId,
                    new ManualVerifyRequest("SUCCESS", "无权限者尝试核实")));
            throw new AssertionError("无 iot:command:verify 权限必须被拒绝");
        } catch (BaseException e) {
            assertThat(String.valueOf(e.getMessage())).contains("无权人工核实");
        }
        try {
            asVerifier(() -> receiptService.verifyManually(commandId, new ManualVerifyRequest("SUCCESS", " ")));
            throw new AssertionError("无依据核实必须被拒绝");
        } catch (BaseException e) {
            assertThat(String.valueOf(e.getMessage())).contains("可信依据");
        }
        try {
            asVerifier(() -> receiptService.verifyManually(999999L, new ManualVerifyRequest("SUCCESS", "不存在的命令")));
            throw new AssertionError("不存在的命令必须 404");
        } catch (BaseException e) {
            assertThat(e.getCode()).isEqualTo(404);
        }

        // 6. 人工核实正例：有权限 + 可信依据 → SUCCESS，审计含依据
        var decision = asVerifier(() -> receiptService.verifyManually(commandId,
                new ManualVerifyRequest("SUCCESS", "现场工单 G07b-001（设备面板确认已关机）")));
        assertThat(decision.verdict()).as("人工核实收敛").isEqualTo(
                DeviceReceiptDecision.Verdict.APPLIED);
        assertThat(statusOf(commandId)).as("UNKNOWN→SUCCESS（人工核实）").isEqualTo("SUCCESS");
        int audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'COMMAND_MANUAL_VERIFY' "
                        + "AND object_id = ? AND detail LIKE '%G07b-001%'", Integer.class, String.valueOf(commandId));
        assertThat(audits).as("核实审计含可信依据").isEqualTo(1);

        // 7. 终态后再次核实拒绝（非UNKNOWN）
        try {
            asVerifier(() -> receiptService.verifyManually(commandId,
                    new ManualVerifyRequest("FAILED", "已终态再核实")));
            throw new AssertionError("非 UNKNOWN 必须拒绝");
        } catch (BaseException e) {
            assertThat(String.valueOf(e.getMessage())).contains("UNKNOWN");
        }
        // 核实后依然不重发
        compensationJob.execute();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_iot_device_command WHERE approval_biz_id = ?",
                Integer.class, pi)).isEqualTo(1);

        System.out.println("[P63-EV] g07b.unknown pi=" + pi + " reservation=" + reservationId
                + " command=" + commandId + " peer=" + peerBefore + "->" + peerAfterSend
                + " sent-no-receipt->UNKNOWN no-resend=true verify=neg(permbasismissing)pos(SUCCESS+audit)-reterr");
    }

    // ==================== 辅助 ====================

    private long peerLines() {
        try {
            return Files.exists(PEER_LOG) ? Files.readAllLines(PEER_LOG).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String statusOf(Long commandId) {
        return jdbc.queryForObject("SELECT status FROM sw_iot_device_command WHERE id = ?",
                String.class, commandId);
    }

    private String dueText(java.time.Duration offset) {
        return java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
                .plus(offset).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void awaitCommandStatus(String pi, String expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jdbc.queryForObject(
                    "SELECT status FROM sw_iot_device_command WHERE approval_biz_id = ?", String.class, pi);
            if (expected.equals(last)) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError(timeoutMs + "ms 内命令未到 " + expected + ": last=" + last);
    }

    private void awaitCommandCompleted(Long commandId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                    String.class, commandId);
            if ("COMPLETED".equals(status)) {
                return;
            }
            assertThat(status).isNotIn("FAILED", "EXPIRED");
            Thread.sleep(300);
        }
        throw new AssertionError("审批命令未 COMPLETED: " + commandId);
    }

    private Long awaitIntentRow(String pi) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            var rows = jdbc.queryForList(
                    "SELECT id FROM sw_iot_command_reservation WHERE process_instance_id = ?", pi);
            if (!rows.isEmpty()) {
                return ((Number) rows.get(0).get("id")).longValue();
            }
            Thread.sleep(300);
        }
        throw new AssertionError("60s 内未产生预约意图行: " + pi);
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
        throw new AssertionError("180s 内流程实例未创建: " + recordId);
    }

    private String awaitTask(String pi) throws InterruptedException {
        org.flowable.engine.TaskService taskService = app.getBean(org.flowable.engine.TaskService.class);
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            var task = taskService.createTaskQuery().processInstanceId(pi).singleResult();
            if (task != null) {
                return task.getId();
            }
            Thread.sleep(300);
        }
        throw new AssertionError("60s 内未产生审批任务: " + pi);
    }

    private ApprovalActionRequest req(String comment) {
        ApprovalActionRequest r = new ApprovalActionRequest();
        r.setComment(comment);
        return r;
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

    /** 持 iot:command:verify 权限的核实人。 */
    private <T> T asVerifier(Callable<T> action) {
        LoginUser verifier = new LoginUser();
        verifier.setUserId(APPROVER);
        verifier.setTenantId(TENANT);
        verifier.setPermissions(new ArrayList<>(List.of("iot:command:verify")));
        LoginUserHolder.set(verifier);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            LoginUserHolder.clear();
        }
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
