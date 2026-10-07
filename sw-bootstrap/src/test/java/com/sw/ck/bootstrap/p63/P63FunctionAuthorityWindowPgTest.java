package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.controller.BpmCommandController;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.CommandAcceptRespDTO;
import com.sw.ck.bpm.process.dto.CommandStatusRespDTO;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.CommandChannelEnum;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.CommandAcceptService;
import com.sw.ck.common.response.R;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.iot.entity.IotCommandReservation;
import com.sw.ck.iot.job.IotReservationDispatchJob;
import com.sw.ck.iot.mapper.IotCommandReservationMapper;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.util.DeferredControlUtil;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G03a 功能权威 + 用户/端点越权 + G06a 窗后边界（Embedded PG + 全量上下文 + 受控时钟）：
 * <ul>
 *   <li>G03a：预约冻结前按设备产品<b>已发布物模型</b>校验功能权威——缺模型/未发布/功能未声明
 *       一律 fail closed（成功完成事务拒绝、零意图零外发）；冻结后模型失效在到点真实调度入口
 *       FAILED 可查、零外发、审批不回滚；新增 latest 命令查询本人正向、其他用户/其他租户负向、
 *       零副作用；DESIGNATED 指定跨租户用户在真实引擎链被拒、零任务副作用。</li>
 *   <li>G06a：受控时钟贯穿真实入口（不以等待自然到点/预置过期行替代）——窗后 PENDING 由
 *       真实调度入口「对账先」收敛 EXPIRED 零外发且不复活；对账滞后时认领路径直接拒绝（
 *       「认领先」守卫）零外发；窗内入队命令窗外经真实发送入口/补偿入口均不再外发。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G03a 功能权威 + G06a 窗后边界（受控时钟）PG 端到端")
class P63FunctionAuthorityWindowPgTest {

    private static final Long TENANT = 0L;
    private static final Long TENANT2 = 2L;
    private static final Long INITIATOR = 91601L;
    private static final Long APPROVER = 91602L;
    private static final Long OTHER_USER = 91605L;
    private static final Long TENANT2_USER = 91621L;
    private static final String FORM_KEY = "p63_fa_form";
    private static final Long DEVICE_ID = 91611L;
    private static final String DEVICE_KEY = "p63-fa-device";
    private static final Long NO_MODEL_DEVICE_ID = 91612L;
    private static final String NO_MODEL_DEVICE_KEY = "p63-fa-nomodel-device";
    private static final Long PRODUCT_ID = 91590L;
    private static final Long MODEL_ID = 91591L;
    private static final Long DRAFT_PRODUCT_ID = 91592L;

    /** 测试共享的可设定时钟：贯穿 createIntent 过期判定与调度/外发真实入口。 */
    private final AtomicReference<Instant> clockNow = new AtomicReference<>(Instant.now());
    private Clock testClock;

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private FormDefService formDefService;
    private FormSubmitService submitService;
    private BpmProcessDefService processDefService;
    private CommandAcceptService acceptService;
    private IotCommandReservationFacade reservationFacade;
    private IotReservationDispatchJob dispatchJob;
    private BpmCommandController commandController;

    @BeforeAll
    void setUp() throws Exception {
        testClock = new SettableClock(clockNow, ZoneOffset.UTC);
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-fa-window-jwt-secret-0123456789abcdef0123456789");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-fa-window-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // IoT 启用；无传输 provider——外发断言针对命令/预约持久事实与发送入口守卫
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", "p63-fa-window-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-fa-window", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63FaLoginContextProvider", provider);
                    // 受控时钟覆盖 IoT 链默认 Clock（测试上下文已允许 Bean 覆盖）
                    org.springframework.beans.factory.support.RootBeanDefinition clockDef =
                            new org.springframework.beans.factory.support.RootBeanDefinition(Clock.class,
                                    () -> testClock);
                    clockDef.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("iotClock", clockDef);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        formDefService = app.getBean(FormDefService.class);
        submitService = app.getBean(FormSubmitService.class);
        processDefService = app.getBean(BpmProcessDefService.class);
        acceptService = app.getBean(CommandAcceptService.class);
        reservationFacade = app.getBean(IotCommandReservationFacade.class);
        dispatchJob = app.getBean(IotReservationDispatchJob.class);
        commandController = app.getBean(BpmCommandController.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-fa-initiator', 'seed-not-a-login-secret', '功能权威发起人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", INITIATOR, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-fa-approver', 'seed-not-a-login-secret', '功能权威审批人', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", APPROVER, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-fa-other', 'seed-not-a-login-secret', '同租他用户', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", OTHER_USER, TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-fa-t2user', 'seed-not-a-login-secret', '租户二用户', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", TENANT2_USER, TENANT2);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91601, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, INITIATOR);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91602, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, APPROVER);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91605, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, OTHER_USER);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91621, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT2, TENANT2_USER);
        // 功能权威种子：产品+已发布物模型（power_off/power_on），设备挂接产品权威
        jdbc.update("INSERT INTO sw_iot_product (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, model_status, published_model_id) "
                + "VALUES (?, now(), now(), 0, ?, 0, 'P63PROD', 'P63功能权威产品', 'MQTT', 'PUBLISHED', ?) "
                + "ON CONFLICT (id) DO NOTHING", PRODUCT_ID, TENANT, MODEL_ID);
        jdbc.update("INSERT INTO sw_iot_thing_model (id, create_time, update_time, deleted, tenant_id, version, "
                + "product_id, model_version, status, content_json, publish_time) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 1, 'PUBLISHED', "
                + "'{\"properties\":[{\"id\":\"power_off\"},{\"id\":\"power_on\"}],\"events\":[],\"actions\":[]}', now()) "
                + "ON CONFLICT (id) DO NOTHING", MODEL_ID, TENANT, PRODUCT_ID);
        // 负向产品：物模型未发布（published_model_id 为空）
        jdbc.update("INSERT INTO sw_iot_product (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, model_status, published_model_id) "
                + "VALUES (?, now(), now(), 0, ?, 0, 'P63-NOMODEL', 'P63未发布模型产品', 'MQTT', 'DRAFT', NULL) "
                + "ON CONFLICT (id) DO NOTHING", DRAFT_PRODUCT_ID, TENANT);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, "
                + "product_ref_id) VALUES (?, now(), now(), 0, ?, 0, ?, 'P63功能权威设备', 'ONLINE', 'P63PROD', ?, "
                + "'PUBLISHED', 1, ?) ON CONFLICT (id) DO NOTHING", DEVICE_ID, TENANT, DEVICE_KEY, DEVICE_KEY,
                PRODUCT_ID);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, "
                + "product_ref_id) VALUES (?, now(), now(), 0, ?, 0, ?, 'P63无模型设备', 'ONLINE', 'P63-NOMODEL', ?, "
                + "'PUBLISHED', 1, ?) ON CONFLICT (id) DO NOTHING", NO_MODEL_DEVICE_ID, TENANT,
                NO_MODEL_DEVICE_KEY, NO_MODEL_DEVICE_KEY, DRAFT_PRODUCT_ID);

        asUser(INITIATOR, () -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "P63功能权威表单", null, null);
            formDefService.saveConfig(draft.getId(),
                    "{\"schemaVersion\":1,\"title\":\"P63功能权威表单\",\"fields\":["
                            + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false},"
                            + "{\"name\":\"plan_time\",\"type\":\"DATE\",\"label\":\"预约时间\",\"required\":false,"
                            + "\"format\":\"datetime\"}]}");
            formDefService.publish(draft.getId());
            return null;
        });
        System.out.println("[P63-EV] fa-window boot ok pgPort=" + pg.getPort());
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

    // ==================== G03a 功能权威 ====================

    @Test
    @DisplayName("冻结前 fail-closed：设备产品未发布物模型 → 成功完成事务拒绝、零意图零外发")
    void freezeRejectsProductWithoutPublishedModel() {
        BpmProcessDef def = publishReservationProcess(NO_MODEL_DEVICE_ID, "power_off",
                clockDueText(Duration.ofMinutes(30)), 60);
        String recordId = submitExpectApproveTerminalFailed(def, "无模型产品预约");
        String pi = awaitInstanceStarted(recordId);
        assertThat(reservationCount(pi)).as("缺已发布物模型：零预约意图").isZero();
        assertThat(commandsFor(pi)).as("缺已发布物模型：零设备命令外发").isZero();
        System.out.println("[P63-EV] g03a.freeze-no-model record=" + recordId
                + " reservations=0 deviceCommands=0 approveTerminal=FAILED(fail-closed)");
    }

    @Test
    @DisplayName("冻结前 fail-closed：功能未在已发布物模型声明 → 事务拒绝、零意图零外发")
    void freezeRejectsUndeclaredFunction() {
        BpmProcessDef def = publishReservationProcess(DEVICE_ID, "rogue_switch",
                clockDueText(Duration.ofMinutes(30)), 60);
        String recordId = submitExpectApproveTerminalFailed(def, "未声明功能预约");
        String pi = awaitInstanceStarted(recordId);
        assertThat(reservationCount(pi)).as("功能未声明：零预约意图").isZero();
        assertThat(commandsFor(pi)).as("功能未声明：零设备命令外发").isZero();
        System.out.println("[P63-EV] g03a.freeze-undeclared record=" + recordId
                + " reservations=0 deviceCommands=0 approveTerminal=FAILED(fail-closed)");
    }

    @Test
    @DisplayName("冻结后模型失效 → 到点真实调度入口 FAILED 可查、零外发、审批不回滚")
    void functionRevokedAfterFreezeFailsAtDue() {
        BpmProcessDef def = publishReservationProcess(DEVICE_ID, "power_off",
                clockDueText(Duration.ofMinutes(30)), 60);
        submitAndApprove(def, "模型失效场景", clockDueText(Duration.ofMinutes(30)));
        Long approveCommandId = lastApprovedCommandId;
        assertThat(approveCommandId).as("审批命令句柄已捕获").isNotNull();
        String pi = currentPi;
        Map<String, Object> intent = awaitIntentRow(pi);
        assertThat(intent.get("status")).as("冻结时功能有效：PENDING").isEqualTo("PENDING");
        jdbc.update("UPDATE sw_iot_thing_model SET status = 'ARCHIVED' WHERE id = ?", MODEL_ID);

        // 推进到窗内到点（不越过窗口终点：认领先守卫届时才让位于功能重核）
        advanceClock(Duration.ofSeconds(30 * 60 + 5));
        dispatchJob.dispatchDueReservations();
        // 先恢复发布态再断言，防止本例失败污染后续用例的权威状态
        jdbc.update("UPDATE sw_iot_thing_model SET status = 'PUBLISHED' WHERE id = ?", MODEL_ID);

        assertThat(intentStatus(pi)).as("模型失效后到点：FAILED 可查").isEqualTo("FAILED");
        Map<String, Object> failed = jdbc.queryForMap(
                "SELECT status, reject_reason FROM sw_iot_command_reservation WHERE process_instance_id = ?", pi);
        assertThat(String.valueOf(failed.get("reject_reason"))).as("失败原因可读").isNotBlank();
        assertThat(commandsFor(pi)).as("功能失效：零设备命令外发").isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sw_bpm_command WHERE id = ?",
                String.class, approveCommandId)).as("审批不回滚（原命令保持 COMPLETED）").isEqualTo("COMPLETED");
        System.out.println("[P63-EV] g03a.revoke-at-due pi=" + pi + " intent=FAILED reason-readable"
                + " deviceCommands=0 approveCommand=" + approveCommandId + "=COMPLETED(not-rolled-back)");
    }

    @Test
    @DisplayName("latest 命令查询：本人正向返回终态；同租他用户/跨租户均为空；查询零副作用")
    void latestCommandScopedToSelfAndTenant() {
        BpmProcessDef def = publishReservationProcess(DEVICE_ID, "power_off",
                clockDueText(Duration.ofMinutes(30)), 60);
        submitAndApprove(def, "latest边界场景", clockDueText(Duration.ofMinutes(30)));
        String pi = currentPi;
        String taskId = lastTaskId;
        Long approveCommandId = lastApprovedCommandId;
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command", Integer.class);

        R<CommandStatusRespDTO> self = asUser(APPROVER,
                () -> commandController.latestForTask(taskId));
        assertThat(self.getData()).as("本人查询命中本人命令").isNotNull();
        assertThat(self.getData().getCommandId()).isEqualTo(approveCommandId);
        assertThat(self.getData().getStatus()).isEqualTo("COMPLETED");

        R<CommandStatusRespDTO> otherUser = asUser(OTHER_USER,
                () -> commandController.latestForTask(taskId));
        assertThat(otherUser.getData()).as("同租其他用户：不泄露他人命令").isNull();

        R<CommandStatusRespDTO> otherTenant = asUserOfTenant(TENANT2_USER, TENANT2,
                () -> commandController.latestForTask(taskId));
        assertThat(otherTenant.getData()).as("跨租户：不泄露命令").isNull();

        int after = jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command", Integer.class);
        assertThat(after).as("查询零副作用（不产生新命令）").isEqualTo(before);
        System.out.println("[P63-EV] g03a.latest pi=" + pi + " task=" + taskId + " self=" + approveCommandId
                + "=COMPLETED otherUser=null otherTenant=null commandDelta=0");
    }

    @Test
    @DisplayName("用户配置跨租越权：DESIGNATED 指定租户二用户 → 发布链权威校验拒绝、零任务副作用")
    void crossTenantDesignatedApproverRejectedWithZeroTaskSideEffects() {
        BpmProcessDef def = draftProcessWithDesignatedApprover(String.valueOf(TENANT2_USER));
        // 跨租户审批人在发布翻译写死 assignee 前被权威租户校验 fail-closed 拒绝（明确提示）
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asUser(INITIATOR, () -> {
            processDefService.publish(def.getId());
            return null;
        })).as("跨租户审批人配置在发布链被拒绝").isInstanceOf(RuntimeException.class);
        long assigned = app.getBean(org.flowable.engine.TaskService.class)
                .createTaskQuery().taskAssignee(String.valueOf(TENANT2_USER)).count();
        assertThat(assigned).as("跨租户用户零任务副作用").isZero();
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_process_def WHERE id = ? AND status = 'PUBLISHED'",
                Integer.class, def.getId());
        assertThat(published).as("跨租户配置未进入发布态").isZero();
        System.out.println("[P63-EV] g03a.cross-tenant-approver def=" + def.getId()
                + " publishRejected=true tenant2UserTasks=0 publishedVersions=0");
    }

    // ==================== G06a 窗后边界（受控时钟贯穿真实入口） ====================

    @Test
    @DisplayName("对账先：窗后 PENDING 经真实调度入口收敛 EXPIRED、零外发、不复活")
    void reconcileFirstExpiresPastWindowPendingWithoutDispatch() {
        BpmProcessDef def = publishReservationProcess(DEVICE_ID, "power_off",
                clockDueText(Duration.ofMinutes(30)), 60);
        String pi = submitAndApprove(def, "对账先场景", clockDueText(Duration.ofMinutes(30)));
        assertThat(intentStatus(pi)).isEqualTo("PENDING");

        advanceClock(Duration.ofSeconds(30 * 60 + 61));
        dispatchJob.dispatchDueReservations();

        assertThat(intentStatus(pi)).as("真实入口对账先：EXPIRED").isEqualTo("EXPIRED");
        assertThat(commandsFor(pi)).as("窗后零外发").isZero();
        advanceClock(Duration.ofMinutes(10));
        dispatchJob.dispatchDueReservations();
        assertThat(intentStatus(pi)).as("过期不复活").isEqualTo("EXPIRED");
        assertThat(commandsFor(pi)).as("重复扫描仍零外发").isZero();
        System.out.println("[P63-EV] g06a.reconcile-first pi=" + pi + " intent=EXPIRED commands=0"
                + " rescan=no-revival controlled-clock=real-entry");
    }

    @Test
    @DisplayName("认领先：对账滞后时窗后 PENDING 经认领路径直接拒绝（零外发）")
    void claimPathRefusesPastWindowDirectly() throws Exception {
        BpmProcessDef def = publishReservationProcess(DEVICE_ID, "power_off",
                clockDueText(Duration.ofMinutes(30)), 60);
        String pi = submitAndApprove(def, "认领先场景", clockDueText(Duration.ofMinutes(30)));
        assertThat(intentStatus(pi)).isEqualTo("PENDING");

        // 时钟推进过窗但真实对账入口尚未运行（对账滞后竞态窗口）
        advanceClock(Duration.ofSeconds(30 * 60 + 61));
        IotCommandReservation reservation;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            reservation = app.getBean(IotCommandReservationMapper.class).selectByInstance(TENANT, pi);
        }
        assertThat(reservation).isNotNull();
        Method claim = ReflectionUtils.findMethod(IotReservationDispatchJob.class,
                "claimAndDispatchSuspended", IotCommandReservation.class, LocalDateTime.class);
        ReflectionUtils.makeAccessible(claim);
        LocalDateTime nowUtc = LocalDateTime.ofInstant(clockNow.get(), ZoneOffset.UTC);
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            ReflectionUtils.invokeMethod(claim, dispatchJob, reservation, nowUtc);
        }

        assertThat(intentStatus(pi)).as("认领路径窗后直接拒绝：EXPIRED").isEqualTo("EXPIRED");
        assertThat(commandsFor(pi)).as("认领拒绝零外发").isZero();
        System.out.println("[P63-EV] g06a.claim-first pi=" + pi + " intent=EXPIRED commands=0"
                + " claim-guard=window-refused controlled-clock=real-entry");
    }

    @Test
    @DisplayName("窗内入队后窗外：真实发送入口过期守卫不外发、补偿入口不再重试")
    void commandEnqueuedInWindowNotDispatchedAfterWindow() {
        BpmProcessDef def = publishReservationProcess(DEVICE_ID, "power_off",
                clockDueText(Duration.ofMinutes(30)), 60);
        String pi = submitAndApprove(def, "窗外发送边界", clockDueText(Duration.ofMinutes(30)));
        assertThat(intentStatus(pi)).isEqualTo("PENDING");

        advanceClock(Duration.ofSeconds(30 * 60 + 5));
        dispatchJob.dispatchDueReservations();
        assertThat(intentStatus(pi)).as("窗内认领下发（无通道记可重试失败）").isEqualTo("DISPATCHED");
        Integer preRetry = jdbc.queryForObject("SELECT COUNT(*) FROM sw_iot_device_command "
                + "WHERE approval_biz_id = ?", Integer.class, pi);
        assertThat(preRetry).isEqualTo(1);
        Map<String, Object> command = jdbc.queryForMap(
                "SELECT id, status FROM sw_iot_device_command WHERE approval_biz_id = ?", pi);
        Long commandId = ((Number) command.get("id")).longValue();

        // 时钟越过窗口终点：真实发送入口守卫不外发（调度线程同口径挂起租户线）
        advanceClock(Duration.ofSeconds(56));
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            DeferredControlUtil sender = app.getBean(DeferredControlUtil.class);
            com.sw.ck.iot.entity.IotDeviceCommand queued = app.getBean(
                    com.sw.ck.iot.mapper.IotDeviceCommandMapper.class).selectById(commandId);
            sender.sendCommand(queued);
        }
        String afterSend = jdbc.queryForObject(
                "SELECT status FROM sw_iot_device_command WHERE id = ?", String.class, commandId);
        assertThat(afterSend).as("窗外真实发送入口：过期不外发").isEqualTo("EXPIRED");

        // 补偿入口不再返回过期命令（无重试路径）
        List<com.sw.ck.iot.entity.IotDeviceCommand> retryable = app.getBean(CommandQueueService.class)
                .findRetryableFailed(5, 50);
        assertThat(retryable).extracting("id").as("窗外补偿不重试").doesNotContain(commandId);
        assertThat(commandsFor(pi)).as("全程恰一条命令（不新增外发）").isEqualTo(1);
        System.out.println("[P63-EV] g06a.send-boundary pi=" + pi + " command=" + commandId
                + " final=EXPIRED sentNever=true retryableScan=excludes controlled-clock=real-entry");
    }

    // ==================== helpers ====================

    private void advanceClock(Duration d) {
        clockNow.set(clockNow.get().plus(d));
    }

    private String clockDueText(Duration offset) {
        LocalDateTime due = LocalDateTime.ofInstant(clockNow.get(), ZoneId.of("Asia/Shanghai")).plus(offset);
        return due.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private BpmProcessDef publishReservationProcess(Long deviceId, String commandKey, String dueText,
                                                    int lateWindowSeconds) {
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService
                .createDef("P63FA-" + commandKey + "-" + deviceId + "-" + System.nanoTime(), FORM_KEY));
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
        String actionJson = "{\"enabled\":true,\"deliveryMode\":\"RESERVATION\","
                + "\"deviceSource\":\"FIXED\",\"deviceId\":" + deviceId + ","
                + "\"commandKey\":\"" + commandKey + "\",\"paramField\":null,"
                + "\"reservation\":{\"dueField\":\"plan_time\",\"timezoneId\":\"Asia/Shanghai\","
                + "\"lateWindowSeconds\":" + lateWindowSeconds + "}}";
        asUser(INITIATOR, () -> {
            processDefService.saveDraftGraph(def.getId(), toJson(graph));
            processDefService.setIotDeviceAction(def.getId(), actionJson);
            processDefService.publish(def.getId());
            return null;
        });
        return def;
    }

    private BpmProcessDef draftProcessWithDesignatedApprover(String approverUserId) {
        BpmProcessDef def = asUser(INITIATOR, () -> processDefService
                .createDef("P63FA-CROSS-" + System.nanoTime(), FORM_KEY));
        List<GraphElement> elements = List.of(
                node("start", "START", null),
                node("approver", "APPROVAL", Map.of("name", "跨租户审批",
                        "approver", Map.of("type", "DESIGNATED", "value", approverUserId))),
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
            return null;
        });
        return def;
    }

    private String submitAndApprove(BpmProcessDef def, String topic, String dueText) {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", topic, "plan_time", dueText), null, null, null));
        String pi = awaitInstanceStarted(recordId);
        String taskId = awaitTask(pi);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("核准预约"), CommandChannelEnum.NORMAL));
        awaitCommandCompleted(accepted.getCommandId());
        this.currentPi = pi;
        this.lastTaskId = taskId;
        this.lastApprovedCommandId = accepted.getCommandId();
        return pi;
    }

    private String currentPi;
    private Long lastApprovedCommandId;
    private String lastTaskId;

    private String submitExpectApproveTerminalFailed(BpmProcessDef def, String topic) {
        String recordId = asUser(INITIATOR, () -> submitService.submitForm(FORM_KEY,
                data("topic", topic, "plan_time", clockDueText(Duration.ofMinutes(30))), null, null, null));
        String pi = awaitInstanceStarted(recordId);
        String taskId = awaitTask(pi);
        CommandAcceptRespDTO accepted = asUser(APPROVER, () -> acceptService.acceptTaskAction(taskId,
                ApprovalAction.APPROVE, req("核准预约"), CommandChannelEnum.NORMAL));
        awaitCommandTerminalFailed(accepted.getCommandId());
        return recordId;
    }

    private int commandsFor(String processInstanceId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_device_command WHERE approval_biz_id = ?",
                Integer.class, processInstanceId);
    }

    private int reservationCount(String processInstanceId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
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
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, failure_reason FROM sw_bpm_command WHERE id = ?", commandId);
            status = String.valueOf(row.get("status"));
            if ("FAILED".equals(status)) {
                assertThat(String.valueOf(row.get("failure_reason"))).as("拒绝原因可读").isNotBlank();
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
        return asUserOfTenant(userId, TENANT, action);
    }

    private static <T> T asUserOfTenant(Long userId, Long tenantId, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "workflow:def:publish",
                "workflow:command:accept")));
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

    /** 可设定时钟：instant 由测试显式推进，withZone 仍共享同一时间源。 */
    private static final class SettableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        SettableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zoneId) {
            return new SettableClock(now, zoneId);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
