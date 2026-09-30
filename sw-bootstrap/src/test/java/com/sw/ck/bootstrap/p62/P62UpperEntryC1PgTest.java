package com.sw.ck.bootstrap.p62;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.queue.CommandDispatcher;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.form.txn.model.C1PolicyModel;
import com.sw.ck.form.txn.service.C1PolicyService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtTokenProvider;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LT02a：两个上层可达写入口的 C1 约束行为证据（真实 HTTP + 真实 PG + 真实消费者）。
 * <p>
 * 审查02 LT02a 的不可接受证据是「源码委托链 + 下层闸门」——底层通过不能证明上层
 * 路由实际到达并把请求交给受保护的写入。本用例对两个可达入口发真实 HTTP 请求：
 * <ul>
 *   <li>草稿提交 {@code POST /api/workflow/drafts/{id}/submit}（JWT 身份）→ 真实受理
 *       → 由调度器同源车道入口 pollNormal 消费（同一 Spring 上下文）→ Facade → submitForm；</li>
 *   <li>OpenAPI 发起 {@code POST /api/openapi/v1/processes}（HMAC 签名鉴权，测试侧独立实现
 *       签名口径，不使用服务端签名工具）→ 同步落到同一提交链。</li>
 * </ul>
 * 受保护表单：上层请求实际到达写入前置闸门并按 C1 明确拒绝，动态宽表零行、
 * 无 FLOW_START 命令/流程实例等非预期副作用，拒绝记录按既有契约保留
 *（草稿：命令终态 FAILED + 失败原因 + 草稿 last_error；OpenAPI：R 信封 errorKey/eventRef）。
 * 未保护表单走同一入口同一身份：真实落库成功，证明链路本身可写（非“一律拒绝”）。
 * </p><p>
 * 隔离：zonky 内嵌真实 PostgreSQL；自动轮询车道以 1 小时间隔配置静默，消费者由
 * 测试直驱 {@code CommandDispatcher#pollNormal()}（生产调度器调用的同一方法，
 * 仅改触发时机，不复制消费逻辑、不 mock 写入与 C1 服务）。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 上层写入口 C1 约束（草稿提交 / OpenAPI，真实 HTTP + 真实 PG）")
class P62UpperEntryC1PgTest {

    private static final Long TENANT = 0L;
    private static final Long USER_ID = 1L;
    private static final String PROTECTED_KEY = "p62_upper_c1";
    private static final String PLAIN_KEY = "p62_upper_plain";
    private static final String PROCESS_DEF_KEY = "p62_upper_flow";
    private static final String APP_ID = "p62-upper-entry-app";
    private static final String APP_SECRET = "p62-upper-entry-secret-0123456789abcdef";
    private static final int C1_WRITE_PROTECTED = 1612;
    /** R 信封成功码（R.ok 口径，非 HTTP 状态码）。 */
    private static final int R_OK = 0;

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;
    private HttpClient http;
    private String baseUrl;
    private String token;
    private CommandDispatcher dispatcher;
    private String protectedTable;
    private String plainTable;
    private String protectedFormId;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-upper-entry-jwt-secret-0123456789abcdef0123456789");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-upper-entry-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // 自动轮询车道静默（首触发在 1 小时后）：消费者由测试直驱，避免与断言竞态；其余参数保持生产语义
        props.put("sw.bpm.command.poll-interval-millis", "3600000");
        props.put("sw.bpm.command.p0-poll-interval-millis", "3600000");
        props.put("sw.bpm.command.max-retries", "0");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-upper-entry", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62UpperEntryLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        objectMapper = app.getBean(ObjectMapper.class);
        dispatcher = app.getBean(CommandDispatcher.class);
        int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) app)
                .getWebServer().getPort();
        baseUrl = "http://127.0.0.1:" + port + "/api";
        token = app.getBean(JwtTokenProvider.class).generateToken(USER_ID);
        http = HttpClient.newHttpClient();
        seed();
        System.out.println("[P62-EV] lt02a.boot http=" + baseUrl + " seed=protected+plain forms, binding, openapi-app");
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
    }

    // ==================== 入口 5：BPM 草稿提交 ====================

    @Test
    @DisplayName("草稿提交入口：受保护表单真实受理→消费者到达写入闸门被 C1 拒绝，零行零流程且拒绝可查")
    void draftSubmitEntryEnforcesC1WithNoSideEffects() throws Exception {
        long commandsBefore = countCommands();
        long rowsBefore = countRows(protectedTable);
        long flowStartBefore = countFlowStartCommands();
        long instancesBefore = countProcessInstances();
        JsonNode draft = createDraft(PROTECTED_KEY, "LT02a 受保护草稿",
                Map.of("material", "LT02A-P", "qty_available", "8", "qty_reserved", "0"));
        long draftId = draft.get("id").asLong();

        JsonNode accepted = postJson("/workflow/drafts/" + draftId + "/submit", null, authHeaders());
        assertThat(accepted.get("code").asInt()).as("合法身份可达：受理成功").isEqualTo(R_OK);
        long commandId = accepted.get("data").get("commandId").asLong();
        assertThat(countCommands()).as("受理产生 1 条 DRAFT_SUBMIT 命令").isEqualTo(commandsBefore + 1);

        drainNormalLane();

        String commandStatus = queryString("SELECT status FROM sw_bpm_command WHERE id = " + commandId);
        String failureReason = queryString("SELECT failure_reason FROM sw_bpm_command WHERE id = " + commandId);
        assertThat(commandStatus).as("命令终态 FAILED").isEqualTo("FAILED");
        assertThat(failureReason).as("失败原因来自 C1 写入闸门（" + failureReason + "）").contains("受控事务动作");
        String draftStatus = queryString("SELECT status FROM sw_bpm_draft WHERE id = " + draftId);
        String draftError = queryString("SELECT last_error FROM sw_bpm_draft WHERE id = " + draftId);
        assertThat(draftStatus).as("草稿转 FAILED（内容保留可修正）").isEqualTo("FAILED");
        assertThat(draftError).as("草稿保留拒绝原因（" + draftError + "）").contains("受控事务动作");
        assertThat(countRows(protectedTable)).as("拒绝后动态宽表零新增").isEqualTo(rowsBefore);
        assertThat(countFlowStartCommands()).as("本入口未受理 FLOW_START（无子命令增量）").isEqualTo(flowStartBefore);
        assertThat(countProcessInstances()).as("无流程实例增量").isEqualTo(instancesBefore);
        System.out.println("[P62-EV] lt02a.draft protected entry=accepted command=" + commandId
                + " status=FAILED reason=C1 draft=" + draftId + "/FAILED rows=0 flowStart=0 instances=0"
                + " http=" + baseUrl);
    }

    @Test
    @DisplayName("草稿提交入口（对照）：未保护表单同一入口同一身份真实落库并受理流程发起")
    void draftSubmitEntryWritesPlainFormThroughSameChain() throws Exception {
        long rowsBefore = countRows(plainTable);
        long flowStartBefore = countFlowStartCommands();
        JsonNode draft = createDraft(PLAIN_KEY, "LT02a 未保护草稿",
                Map.of("material", "LT02A-PLAIN", "qty_available", "8", "qty_reserved", "0"));
        long draftId = draft.get("id").asLong();
        JsonNode accepted = postJson("/workflow/drafts/" + draftId + "/submit", null, authHeaders());
        System.out.println("[P62-EV] lt02a.draft-accept commandKey="
                + accepted.get("data").get("commandKey").asText() + " channel="
                + accepted.get("data").get("channel").asText());
        assertThat(accepted.get("code").asInt()).isEqualTo(R_OK);

        drainNormalLane();

        assertThat(countRows(plainTable)).as("未保护表单经同一入口真实落库（+1 行）").isEqualTo(rowsBefore + 1);
        String rowId = queryString("SELECT id FROM " + plainTable + " WHERE \"material\" = 'LT02A-PLAIN'");
        assertThat(queryString("SELECT status FROM sw_bpm_draft WHERE id = " + draftId)).isEqualTo("SUBMITTED");
        assertThat(queryString("SELECT result_record_id FROM sw_bpm_draft WHERE id = " + draftId)).isEqualTo(rowId);
        assertThat(countFlowStartCommands()).as("表单落库同事务受理 FLOW_START（+1 子命令）")
                .isEqualTo(flowStartBefore + 1);
        System.out.println("[P62-EV] lt02a.draft-plain entry=written row=" + rowId + " draft=SUBMITTED"
                + " rowsDelta=" + (countRows(plainTable) - rowsBefore)
                + " flowStartDelta=" + (countFlowStartCommands() - flowStartBefore) + " (同一入口同一身份)");
    }

    // ==================== 入口 6：OpenAPI 发起 ====================

    @Test
    @DisplayName("OpenAPI 入口：签名请求可达并受 C1 约束，拒绝零写入零幂等登记，错误按 R 契约可读")
    void openApiEntryEnforcesC1WithNoSideEffects() throws Exception {
        long rowsBefore = countRows(protectedTable);
        long idemBefore = countRows("sw_openapi_idempotency");
        long flowStartBefore = countFlowStartCommands();
        long instancesBefore = countProcessInstances();
        String body = "{\"formKey\":\"" + PROTECTED_KEY + "\",\"businessKey\":\"LT02A-OA-P\","
                + "\"idempotencyKey\":\"lt02a-oa-protected\",\"formData\":{\"material\":\"LT02A-OA-P\","
                + "\"qty_available\":\"8\",\"qty_reserved\":\"0\"}}";
        HttpResponse<String> response = postSigned(body);
        assertThat(response.statusCode()).as("HTTP 契约：业务失败以 R 信封表达").isEqualTo(200);
        JsonNode envelope = objectMapper.readTree(response.body());
        assertThat(envelope.get("code").asInt()).as("C1 保护错误码").isEqualTo(C1_WRITE_PROTECTED);
        assertThat(envelope.get("errorKey").asText()).isEqualTo("form.c1_write_protected");
        assertThat(envelope.get("eventRef").asText()).as("拒绝带可报出的诊断引用").isNotBlank();
        assertThat(envelope.get("msg").asText()).as("拒绝原因可读").contains("关键数据保护");
        assertThat(countRows(protectedTable)).as("拒绝后动态宽表零新增").isEqualTo(rowsBefore);
        assertThat(countRows("sw_openapi_idempotency")).as("拒绝不登记幂等结果").isEqualTo(idemBefore);
        assertThat(countFlowStartCommands()).as("拒绝不产生 FLOW_START 子命令").isEqualTo(flowStartBefore);
        assertThat(countProcessInstances()).as("拒绝不产生流程实例").isEqualTo(instancesBefore);
        System.out.println("[P62-EV] lt02a.openapi protected code=" + envelope.get("code").asInt()
                + " errorKey=" + envelope.get("errorKey").asText() + " rows=0 idem=0 flowStart=0 instances=0");
    }

    @Test
    @DisplayName("OpenAPI 入口（对照）：未保护表单签名请求真实落库，同幂等键重放返回同一 recordId 不重复写入")
    void openApiEntryWritesPlainFormAndReplaysByBusinessKey() throws Exception {
        long rowsBefore = countRows(plainTable);
        long idemBefore = countRows("sw_openapi_idempotency");
        String body = "{\"formKey\":\"" + PLAIN_KEY + "\",\"businessKey\":\"LT02A-OA-PLAIN\","
                + "\"idempotencyKey\":\"lt02a-oa-plain\",\"formData\":{\"material\":\"LT02A-OA-PLAIN\","
                + "\"qty_available\":\"9\",\"qty_reserved\":\"0\"}}";
        HttpResponse<String> first = postSigned(body);
        JsonNode firstEnvelope = objectMapper.readTree(first.body());
        assertThat(firstEnvelope.get("code").asInt()).as("签名鉴权通过并真实提交 body=" + firstEnvelope).isEqualTo(R_OK);
        String recordId = firstEnvelope.get("data").get("recordId").asText();
        assertThat(recordId).isNotBlank();
        assertThat(firstEnvelope.get("data").get("idempotentReplay").asBoolean()).isFalse();
        assertThat(countRows(plainTable)).as("真实写入 +1 行").isEqualTo(rowsBefore + 1);

        HttpResponse<String> replay = postSigned(body);
        JsonNode replayEnvelope = objectMapper.readTree(replay.body());
        assertThat(replayEnvelope.get("code").asInt()).isEqualTo(R_OK);
        assertThat(replayEnvelope.get("data").get("recordId").asText()).as("重放返回首次业务对象").isEqualTo(recordId);
        assertThat(replayEnvelope.get("data").get("idempotentReplay").asBoolean()).isTrue();
        assertThat(countRows(plainTable)).as("重放不产生第二次写入").isEqualTo(rowsBefore + 1);
        assertThat(countRows("sw_openapi_idempotency")).as("幂等登记 +1（重放不新增）").isEqualTo(idemBefore + 1);
        System.out.println("[P62-EV] lt02a.openapi-plain entry=written record=" + recordId
                + " replay=same-recordId rowsDelta=" + (countRows(plainTable) - rowsBefore)
                + " idemDelta=" + (countRows("sw_openapi_idempotency") - idemBefore));
    }

    // ==================== 种子与工具 ====================

    private void seed() {
        protectedFormId = asTenant(TENANT, USER_ID, () -> {
            FormDefDTO draft = formDefService().createDraft(PROTECTED_KEY, "P62上层入口受保护表", null, null);
            formDefService().saveConfig(draft.getId(), stockDefinition());
            formDefService().publish(draft.getId());
            return draft.getId();
        });
        protectedTable = asTenant(TENANT, USER_ID, () -> formDefService().getFormDefByKey(PROTECTED_KEY).getPhysicalTableName());
        asTenant(TENANT, USER_ID, () -> {
            FormDefDTO draft = formDefService().createDraft(PLAIN_KEY, "P62上层入口未保护表", null, null);
            formDefService().saveConfig(draft.getId(), stockDefinition());
            formDefService().publish(draft.getId());
            return draft.getId();
        });
        plainTable = asTenant(TENANT, USER_ID, () -> formDefService().getFormDefByKey(PLAIN_KEY).getPhysicalTableName());

        C1PolicyModel policy = new C1PolicyModel();
        policy.setEnabled(true);
        policy.setProtectedFields(List.of("qty_available", "qty_reserved"));
        policy.setBalanceField("qty_available");
        policy.setReservedField("qty_reserved");
        policy.setNonNegativeAvailable(true);
        assertThat(asTenant(TENANT, USER_ID, () -> c1PolicyService().save(protectedFormId, policy).enabled()))
                .as("受保护表单启用 C1").isTrue();

        jdbc.update("INSERT INTO sw_bpm_form_binding (id, create_time, update_time, deleted, tenant_id, version,"
                + " form_key, process_def_key, active) VALUES (?, now(), now(), 0, 0, 0, ?, ?, true)",
                88011L, PROTECTED_KEY, PROCESS_DEF_KEY);
        jdbc.update("INSERT INTO sw_bpm_form_binding (id, create_time, update_time, deleted, tenant_id, version,"
                + " form_key, process_def_key, active) VALUES (?, now(), now(), 0, 0, 0, ?, ?, true)",
                88012L, PLAIN_KEY, PROCESS_DEF_KEY);

        jdbc.update("INSERT INTO sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version,"
                + " app_id, app_name, secret_hash, scopes, status, act_as_user_id)"
                + " VALUES (?, now(), now(), 0, 0, 0, ?, ?, ?, ?, 'ENABLED', ?)",
                88001L, APP_ID, "P62上层入口验证应用", APP_SECRET,
                "PROCESS_START,PROCESS_QUERY,TASK_HANDLE", USER_ID);
    }

    private FormDefService formDefService() {
        return app.getBean(FormDefService.class);
    }

    private C1PolicyService c1PolicyService() {
        return app.getBean(C1PolicyService.class);
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json");
    }

    private JsonNode createDraft(String formKey, String title, Map<String, Object> payload) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("formKey", formKey);
        body.put("title", title);
        body.put("payload", payload);
        JsonNode envelope = postJson("/workflow/drafts", objectMapper.writeValueAsString(body), authHeaders());
        assertThat(envelope.get("code").asInt()).as("草稿创建可达 body=" + envelope).isEqualTo(R_OK);
        return envelope.get("data");
    }

    private JsonNode postJson(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
        headers.forEach(builder::header);
        builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    /** 测试侧独立实现签名口径：HMAC-SHA256(secret, appId + timestamp + nonce + sha256(body))。 */
    private HttpResponse<String> postSigned(String body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString();
        String signature = hmacSha256Hex(APP_SECRET, APP_ID + timestamp + nonce + sha256Hex(body));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/openapi/v1/processes"))
                .header("Content-Type", "application/json")
                .header("X-App-Id", APP_ID)
                .header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** 直驱调度器同源普通车道入口（生产调度器 #pollNormal 调用的同一方法）。 */
    private void drainNormalLane() throws Exception {
        Method poll = CommandDispatcher.class.getDeclaredMethod("pollNormal");
        poll.setAccessible(true);
        poll.invoke(dispatcher);
    }

    private long countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long countCommands() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command", Long.class);
    }

    private long countFlowStartCommands() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sw_bpm_command WHERE command_type = 'FLOW_START'",
                Long.class);
    }

    private long countProcessInstances() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'"
                        + " AND table_name = 'act_hi_procinst'", Integer.class);
        if (count == null || count == 0) {
            return 0;
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM act_hi_procinst", Long.class);
    }

    private String queryString(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hmacSha256Hex(String secret, String material) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
    }

    private static String stockDefinition() {
        return "{\"schemaVersion\":1,\"title\":\"P62上层入口库存表\",\"fields\":["
                + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\",\"required\":false},"
                + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\",\"required\":false},"
                + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\",\"required\":false}]}";
    }

    private static <T> T asTenant(Long tenantId, Long userId, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
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
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
