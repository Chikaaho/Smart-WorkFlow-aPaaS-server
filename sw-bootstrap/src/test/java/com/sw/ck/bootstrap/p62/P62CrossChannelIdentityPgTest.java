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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核04 G3b 剩余项：真实同步入口与异步命令入口对同一租户同一逻辑业务操作
 * （同一待办任务的同一审批动作）使用同一幂等身份——另一入口取得原结果或明确
 * 同操作去重，且实际效果不增加。
 *
 * <p>证据口径：两入口均为真实 HTTP（debug 身份经 UserDetailsProvider 正式回查）；
 * 逐入口保留实际请求/响应原文、tenant/action/版本/逻辑键、命令与动作台账前后计数、
 * 关联结果回查输出。不重做停用冻结与旧 handler（已在 G3b 前一轮锁定）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 复核04 G3b：跨同步/异步入口统一幂等身份（真实PG+真实HTTP）")
class P62CrossChannelIdentityPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91357L;
    private static final String FORM_KEY = "p62_xchan_todo";
    private static final String PROCESS_NAME = "跨通道审批身份";

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
        String dir = System.getProperty("p62.g3b.evidence.dir");
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("必须提供 -Dp62.g3b.evidence.dir");
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
        props.put("sw.security.jwt.secret", "p62-g3b-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", P62BudgetMeasurementPgTest.generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-g3b-digest-secret");
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
                            new org.springframework.core.env.MapPropertySource("p62-g3b", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62G3bLoginContextProvider", provider);
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
            Files.writeString(evidenceDir.resolve("cross-channel-raw.txt"), raw.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            System.out.println("[P62-EV] g3b raw evidence written: "
                    + evidenceDir.resolve("cross-channel-raw.txt"));
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
                        + " '跨通道身份租户', 'localhost') ON CONFLICT (id) DO NOTHING",
                TENANT, "跨通道身份租户", "p62-xchan-t" + TENANT);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                        + "VALUES (?, ?, 'seed-not-a-login-secret', '跨通道身份操作员', ?, 0) "
                        + "ON CONFLICT (id) DO NOTHING", USER, "xchan-op-" + USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                        + "VALUES (?, ?, ?, 2) ON CONFLICT (id) DO NOTHING", USER, TENANT, USER);
        FormDefService formDefService = app.getBean(FormDefService.class);
        BpmProcessDefService processDefService = app.getBean(BpmProcessDefService.class);
        ProcessStartService startService = app.getBean(ProcessStartService.class);
        asTenant(() -> {
            FormDefDTO draft = formDefService.createDraft(FORM_KEY, "跨通道待办", null, null);
            formDefService.saveConfig(draft.getId(), "{\"schemaVersion\":1,\"title\":\"跨通道待办\","
                    + "\"fields\":[{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\"}]}");
            formDefService.publish(draft.getId());
            return null;
        });
        String processKey = asTenant(() -> {
            BpmProcessDef def = processDefService.createDef(PROCESS_NAME, FORM_KEY);
            List<GraphElement> elements = List.of(
                    node("start", "START"),
                    node("approve", "APPROVAL", Map.of("name", "跨通道审批",
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
                throw new IllegalStateException("跨通道流程发布失败: " + def.getProcessKey());
            }
            return published.getProcessKey();
        });
        raw.append("processKey=").append(processKey).append(" formKey=").append(FORM_KEY)
                .append(" tenant=").append(TENANT).append(" actor=").append(USER).append('\n');
    }

    /** 发起一个新的审批实例并返回真实待办任务标识（每次调用生成独立对象）。 */
    private String startPendingTask(String tag) throws Exception {
        ProcessStartService startService = app.getBean(ProcessStartService.class);
        String recordId = "XCHAN-" + tag + "-" + System.nanoTime();
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

    // ==================== 断言：跨同步/异步入口同一幂等身份 ====================

    @Test
    @DisplayName("异步入口先行→同步入口同操作：同身份判定、原结果/明确去重、效果恰一、命令可回查")
    void asyncFirstThenSync() throws Exception {
        String taskId = startPendingTask("async-first");
        String bearer = "Bearer test_" + USER;
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        long commandsBefore = countCommandsByKey(taskId);

        // 1. 异步命令入口（NORMAL 通道）受理同一逻辑操作
        String acceptResp = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                bearer, "{\"comment\":\"异步先行\"}");
        Long commandId = extractNumber(acceptResp, "commandId");
        raw.append("[async-first] accept response: ").append(acceptResp).append('\n');
        // 2. 结果回查（受理≠完成）
        String statusResp = awaitCommandTerminal(commandId, bearer);
        raw.append("[async-first] command status: ").append(statusResp).append('\n');
        assertTerminal(statusResp);

        // 3. 同步入口对同一逻辑操作（同租户/同任务/同动作/同操作人）
        String syncResp = post("/api/workflow/tasks/" + taskId + "/complete", bearer,
                "{\"comment\":\"同步跟进\"}");
        raw.append("[async-first] sync response: ").append(syncResp).append('\n');

        // 4. 实际效果不增加（动作台账/命令台账/通知）且同步入口同操作为明确去重成功
        long actionsAfter = countActions(taskId);
        long effectsAfter = countNotifications(taskId);
        long commandsAfter = countCommandsByKey(taskId);
        raw.append(String.format("[async-first] counters actions=%d->%d notifications=%d->%d "
                        + "commandsByKey=%d->%d%n",
                actionsBefore, actionsAfter, effectsBefore, effectsAfter,
                commandsBefore, commandsAfter));
        assertEffectExactlyOnce("async-first", syncResp, actionsBefore, actionsAfter,
                effectsBefore, effectsAfter);
        assertThat(commandsAfter).as("同身份重放不新增第二受理行")
                .isEqualTo(Math.max(1L, commandsBefore));
        writeRow("async-first", taskId, commandId, acceptResp, statusResp, syncResp,
                actionsBefore, actionsAfter, effectsBefore, effectsAfter);
    }

    @Test
    @DisplayName("同步入口先行→异步入口同操作：同身份判定、原结果/明确去重、效果恰一、命令可回查")
    void syncFirstThenAsync() throws Exception {
        String taskId = startPendingTask("sync-first");
        String bearer = "Bearer test_" + USER;
        long actionsBefore = countActions(taskId);
        long effectsBefore = countNotifications(taskId);
        long commandsBefore = countCommandsByKey(taskId);

        // 1. 同步 HTTP 入口先行
        String syncResp = post("/api/workflow/tasks/" + taskId + "/complete", bearer,
                "{\"comment\":\"同步先行\"}");
        raw.append("[sync-first] sync response: ").append(syncResp).append('\n');
        assertThat(syncResp).as("同步入口先行必须成功").contains("httpStatus=200").contains("\"code\":0");
        // 只读现场：同步入口提交后运行期任务已消失、历史行已完成（证明同操作不会再执行）
        raw.append("[sync-first] post-sync runtimeTaskRows=")
                .append(jdbc.queryForList("SELECT ID_ FROM ACT_RU_TASK WHERE ID_ = ?", taskId))
                .append(" historicTaskEnd=")
                .append(jdbc.queryForList("SELECT END_TIME_ FROM ACT_HI_TASKINST WHERE ID_ = ?", taskId))
                .append('\n');

        // 2. 异步命令入口对同一逻辑操作：必须恢复原结果（RECOVERED + 原动作记录标识），不得误报二次成功
        String acceptResp = post("/api/workflow/commands/tasks/" + taskId + "/complete?channel=NORMAL",
                bearer, "{\"comment\":\"异步跟进\"}");
        Long commandId = extractNumber(acceptResp, "commandId");
        raw.append("[sync-first] accept response: ").append(acceptResp).append('\n');
        String statusResp = awaitCommandTerminal(commandId, bearer);
        raw.append("[sync-first] command status: ").append(statusResp).append('\n');
        assertTerminal(statusResp);
        assertThat(statusResp).as("跨入口同操作必须恢复自身已提交结果")
                .contains("RECOVERED").contains("actionRecordId");

        long actionsAfter = countActions(taskId);
        long effectsAfter = countNotifications(taskId);
        long commandsAfter = countCommandsByKey(taskId);
        raw.append(String.format("[sync-first] counters actions=%d->%d notifications=%d->%d "
                        + "commandsByKey=%d->%d%n",
                actionsBefore, actionsAfter, effectsBefore, effectsAfter,
                commandsBefore, commandsAfter));
        assertEffectExactlyOnce("sync-first", syncResp, actionsBefore, actionsAfter,
                effectsBefore, effectsAfter);
        assertThat(commandsAfter).as("同身份重放不新增第二受理行")
                .isEqualTo(Math.max(1L, commandsBefore));
        writeRow("sync-first", taskId, commandId, acceptResp, statusResp, syncResp,
                actionsBefore, actionsAfter, effectsBefore, effectsAfter);
    }

    /** 正向：两道入口完成后该逻辑操作恰一条动作记录、恰一次通知；反向：无第二条效果。 */
    private void assertEffectExactlyOnce(String scenario, String secondEntryResp,
                                         long actionsBefore, long actionsAfter,
                                         long effectsBefore, long effectsAfter) {
        String label = "[" + scenario + "] ";
        assertThat(actionsBefore).as(label + "起态无动作记录").isZero();
        assertThat(actionsAfter).as(label + "两入口后动作记录恰一条").isEqualTo(1L);
        assertThat(effectsAfter).as(label + "通知不因第二入口增加").isEqualTo(effectsBefore);
        assertThat(secondEntryResp).as(label + "第二入口不得 500/异常")
                .doesNotContain("httpStatus=500").contains("httpStatus=200");
    }

    // ==================== HTTP 与核销工具 ====================

    private String post(String path, String bearer, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return "httpStatus=" + response.statusCode() + " body=" + response.body();
    }

    /** 按 commandId 回查命令终态（受理≠完成；有界轮询真实状态接口）。 */
    private String awaitCommandTerminal(Long commandId, String bearer) throws Exception {
        String last = null;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/workflow/commands/" + commandId))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization", bearer)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            last = "httpStatus=" + response.statusCode() + " body=" + response.body();
            if (response.body().contains("\"status\":\"COMPLETED\"")
                    || response.body().contains("\"status\":\"FAILED\"")) {
                return last;
            }
            Thread.sleep(100);
        }
        return last;
    }

    private void assertTerminal(String statusResp) {
        if (!statusResp.contains("\"status\":\"COMPLETED\"")) {
            throw new IllegalStateException("命令未达 COMPLETED: " + statusResp);
        }
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

    private void writeRow(String scenario, String taskId, Long commandId, String acceptResp,
                          String statusResp, String syncResp, long actionsBefore, long actionsAfter,
                          long effectsBefore, long effectsAfter) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario);
        row.put("tenant", TENANT);
        row.put("actor", USER);
        row.put("taskId", taskId);
        row.put("commandId", commandId);
        row.put("logicKey", "TASK_APPROVE:" + taskId + ":" + USER);
        row.put("acceptResponse", acceptResp);
        row.put("statusResponse", statusResp);
        row.put("syncResponse", syncResp);
        row.put("actionsBefore", actionsBefore);
        row.put("actionsAfter", actionsAfter);
        row.put("notificationsBefore", effectsBefore);
        row.put("notificationsAfter", effectsAfter);
        row.put("recordedAt", LocalDateTime.now().format(TS));
        String json = app.getBean(com.fasterxml.jackson.databind.ObjectMapper.class)
                .writeValueAsString(row);
        Files.writeString(evidenceDir.resolve("cross-channel-" + scenario + ".json"), json + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** R 契约的雪花标识以字符串渲染（如 "commandId":"2105..."）：取带引号或不带引号的标量再解析。 */
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
