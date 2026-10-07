package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.job.IotReservationDispatchJob;
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
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G06a 预约边界 + G06b 取消×认领真实存储竞争（Embedded PG + 全量上下文 + 真实调度入口）：
 * <ul>
 *   <li>G06a：批准时预约时刻已过且超出迟到窗口 → 意图创建即 EXPIRED 零外发；已过但在
 *       迟到窗口内 → 真实调度入口 catch-up 恰好一次外发（幂等重放不增）；合法未来意图由
 *       真实扫描不外发，受控时钟驱动真实 expireOverdue 收敛 EXPIRED 后仍零外发；
 *       歧义/不存在本地时刻（America/New_York 夏令时切换）在审批链明确拒绝、零意图。</li>
 *   <li>G06b：取消（facade CAS PENDING→CANCELED）与认领（调度 CAS PENDING→DISPATCHING）
 *       两种先后顺序各只有一个合法结果；并发竞争恰一方获胜，最终行与外发计数一致。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G06a 预约边界 + G06b 取消×认领竞争 PG 端到端")
class P63ReservationBoundaryPgTest {

    private static final Long TENANT = 0L;
    private static final Long INITIATOR = 91501L;
    private static final Long APPROVER = 91502L;
    private static final String FORM_KEY = "p63_boundary_form";
    private static final String DEVICE_KEY = "p63-boundary-device";
    private static final Long DEVICE_ID = 91511L;

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private FormDefService formDefService;
    private FormSubmitService submitService;
    private BpmProcessDefService processDefService;
    private CommandAcceptService acceptService;
    private IotCommandReservationFacade reservationFacade;
    private IotReservationDispatchJob dispatchJob;

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
        props.put("sw.security.jwt.secret", "p63-boundary-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-boundary-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // IoT 启用；无传输对端——外发断言只针对命令行/预约行持久事实，不依赖传输成功
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", "p63-boundary-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-boundary", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63BoundaryLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        acceptService = app.getBean(CommandAcceptService.class);
        reservationFacade = app.getBean(IotCommandReservationFacade.class);
        dispatchJob = app.getBean(IotReservationDispatchJob.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-bound-initiator', 'seed-not-a-login-secret', '边界发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-bound-approver', 'seed-not-a-login-secret', '边界审批人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91501, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91502, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 'P63边界受控设备', 'ONLINE', 'P63PROD', ?, 'PUBLISHED', 1) "
                + "ON CONFLICT (id) DO NOTHING", DEVICE_ID, TENANT, DEVICE_KEY, DEVICE_KEY);

        asUser(INITIATOR, () -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P63边界表单", null, null);
            formDefService.saveConfig(draft.getId(),
                    "{\"schemaVersion\":1,\"title\":\"P63边界表单\",\"fields\":["
                            + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false},"
                            + "{\"name\":\"plan_time\",\"type\":\"DATE\",\"label\":\"预约时间\",\"required\":false,"
                            + "\"format\":\"datetime\"}]}");
            formDefService.publish(draft.getId());
            return null;
        });
        System.out.println("[P63-EV] boundary boot ok pgPort=" + pg.getPort());
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

    // ==================== G06a ====================

    @Test
    @DisplayName("批准时预约时刻已过且超迟到窗口 → 意图创建即 EXPIRED 保留记录、零外发")
    void approveAfterWindowCreatesExpiredIntentWithZeroDispatch() {
        BpmProcessDef def = publishReservationProcess("Asia/Shanghai", 60);
        String recordId = submitAndApprove(def, localShanghaiText(java.time.Duration.ofMinutes(-10)));
        String pi = awaitInstanceStarted(recordId);
        Map<String, Object> intent = awaitIntentRow(pi);
        assertThat(intent.get("status")).as("窗口已过：意图创建即 EXPIRED（不补发）").isEqualTo("EXPIRED");
        assertThat(intent.get("due_at_utc")).as("冻结 dueAtUtc 可查").isNotNull();
        assertThat(intent.get("timezone_id")).isEqualTo("Asia/Shanghai");
        assertThat(((Number) intent.get("late_window_seconds")).intValue()).isEqualTo(60);
        assertThat(commandsFor(pi)).as("窗口外批准零外发").isZero();
        System.out.println("[P63-EV] g06a.beyond-window pi=" + pi + " intent=EXPIRED-at-create commands=0"
                + " due_utc=" + intent.get("due_at_utc"));
    }

    @Test
    @DisplayName("批准时已过点但在迟到窗口内 → 真实调度入口 catch-up 恰好一次外发，重放不增")
    void withinWindowCatchUpDispatchesExactlyOnce() {
        BpmProcessDef def = publishReservationProcess("Asia/Shanghai", 3600);
        String recordId = submitAndApprove(def, localShanghaiText(java.time.Duration.ofSeconds(-5)));
        String pi = awaitInstanceStarted(recordId);
        Map<String, Object> intent = awaitIntentRow(pi);
        // 后台 10s 调度 tick 可能先于手动触发认领：此处接受 PENDING/DISPATCHING/DISPATCHED，
        // 核心断言为“恰一条命令且重放不增”
        assertThat(String.valueOf(intent.get("status"))).as("窗口内：意图未被取消/过期")
                .isIn("PENDING", "DISPATCHING", "DISPATCHED");

        dispatchJob.dispatchDueReservations();
        int afterFirst = commandsFor(pi);
        assertThat(afterFirst).as("窗口内 catch-up 外发恰好一条命令").isEqualTo(1);
        String idempotentKey = jdbc.queryForObject(
                "SELECT idempotent_key FROM sw_iot_device_command WHERE approval_biz_id = ?",
                String.class, pi);
        assertThat(idempotentKey).as("命令为预约来源").startsWith("RESERVATION:");

        dispatchJob.dispatchDueReservations();
        dispatchJob.dispatchDueReservations();
        assertThat(commandsFor(pi)).as("重复真实扫描不增外发（幂等）").isEqualTo(1);
        System.out.println("[P63-EV] g06a.within-window pi=" + pi + " commands=" + afterFirst
                + " idempotent_key=" + idempotentKey + " replays=2 no-growth=true");
    }

    @Test
    @DisplayName("合法未来意图真实扫描不外发；受控时钟驱动真实 expireOverdue 收敛 EXPIRED 仍零外发")
    void futureIntentMissedWindowExpiresWithoutDispatch() throws Exception {
        BpmProcessDef def = publishReservationProcess("Asia/Shanghai", 60);
        String recordId = submitAndApprove(def, localShanghaiText(java.time.Duration.ofMinutes(30)));
        String pi = awaitInstanceStarted(recordId);
        Map<String, Object> intent = awaitIntentRow(pi);
        assertThat(intent.get("status")).as("未来预约 PENDING").isEqualTo("PENDING");

        dispatchJob.dispatchDueReservations();
        assertThat(intentStatus(pi)).as("真实扫描不外发未来意图").isEqualTo("PENDING");
        assertThat(commandsFor(pi)).isZero();

        // 受控时钟驱动真实持久收敛入口 expireOverdue（有限触发，不长等真实时钟）
        LocalDateTime futureUtc = LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(31);
        Method expire = ReflectionUtils.findMethod(IotReservationDispatchJob.class,
                "expireOverdue", LocalDateTime.class);
        ReflectionUtils.makeAccessible(expire);
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            ReflectionUtils.invokeMethod(expire, dispatchJob, futureUtc);
        }
        assertThat(intentStatus(pi)).as("超窗后收敛 EXPIRED").isEqualTo("EXPIRED");

        dispatchJob.dispatchDueReservations();
        assertThat(commandsFor(pi)).as("错窗意图零外发").isZero();
        System.out.println("[P63-EV] g06a.missed-window pi=" + pi
                + " scan-while-future=no-dispatch controlled-expire=EXPIRED commands=0");
    }

    @Test
    @DisplayName("歧义/不存在本地时刻（America/New_York 夏令时切换）→ 审批链明确拒绝、零意图零外发")
    void ambiguousAndNonexistentLocalTimeRejected() {
        publishReservationProcess("America/New_York", 60);
        // 2027-03-14 02:30 本地时刻在美国东部春令时切换中不存在
        String recordId1 = submitAndApproveExpectReject("不存在时刻", "2027-03-14 02:30:00");
        // 2027-11-07 01:30 本地时刻在秋令时回拨中出现两次（歧义）
        String recordId2 = submitAndApproveExpectReject("歧义时刻", "2027-11-07 01:30:00");

        int intents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE record_id IN (?, ?)",
                Integer.class, recordId1, recordId2);
        assertThat(intents).as("歧义/不存在时刻零意图").isZero();
        System.out.println("[P63-EV] g06a.invalid-local-time record1=" + recordId1
                + " record2=" + recordId2 + " intents=0 both-rejected=true");
    }

    private String submitAndApproveExpectReject(String topic, String dueText) {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", topic, "plan_time", dueText), null, null, null));
        String pi = awaitInstanceStarted(recordId);
        String taskId = awaitTask(pi);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("批准含非法本地时刻"), CommandChannelEnum.NORMAL));
        awaitCommandTerminalFailed(accepted.getCommandId());
        long tasks = app.getBean(org.flowable.engine.TaskService.class)
                .createTaskQuery().processInstanceId(pi).count();
        assertThat(tasks).as("拒绝后审批任务保留（整体回滚）").isEqualTo(1);
        return recordId;
    }

    // ==================== G06b ====================

    @Test
    @DisplayName("取消先手：CAS 取消落原因/操作者、零外发；随后真实认领竞争失败")
    void cancelWinsPersistsReasonAndZeroDispatch() {
        BpmProcessDef def = publishReservationProcess("Asia/Shanghai", 60);
        String recordId = submitAndApprove(def, localShanghaiText(java.time.Duration.ofMinutes(20)));
        String pi = awaitInstanceStarted(recordId);
        Long reservationId = ((Number) awaitIntentRow(pi).get("id")).longValue();

        java.util.Optional<String> result = asController(
                () -> reservationFacade.cancel(TENANT, reservationId, APPROVER, "G06b 取消先手（窗口未到）"));
        assertThat(result).as("PENDING 先手取消=CANCELED").contains(IotCommandReservationFacade.CANCEL_CANCELED);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, cancel_by, cancel_reason, cancel_time FROM sw_iot_command_reservation WHERE id = ?",
                reservationId);
        assertThat(row.get("status")).isEqualTo("CANCELED");
        assertThat(((Number) row.get("cancel_by")).longValue()).isEqualTo(APPROVER);
        assertThat(String.valueOf(row.get("cancel_reason"))).contains("取消先手");
        assertThat(row.get("cancel_time")).isNotNull();

        dispatchJob.dispatchDueReservations();
        assertThat(intentStatus(pi)).as("取消后真实认领不再生效").isEqualTo("CANCELED");
        assertThat(commandsFor(pi)).as("取消胜=零外发").isZero();
        System.out.println("[P63-EV] g06b.cancel-first reservation=" + reservationId
                + " cancel=CANCELED reason-persisted commands=0 claim-after=lost");
    }

    @Test
    @DisplayName("认领先手：真实调度 CAS 占用后取消明确不可取消，仅一个合法结果")
    void claimWinsMakesCancelNotCancellable() {
        BpmProcessDef def = publishReservationProcess("Asia/Shanghai", 3600);
        String recordId = submitAndApprove(def, localShanghaiText(java.time.Duration.ofSeconds(-4)));
        String pi = awaitInstanceStarted(recordId);
        Long reservationId = ((Number) awaitIntentRow(pi).get("id")).longValue();

        dispatchJob.dispatchDueReservations();
        String claimed = intentStatus(pi);
        assertThat(claimed).as("认领先手=调度侧已认领（DISPATCHING/DISPATCHED）")
                .isIn("DISPATCHING", "DISPATCHED");

        java.util.Optional<String> result = asController(
                () -> reservationFacade.cancel(TENANT, reservationId, APPROVER, "G06b 认领后取消（应被拒）"));
        assertThat(result).as("认领后取消=NOT_CANCELLABLE")
                .contains(IotCommandReservationFacade.CANCEL_NOT_CANCELLABLE);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, cancel_by, cancel_reason FROM sw_iot_command_reservation WHERE id = ?",
                reservationId);
        assertThat(String.valueOf(row.get("status"))).as("认领后状态不被取消改写")
                .isIn("DISPATCHING", "DISPATCHED");
        assertThat(row.get("cancel_by")).isNull();
        assertThat(commandsFor(pi)).as("认领先手恰一条命令").isEqualTo(1);
        System.out.println("[P63-EV] g06b.claim-first reservation=" + reservationId
                + " claim=DISPATCHING cancel=NOT_CANCELLABLE commands=1");
    }

    @Test
    @DisplayName("并发竞争：取消与真实调度同时启动，恰一方获胜，最终行与外发计数自洽")
    void concurrentCancelVsClaimHasExactlyOneWinner() throws Exception {
        for (int round = 1; round <= 3; round++) {
            BpmProcessDef def = publishReservationProcess("Asia/Shanghai", 3600);
            String recordId = submitAndApprove(def, localShanghaiText(java.time.Duration.ofSeconds(-2)));
            String pi = awaitInstanceStarted(recordId);
            Long reservationId = ((Number) awaitIntentRow(pi).get("id")).longValue();

            CountDownLatch start = new CountDownLatch(1);
            final int roundNo = round;
            AtomicReference<java.util.Optional<String>> cancelResult = new AtomicReference<>();
            Thread canceller = new Thread(() -> {
                try {
                    start.await();
                    // 取消方模拟带租户身份的控制端调用（与生产 Controller 链一致）
                    LoginUser actor = new LoginUser();
                    actor.setUserId(APPROVER);
                    actor.setTenantId(TENANT);
                    actor.setPermissions(new ArrayList<>(List.of("iot:reservation:cancel")));
                    LoginUserHolder.set(actor);
                    cancelResult.set(reservationFacade.cancel(TENANT, reservationId,
                            APPROVER, "G06b 并发竞争第" + roundNo + "轮"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    LoginUserHolder.clear();
                }
            });
            Thread claimer = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                dispatchJob.dispatchDueReservations();
            });
            canceller.start();
            claimer.start();
            start.countDown();
            canceller.join(30_000);
            claimer.join(30_000);

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, cancel_reason FROM sw_iot_command_reservation WHERE id = ?", reservationId);
            String finalStatus = String.valueOf(row.get("status"));
            int commands = commandsFor(pi);
            boolean cancelWon = java.util.Optional.of(IotCommandReservationFacade.CANCEL_CANCELED)
                    .equals(cancelResult.get());
            // 恰一方获胜：取消胜→CANCELED；认领胜→DISPATCHING/DISPATCHED（发送完成即 DISPATCHED）
            if (cancelWon) {
                assertThat(finalStatus).as("取消胜→CANCELED").isEqualTo("CANCELED");
                assertThat(commands).as("取消胜零外发").isZero();
            } else {
                assertThat(finalStatus).as("认领胜→调度侧状态").isIn("DISPATCHING", "DISPATCHED");
                assertThat(cancelResult.get()).contains(IotCommandReservationFacade.CANCEL_NOT_CANCELLABLE);
                assertThat(commands).as("认领胜恰一条命令").isEqualTo(1);
            }
            System.out.println("[P63-EV] g06b.concurrent round=" + round + " cancel=" + cancelResult.get()
                    + " final=" + finalStatus + " commands=" + commands + " single-winner=true");
        }
    }

    // ==================== 种子与辅助 ====================

    private BpmProcessDef publishReservationProcess(String timezoneId, int lateWindowSeconds) {
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService
                .createDef("P63边界场景-" + timezoneId + "-" + System.nanoTime(), FORM_KEY));
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
            processDefService.setIotDeviceAction(def.getId(), reservationActionJson(timezoneId, lateWindowSeconds));
            processDefService.publish(def.getId());
            return null;
        });
        return def;
    }

    private String reservationActionJson(String timezoneId, int lateWindowSeconds) {
        return "{\"enabled\":true,\"deliveryMode\":\"RESERVATION\","
                + "\"deviceSource\":\"FIXED\",\"deviceId\":" + DEVICE_ID + ","
                + "\"commandKey\":\"power_off\",\"paramField\":null,"
                + "\"reservation\":{\"dueField\":\"plan_time\",\"timezoneId\":\"" + timezoneId
                + "\",\"lateWindowSeconds\":" + lateWindowSeconds + "}}";
    }

    private String submitAndApprove(BpmProcessDef def, String dueText) {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", "边界场景", "plan_time", dueText), null, null, null));
        String pi = awaitInstanceStarted(recordId);
        String taskId = awaitTask(pi);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("核准预约"), CommandChannelEnum.NORMAL));
        awaitCommandCompleted(accepted.getCommandId());
        return recordId;
    }

    private String localShanghaiText(java.time.Duration offset) {
        LocalDateTime due = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plus(offset);
        return due.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private int commandsFor(String processInstanceId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_device_command WHERE approval_biz_id = ?",
                Integer.class, processInstanceId);
    }

    private String intentStatus(String processInstanceId) {
        return jdbc.queryForObject(
                "SELECT status FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                String.class, processInstanceId);
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

    private void awaitCommandTerminalFailed(Long commandId) {
        long deadline = System.currentTimeMillis() + 180_000L;
        String status = null;
        String reason = null;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, failure_reason FROM sw_bpm_command WHERE id = ?", commandId);
            status = String.valueOf(row.get("status"));
            if ("FAILED".equals(status)) {
                reason = String.valueOf(row.get("failure_reason"));
                assertThat(reason).as("拒绝原因可读（本地时刻非法）").isNotBlank();
                return;
            }
            assertThat(status).as("拒绝场景不得成功").isNotEqualTo("COMPLETED");
            sleepBriefly();
        }
        throw new AssertionError("180s 内审批命令未收敛 FAILED: command=" + commandId + " status=" + status);
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

    /** 模拟带租户身份的控制端调用（生产取消经认证 Controller 进入）。 */
    private <T> T asController(Callable<T> action) {
        LoginUser actor = new LoginUser();
        actor.setUserId(APPROVER);
        actor.setTenantId(TENANT);
        actor.setPermissions(new ArrayList<>(List.of("iot:reservation:cancel")));
        LoginUserHolder.set(actor);
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

    private void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
