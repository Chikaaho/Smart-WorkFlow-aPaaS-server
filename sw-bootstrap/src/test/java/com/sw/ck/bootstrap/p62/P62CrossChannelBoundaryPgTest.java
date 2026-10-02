package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.ProcessStartService;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示04 G3b 反向边界：b6e9a45 引入的跨入口恢复分支不得吞掉不同有效载荷或
 * 他人/异动作提交——同键异载荷不覆盖原结果、不同操作人与异动作保持确定性冲突、
 * 原动作/通知/结果不增加。全部场景真实 PG + 真实 HTTP，逐场景记录命令 payload
 * 原文与 payload_fingerprint 实际值（有效载荷/入口字段事实）、动作记录字段读回
 * 与前后效果计数；仅 HTTP 200 或任务已无不构成边界通过依据。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("P62 提示04 G3b 反向边界：恢复分支不吞异载荷、不恢复他人/异动作（真实PG+真实HTTP）")
class P62CrossChannelBoundaryPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91357L;
    private static final Long OTHER_USER = 91358L;
    private static final String FORM_KEY = "p62_xbnd_todo";
    private static final String PROCESS_NAME = "恢复分支反向边界";
    private static final String ALREADY_HANDLED_CODE = "2305";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    private Path evidenceDir;
    private final StringBuilder raw = new StringBuilder();

    @BeforeAll
    void boot() throws Exception {
        String dir = System.getProperty("p62.boundary.evidence.dir");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("必须提供 -Dp62.boundary.evidence.dir");
        }
        evidenceDir = Path.of(dir);
        Files.createDirectories(evidenceDir);
        raw.append("runId=").append(System.getProperty("p62.runId", "")).append('\n');
        raw.append("buildCommit=").append(System.getProperty("p62.build.commit", "")).append('\n');
        raw.append("bootedAt=").append(LocalDateTime.now().format(TS)).append('\n');

        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-xbnd-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", P62BudgetMeasurementPgTest.generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-xbnd-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("spring.datasource.dynamic.druid.initial-size", "5");
        props.put("spring.datasource.dynamic.druid.min-idle", "5");
        props.put("spring.datasource.dynamic.druid.max-active", "16");
        props.put("spring.datasource.dynamic.druid.max-wait", "10000");
        props.put("sw.bpm.command.poll-interval-millis", "100");
        props.put("sw.bpm.command.p0-poll-interval-millis", "100");
        props.put("sw.bpm.command.batch-size", "20");
        props.put("sw.security.debug-auth.enabled", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-xbnd", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62XbndLoginContextProvider", provider);
                    context.addBeanFactoryPostProcessor(bf -> {
                        if (bf.containsBeanDefinition("loginUserCacheService")) {
                            ((org.springframework.beans.factory.support.DefaultListableBeanFactory) bf)
                                    .setAllowBeanDefinitionOverriding(true);
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
        jdbc = app.getBean(JdbcTemplate.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        raw.append("pgPort=").append(pg.getPort()).append(" httpPort=").append(port).append('\n');
        seedApprovalProcess();
    }

    @AfterAll
    void tearDown() throws Exception {
        try {
            Files.writeString(evidenceDir.resolve("boundary-raw.txt"), raw.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            System.out.println("[P62-EV] g3b-boundary raw evidence written: "
                    + evidenceDir.resolve("boundary-raw.txt"));
        } finally {
            if (app != null) {
                app.close();
            }
            if (pg != null) {
                pg.close();
            }
            LoginUserHolder.clear();
        }
    }

    // ==================== 种子：真实待办业务对象 ====================

    private void seedApprovalProcess() throws Exception {
        jdbc.update("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id,"
                        + " version, name, code, status, description, domain_name) "
                        + "VALUES (?, current_timestamp, current_timestamp, 0, 0, 0, ?, ?, 0,"
                        + " '反向边界租户', 'localhost') ON CONFLICT (id) DO NOTHING",
                TENANT, "反向边界租户", "p62-xbnd-t" + TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                        + "VALUES (?, ?, 'seed-not-a-login-secret', '反向边界操作员甲', ?, 0) "
                        + "ON CONFLICT (id) DO NOTHING", USER, "xbnd-op-" + USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, 2) ON CONFLICT (id) DO NOTHING", USER, TENANT, USER);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                        + "VALUES (?, ?, 'seed-not-a-login-secret', '反向边界操作员乙', ?, 0) "
                        + "ON CONFLICT (id) DO NOTHING", OTHER_USER, "xbnd-op-" + OTHER_USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                + "VALUES (?, ?, ?, 2) ON CONFLICT (id) DO NOTHING", OTHER_USER, TENANT, OTHER_USER);
        FormDefService formDefService = app.getBean(FormDefService.class);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        ProcessStartService startService = app.getBean(ProcessStartService.class);
        asTenant(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "反向边界待办", null, null);
            formDefService.saveConfig(draft.getId(), "{\"schemaVersion\":1,\"title\":\"反向边界待办\","
                    + "\"fields\":[{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\"}]}");
            formDefService.publish(draft.getId());
            return null;
        });
        String processKey = asTenant(() -> {
            BpmProcessDef def = processDefService.createDef(PROCESS_NAME, FORM_KEY);
            List<GraphElement> elements = List.of(
                    node("start", "START"),
                    node("approve", "APPROVAL", Map.of("name", "反向边界审批",
                            "approver", Map.of("type", "DESIGNATED",
                                    "value", List.of(String.valueOf(USER))))),
                    node("end", "END"),
                    edge("e1", "start", "approve"),
                    edge("e2", "approve", "end"));
            ProcessGraph graph = ProcessGraph.builder()
                    .processKey(def.getProcessKey()).name(PROCESS_NAME)
                    .formKey(FORM_KEY).version(1).elements(elements).build();
            String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                    .writeValueAsString(graph);
            processDefService.saveDraftGraph(def.getId(), json);
            BpmProcessDef published = processDefService.publish(def.getId());
            if (!"PUBLISHED".equals(published.getStatus())) {
                throw new IllegalStateException("反向边界流程发布失败: " + def.getProcessKey());
            }
            return published.getProcessKey();
        });
        raw.append("processKey=").append(processKey).append(" formKey=").append(FORM_KEY)
                .append(" tenant=").append(TENANT)
                .append(" actor=").append(USER).append(" otherActor=").append(OTHER_USER).append('\n');
    }

    /** 发起一个新的审批实例并返回真实待办任务标识（每次调用生成独立对象）。 */
    private String startPendingTask(String tag) throws Exception {
        ProcessStartService startService = app.getBean(ProcessStartService.class);
        String recordId = "XBND-" + tag + "-" + System.nanoTime();
        asTenant(() -> {
            com.sw.ck.bpm.process.dto.StartCommand cmd = new com.sw.ck.bpm.process.dto.StartCommand();
            cmd.setFormKey(FORM_KEY);
            cmd.setRecordId(recordId);
            cmd.setSubmitter(USER);
            cmd.setTenantId(TENANT);
            cmd.setSubmittedData(Map.of("material", tag));
            startService.start(cmd);
            return null;
        });
        String taskId = jdbc.queryForObject(
                "SELECT ID_ FROM ACT_RU_TASK WHERE ASSIGNEE_ = ? ORDER BY CREATE_TIME_ DESC LIMIT 1",
                String.class, String.valueOf(USER));
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("待办未生成: recordId=" + recordId);
        }
        raw.append("task-started tag=").append(tag).append(" recordId=").append(recordId)
                .append(" taskId=").append(taskId).append(" ts=")
                .append(LocalDateTime.now().format(TS)).append('\n');
        return taskId;
    }

    // ==================== 场景1：异步同键异载荷（受理幂等命中、原结果不覆盖） ====================

    @Test
    @Order(1)
    @DisplayName("异步同键异载荷：受理命中原命令不新建，原命令结果与动作记录载荷不覆盖，效果不增")
    void sameKeyDifferentPayloadAsync() throws Exception {
        String taskId = startPendingTask("asp");
        String bearer = bearer(USER);
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        long commandsBefore = countCommandsByKey(taskId);

        // 1. 异步受理 APPROVE（载荷 comment=第一载荷），等待业务终态
        String acceptResp = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                bearer, "{\"comment\":\"第一载荷\"}");
        raw.append("[async-payload] accept-1: ").append(acceptResp).append('\n');
        Long commandId = extractNumber(acceptResp, "commandId");
        String statusResp = awaitCommandTerminal(commandId, bearer, 60_000);
        raw.append("[async-payload] status-1: ").append(statusResp).append('\n');
        assertThat(statusResp).as("首次异步执行必须成功").contains("\"status\":\"COMPLETED\"");
        String payloadBefore = commandPayload(commandId);
        String fingerprintBefore = commandFingerprint(commandId);
        String recordBefore = actionRecordJson(taskId);
        raw.append("[async-payload] payload-1: ").append(payloadBefore)
                .append(" fingerprint=").append(fingerprintBefore).append('\n');
        raw.append("[async-payload] record-1: ").append(recordBefore).append('\n');

        // 2. 同键（TASK_APPROVE:taskId:actor）异载荷（comment=第二载荷）再次受理
        String replayResp = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                bearer, "{\"comment\":\"第二载荷\"}");
        raw.append("[async-payload] accept-2(same key, different payload): ").append(replayResp).append('\n');
        Long replayCommandId = extractNumber(replayResp, "commandId");

        // 3. 反向断言：不新建第二命令、原命令结果不变、原动作记录载荷不覆盖、效果不增
        assertThat(replayCommandId).as("同键异载荷必须命中原命令受理标识，不得新建")
                .isEqualTo(commandId);
        assertThat(countCommandsByKey(taskId)).as("同键异载荷不产生第二条命令行")
                .isEqualTo(commandsBefore + 1);
        String statusAfter = awaitCommandTerminal(commandId, bearer, 5_000);
        raw.append("[async-payload] status-after-replay: ").append(statusAfter).append('\n');
        assertThat(statusAfter).as("原命令终态保持 COMPLETED 且结果仍指向原动作记录")
                .contains("\"status\":\"COMPLETED\"").contains("actionRecordId");
        assertThat(commandPayload(commandId)).as("原命令 payload 不被异载荷覆盖").isEqualTo(payloadBefore);
        assertThat(commandFingerprint(commandId))
                .as("payload_fingerprint 实际值不变（受理入口现状：未写入指纹）")
                .isEqualTo(fingerprintBefore);
        String recordAfter = actionRecordJson(taskId);
        raw.append("[async-payload] record-after-replay: ").append(recordAfter).append('\n');
        assertThat(recordAfter).as("动作记录载荷字段不因异载荷重放覆盖").isEqualTo(recordBefore);
        assertThat(recordBefore).as("原记录保留第一载荷的 comment").contains("第一载荷");
        long actionsAfter = countActions(taskId);
        long effectsAfter = countNotifications(taskId);
        raw.append(String.format("[async-payload] counters actions=%d->%d notifications=%d->%d "
                        + "commandsByKey=%d->%d%n",
                actionsBefore, actionsAfter, effectsBefore, effectsAfter,
                commandsBefore, countCommandsByKey(taskId)));
        assertThat(actionsAfter).as("动作记录恰一条").isEqualTo(1L);
        assertThat(effectsAfter).as("通知不增加").isEqualTo(effectsBefore);
    }

    // ==================== 场景2：同步同身份异载荷（恢复成功、原记录不覆盖） ====================

    @Test
    @Order(2)
    @DisplayName("同步同身份异载荷：恢复原结果不覆盖原记录载荷字段，效果不增（等价语义：任务已消失后载荷无生效路径）")
    void sameIdentityDifferentPayloadSync() throws Exception {
        String taskId = startPendingTask("ssp");
        String bearer = bearer(USER);
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);

        String firstResp = post("/api/workflow/tasks/" + taskId + "/complete", bearer,
                "{\"comment\":\"同步第一载荷\"}");
        raw.append("[sync-payload] sync-1: ").append(firstResp).append('\n');
        assertThat(firstResp).as("同步首次执行必须成功")
                .contains("httpStatus=200").contains("\"code\":0");
        String recordBefore = actionRecordJson(taskId);
        raw.append("[sync-payload] record-1: ").append(recordBefore).append('\n');

        // 任务已消失后：同 (任务, 操作人, 动作) 异载荷重放——恢复分支只读身份字段，不读不写载荷字段
        String replayResp = post("/api/workflow/tasks/" + taskId + "/complete", bearer,
                "{\"comment\":\"同步第二载荷\",\"opinionData\":{\"score\":999}}");
        raw.append("[sync-payload] sync-2(same identity, different payload): ").append(replayResp).append('\n');

        String recordAfter = actionRecordJson(taskId);
        raw.append("[sync-payload] record-after-replay: ").append(recordAfter).append('\n');
        assertThat(recordAfter).as("原动作记录不因异载荷重放覆盖").isEqualTo(recordBefore);
        assertThat(recordBefore).as("原记录保留首次载荷 comment").contains("同步第一载荷");
        long actionsAfter = countActions(taskId);
        long effectsAfter = countNotifications(taskId);
        long runtimeRows = countRuntimeTasks(taskId);
        raw.append(String.format("[sync-payload] counters actions=%d->%d notifications=%d->%d "
                        + "runtimeTasks=%d%n",
                actionsBefore, actionsAfter, effectsBefore, effectsAfter, runtimeRows));
        assertThat(actionsAfter).as("动作记录恰一条").isEqualTo(1L);
        assertThat(effectsAfter).as("通知不增加").isEqualTo(effectsBefore);
        assertThat(runtimeRows).as("任务已消失（重放未复活任务）").isZero();
    }

    // ==================== 场景3：不同操作人同步重放（确定性冲突） ====================

    @Test
    @Order(3)
    @DisplayName("不同操作人同步重放：不恢复为他人成功，返回已处理冲突，原记录/效果不变")
    void differentActorSyncRejected() throws Exception {
        String taskId = startPendingTask("osy");
        String ownerBearer = bearer(USER);
        String otherBearer = bearer(OTHER_USER);

        String firstResp = post("/api/workflow/tasks/" + taskId + "/complete", ownerBearer,
                "{\"comment\":\"操作员甲完成\"}");
        raw.append("[other-sync] sync-1(owner): ").append(firstResp).append('\n');
        assertThat(firstResp).as("操作人甲首次执行必须成功")
                .contains("httpStatus=200").contains("\"code\":0");
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        String recordBefore = actionRecordJson(taskId);

        // 操作人乙对甲已完成的同一任务重放：必须确定性冲突，不得恢复为乙的成功
        String otherResp = post("/api/workflow/tasks/" + taskId + "/complete", otherBearer,
                "{\"comment\":\"操作员乙重放\"}");
        raw.append("[other-sync] sync-2(other actor): ").append(otherResp).append('\n');
        assertThat(otherResp).as("不同操作人必须被拒绝且携带已处理冲突码 " + ALREADY_HANDLED_CODE)
                .contains(ALREADY_HANDLED_CODE)
                .as("不同操作人不得恢复为成功（code=0 即失败）").doesNotContain("\"code\":0");

        assertThat(countActions(taskId)).as("动作记录不增加").isEqualTo(actionsBefore);
        assertThat(countNotifications(taskId)).as("通知不增加").isEqualTo(effectsBefore);
        assertThat(actionRecordJson(taskId)).as("原记录不变（操作人仍为甲）").isEqualTo(recordBefore);
        assertThat(recordBefore).as("原记录 actor 为甲（JdbcTemplate 读回为 Map.toString 格式）")
                .contains("actor_id=" + USER);
        raw.append(String.format("[other-sync] counters actions=%d notifications=%d%n",
                countActions(taskId), countNotifications(taskId)));
    }

    // ==================== 场景4：不同操作人异步命令（命令失败终态，非恢复成功） ====================

    @Test
    @Order(4)
    @DisplayName("不同操作人异步命令：按受理人键新建受理行，消费冲突重试至 FAILED，不得恢复为 COMPLETED")
    void differentActorAsyncCommandFails() throws Exception {
        String taskId = startPendingTask("oas");
        String ownerBearer = bearer(USER);
        String otherBearer = bearer(OTHER_USER);

        String ownerAccept = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                ownerBearer, "{\"comment\":\"甲异步完成\"}");
        Long ownerCommandId = extractNumber(ownerAccept, "commandId");
        raw.append("[other-async] accept-1(owner): ").append(ownerAccept).append('\n');
        String ownerStatus = awaitCommandTerminal(ownerCommandId, ownerBearer, 60_000);
        raw.append("[other-async] status-1(owner): ").append(ownerStatus).append('\n');
        assertThat(ownerStatus).as("甲的命令必须成功").contains("\"status\":\"COMPLETED\"");
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        long commandsBefore = countCommandsByKey(taskId);
        String recordBefore = actionRecordJson(taskId);

        // 乙对同一任务提交异步命令（键 TASK_APPROVE:taskId:乙，合法新受理行）：
        // 消费时任务已消失、既有记录属甲——必须冲突失败，不得恢复为乙的成功
        String otherAccept = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                otherBearer, "{\"comment\":\"乙异步重放\"}");
        Long otherCommandId = extractNumber(otherAccept, "commandId");
        raw.append("[other-async] accept-2(other actor): ").append(otherAccept).append('\n');
        assertThat(otherCommandId).as("异操作人键不同，产生新受理行").isNotEqualTo(ownerCommandId);
        String otherStatus = awaitCommandFailed(otherCommandId, otherBearer, 120_000);
        raw.append("[other-async] status-2(other actor terminal): ").append(otherStatus).append('\n');
        assertThat(otherStatus).as("乙的命令必须失败终态，不得 COMPLETED/RECOVERED")
                .contains("\"status\":\"FAILED\"").doesNotContain("\"status\":\"COMPLETED\"");
        assertThat(otherStatus).as("失败原因含已处理冲突语义（failure_reason 存异常 message）")
                .contains("节点已被处理");

        assertThat(countActions(taskId)).as("动作记录不增加").isEqualTo(actionsBefore);
        assertThat(countNotifications(taskId)).as("通知不增加").isEqualTo(effectsBefore);
        assertThat(countCommandsByKey(taskId)).as("命令行恰两条（甲、乙各一）").isEqualTo(commandsBefore + 1);
        assertThat(actionRecordJson(taskId)).as("原记录不变").isEqualTo(recordBefore);
        raw.append(String.format("[other-async] counters actions=%d notifications=%d commandsByKey=%d%n",
                countActions(taskId), countNotifications(taskId), countCommandsByKey(taskId)));
    }

    // ==================== 场景5：异动作同步重放（APPROVE 已提交后 REJECT 冲突） ====================

    @Test
    @Order(5)
    @DisplayName("异动作同步重放：同任务同操作人 REJECT 不被恢复为成功，原 APPROVE 记录与效果不变")
    void differentActionSyncRejected() throws Exception {
        String taskId = startPendingTask("acs");
        String bearer = bearer(USER);

        String approveResp = post("/api/workflow/tasks/" + taskId + "/complete", bearer,
                "{\"comment\":\"先同意\"}");
        raw.append("[action-sync] complete-1: ").append(approveResp).append('\n');
        assertThat(approveResp).contains("httpStatus=200").contains("\"code\":0");
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        String recordBefore = actionRecordJson(taskId);

        // 同任务同操作人异动作（REJECT）：不得被恢复分支吞成成功
        String rejectResp = post("/api/workflow/tasks/" + taskId + "/reject", bearer,
                "{\"comment\":\"后驳回\"}");
        raw.append("[action-sync] reject-2(different action): ").append(rejectResp).append('\n');
        assertThat(rejectResp).as("异动作必须被拒绝且携带已处理冲突码 " + ALREADY_HANDLED_CODE)
                .contains(ALREADY_HANDLED_CODE)
                .as("异动作不得恢复为成功（code=0 即失败）").doesNotContain("\"code\":0");

        assertThat(countActions(taskId)).as("动作记录恰一条（原 APPROVE）").isEqualTo(actionsBefore);
        assertThat(countNotifications(taskId)).as("通知不增加").isEqualTo(effectsBefore);
        String recordAfter = actionRecordJson(taskId);
        assertThat(recordAfter).as("原记录不变（仍为 APPROVE）").isEqualTo(recordBefore);
        assertThat(recordBefore).as("原记录动作为 APPROVE（JdbcTemplate 读回为 Map.toString 格式）")
                .contains("action=APPROVE");
        raw.append(String.format("[action-sync] counters actions=%d notifications=%d record=%s%n",
                countActions(taskId), countNotifications(taskId), recordAfter));
    }

    // ==================== 场景6：异动作异步命令（REJECT 命令失败终态） ====================

    @Test
    @Order(6)
    @DisplayName("异动作异步命令：REJECT 键新建受理行，消费冲突至 FAILED，原 APPROVE 记录与效果不变")
    void differentActionAsyncCommandFails() throws Exception {
        String taskId = startPendingTask("aca");
        String bearer = bearer(USER);

        String approveAccept = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                bearer, "{\"comment\":\"异步先同意\"}");
        Long approveCommandId = extractNumber(approveAccept, "commandId");
        raw.append("[action-async] accept-1(APPROVE): ").append(approveAccept).append('\n');
        String approveStatus = awaitCommandTerminal(approveCommandId, bearer, 60_000);
        raw.append("[action-async] status-1(APPROVE): ").append(approveStatus).append('\n');
        assertThat(approveStatus).contains("\"status\":\"COMPLETED\"");
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        long commandsBefore = countCommandsByKey(taskId);
        String recordBefore = actionRecordJson(taskId);

        // 同任务同操作人异动作异步命令（键 TASK_REJECT:taskId:actor）
        String rejectAccept = post("/api/workflow/commands/tasks/" + taskId + "/reject?channel=NORMAL",
                bearer, "{\"comment\":\"异步后驳回\"}");
        Long rejectCommandId = extractNumber(rejectAccept, "commandId");
        raw.append("[action-async] accept-2(REJECT, different action): ").append(rejectAccept).append('\n');
        assertThat(rejectCommandId).isNotEqualTo(approveCommandId);
        String rejectStatus = awaitCommandFailed(rejectCommandId, bearer, 120_000);
        raw.append("[action-async] status-2(REJECT terminal): ").append(rejectStatus).append('\n');
        assertThat(rejectStatus).as("异动作命令必须失败终态，不得 COMPLETED/RECOVERED")
                .contains("\"status\":\"FAILED\"").doesNotContain("\"status\":\"COMPLETED\"");
        assertThat(rejectStatus).as("失败原因含已处理冲突语义（failure_reason 存异常 message）")
                .contains("节点已被处理");

        assertThat(countActions(taskId)).as("动作记录恰一条（原 APPROVE）").isEqualTo(actionsBefore);
        assertThat(countNotifications(taskId)).as("通知不增加").isEqualTo(effectsBefore);
        assertThat(countCommandsByKey(taskId)).as("命令行恰两条（APPROVE、REJECT 各一）")
                .isEqualTo(commandsBefore + 1);
        assertThat(actionRecordJson(taskId)).as("原记录不变").isEqualTo(recordBefore);
        raw.append(String.format("[action-async] counters actions=%d notifications=%d commandsByKey=%d%n",
                countActions(taskId), countNotifications(taskId), countCommandsByKey(taskId)));
    }

    // ==================== HTTP、读回与计数工具 ====================

    private String bearer(Long userId) {
        return "Bearer test_" + userId;
    }

    private String post(String path, String bearerToken, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", bearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return "httpStatus=" + response.statusCode() + " body=" + response.body();
    }

    /** 等待命令到 COMPLETED（有界轮询真实状态接口，非 sleep 空转：绑定具体退出条件）。 */
    private String awaitCommandTerminal(Long commandId, String bearerToken, long timeoutMillis)
            throws Exception {
        String last = null;
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            last = fetchCommandStatus(commandId, bearerToken);
            if (last.contains("\"status\":\"COMPLETED\"") || last.contains("\"status\":\"FAILED\"")) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    /** 等待命令到 FAILED（冲突场景专用退出条件；不得以超时 COMPLETED 冒充边界通过）。 */
    private String awaitCommandFailed(Long commandId, String bearerToken, long timeoutMillis)
            throws Exception {
        String last = awaitCommandTerminal(commandId, bearerToken, timeoutMillis);
        if (last == null || !last.contains("\"status\":\"FAILED\"")) {
            throw new IllegalStateException("命令未达 FAILED 终态: " + last);
        }
        return last;
    }

    private String fetchCommandStatus(Long commandId, String bearerToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/workflow/commands/" + commandId))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", bearerToken)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return "httpStatus=" + response.statusCode() + " body=" + response.body();
    }

    /** 命令行原文读回：payload 与 payload_fingerprint 实际值（有效载荷/入口字段事实）。 */
    private String commandPayload(Long commandId) {
        return jdbc.queryForObject(
                "SELECT payload FROM sw_bpm_command WHERE id = ?", String.class, commandId);
    }

    private String commandFingerprint(Long commandId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(payload_fingerprint, '<NULL>') FROM sw_bpm_command WHERE id = ?",
                String.class, commandId);
    }

    /** 动作记录字段读回（恰一条语义下的唯一行：actor/action/command_id/opinion_data）。 */
    private String actionRecordJson(String taskId) {
        return jdbc.queryForList(
                "SELECT id, actor_id, action, command_id, opinion_data "
                        + "FROM sw_bpm_approval_action WHERE task_id = ? ORDER BY id", taskId).toString();
    }

    private long countActions(String taskId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_approval_action WHERE task_id = ?", Long.class, taskId);
        return value == null ? -1 : value;
    }

    private long countNotifications(String taskId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message WHERE biz_id = ?",
                Long.class, taskId);
        return value == null ? -1 : value;
    }

    private long countCommandsByKey(String taskId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command WHERE command_key LIKE ?",
                Long.class, "%" + taskId + "%");
        return value == null ? -1 : value;
    }

    private long countRuntimeTasks(String taskId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ACT_RU_TASK WHERE ID_ = ?", Long.class, taskId);
        return value == null ? -1 : value;
    }

    /** R 契约的雪花标识以字符串渲染：取带引号或不带引号的标量再解析。 */
    private static Long extractNumber(String text, String field) {
        int i = text == null ? -1 : text.indexOf("\"" + field + "\"");
        if (i < 0) {
            return null;
        }
        int colon = text.indexOf(':', i) + 1;
        while (colon < text.length() && text.charAt(colon) == ' ') {
            colon++;
        }
        if (colon >= text.length()) {
            return null;
        }
        String value;
        if (text.charAt(colon) == '"') {
            int end = text.indexOf('"', colon + 1);
            if (end < 0) {
                return null;
            }
            value = text.substring(colon + 1, end);
        } else {
            int end = colon;
            while (end < text.length() && (text.charAt(end) == '-' || Character.isDigit(text.charAt(end)))) {
                end++;
            }
            value = text.substring(colon, end);
        }
        return value.isBlank() ? null : Long.valueOf(value.trim());
    }

    private <T> T asTenant(Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "workflow:p0:dispatch")));
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

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type)
                .config(config == null ? Map.of() : config).style(Map.of()).build();
    }

    private static GraphElement node(String id, String type) {
        return node(id, type, Map.of());
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target)
                .config(Map.of()).style(Map.of()).build();
    }
}
