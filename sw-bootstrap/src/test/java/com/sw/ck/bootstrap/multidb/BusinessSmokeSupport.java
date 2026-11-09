package com.sw.ck.bootstrap.multidb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentToolExternalConfigQuery;
import com.sw.ck.agent.dto.AgentToolInternalConfigQuery;
import com.sw.ck.agent.service.AgentToolConfigService;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.service.FormDefService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.crypto.Cipher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S3：MySQL / Oracle 共用主流程业务冒烟（管理员登录、菜单、表单发布、审批到终态、定时任务触发）。
 */
final class BusinessSmokeSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private BusinessSmokeSupport() {
    }

    static String login(String base, String username, String password) throws Exception {
        JsonNode challenge = MAPPER.readTree(get(base, "/auth/challenge", null).body()).path("data");
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(challenge.path("publicKey").asText())));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey,
                new javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
                        MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT));
        String encrypted = Base64.getEncoder().encodeToString(
                cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        String body = MAPPER.writeValueAsString(Map.of(
                "username", username, "password", encrypted,
                "captcha", "1234", "captchaId", challenge.path("captchaId").asText(),
                "timestamp", String.valueOf(System.currentTimeMillis())));
        HttpResponse<String> response = post(base, "/auth/login", body, null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("登录失败: " + response.statusCode() + " " + response.body());
        }
        String token = MAPPER.readTree(response.body()).path("data").path("accessToken").asText();
        if (token.isBlank()) {
            throw new IllegalStateException("登录响应缺少 accessToken: " + response.body());
        }
        return token;
    }

    static void assertMenus(String base, String token) throws Exception {
        HttpResponse<String> response = get(base, "/system/auth/menus", token);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("菜单加载失败: " + response.statusCode() + " " + response.body());
        }
        JsonNode data = MAPPER.readTree(response.body()).path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("菜单为空: " + response.body());
        }
    }

    /**
     * X1：各库业务冒烟追加一条智能体工具启用过滤查询。
     * 直接经服务层执行 Boolean 绑定，覆盖管理端内部/外部工具列表的 enabled 过滤。
     */
    static void assertAgentToolEnabledFilter(ConfigurableApplicationContext app) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setUsername("admin");
        loginUser.setTenantId(0L);
        loginUser.setSuperAdmin(true);
        LoginUserHolder.set(loginUser);
        try {
            AgentToolConfigService service = app.getBean(AgentToolConfigService.class);
            AgentToolInternalConfigQuery internalQuery = new AgentToolInternalConfigQuery();
            internalQuery.setPageNum(1);
            internalQuery.setPageSize(10);
            internalQuery.setEnabled(true);
            PageResult<?> internal = service.pageInternalTools(internalQuery);
            if (internal == null) {
                throw new IllegalStateException("内部工具启用过滤返回 null");
            }
            AgentToolExternalConfigQuery externalQuery = new AgentToolExternalConfigQuery();
            externalQuery.setPageNum(1);
            externalQuery.setPageSize(10);
            externalQuery.setEnabled(true);
            PageResult<?> external = service.pageExternalTools(externalQuery);
            if (external == null) {
                throw new IllegalStateException("外部工具启用过滤返回 null");
            }
        } finally {
            LoginUserHolder.clear();
        }
    }

    static String publishForm(ConfigurableApplicationContext app, LoginUser actor, String formKey) {
        LoginUserHolder.set(actor);
        try {
            FormDefService service = app.getBean(FormDefService.class);
            var draft = service.createDraft(formKey, "MySQL/Oracle冒烟表单", null, "multi-db smoke");
            String id = draft.getId();
            service.saveConfig(id, "{\"schemaVersion\":1,\"title\":\"冒烟表单\",\"fields\":["
                    + "{\"name\":\"reason\",\"type\":\"TEXT\",\"label\":\"事由\",\"required\":true,\"length\":100}]}");
            service.publish(id);
            return id;
        } finally {
            LoginUserHolder.clear();
        }
    }

    /** 动态宽表真实读写一轮：验证 ANSI 标识符在 MySQL/MariaDB 上按方言改写后可执行。 */
    static void assertDynamicRecordRoundTrip(ConfigurableApplicationContext app, String formId, Long tenantId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setTenantId(tenantId);
        LoginUserHolder.set(loginUser);
        try {
            String table = app.getBean(com.sw.ck.form.mapper.FormDefMapper.class)
                    .selectById(formId).getPhysicalTableName();
            org.springframework.jdbc.core.JdbcTemplate jdbc =
                    app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
            String recordId = UUID.randomUUID().toString();
            com.sw.ck.form.dynamic.DynamicTableSql.update(jdbc, table,
                    "INSERT INTO \"" + table + "\" (\"id\", \"tenant_id\", \"deleted\", \"create_time\", "
                            + "\"create_by\", \"update_time\", \"update_by\", \"version\", \"reason\") "
                            + "VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?, ?, ?)",
                    recordId, tenantId, 0, 1L, 1L, 0L, "冒烟事由");
            List<Map<String, Object>> rows = com.sw.ck.form.dynamic.DynamicTableSql.query(jdbc, table,
                    "SELECT \"id\", \"reason\" FROM \"" + table + "\" WHERE \"id\" = ? AND \"deleted\" = 0 "
                            + "AND \"tenant_id\" = ?", recordId, tenantId);
            if (rows.size() != 1 || !"冒烟事由".equals(rows.get(0).get("reason"))) {
                throw new IllegalStateException("动态宽表读写不一致: " + rows);
            }
            int updated = com.sw.ck.form.dynamic.DynamicTableSql.update(jdbc, table,
                    "UPDATE \"" + table + "\" SET \"deleted\" = 1, \"update_time\" = CURRENT_TIMESTAMP "
                            + "WHERE \"id\" = ? AND \"deleted\" = 0 AND \"tenant_id\" = ?",
                    recordId, tenantId);
            if (updated != 1) {
                throw new IllegalStateException("动态宽表软删失败，影响行数=" + updated);
            }
        } finally {
            LoginUserHolder.clear();
        }
    }

    static void runApproval(ConfigurableApplicationContext app) {
        String processKey = "smoke_approval_" + Long.toString(System.nanoTime(), 36);
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://sw.ck/smoke">
                  <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="冒烟审批" flowable:assignee="1"/>
                    <sequenceFlow sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey);
        var repositoryService = app.getBean(org.flowable.engine.RepositoryService.class);
        repositoryService.createDeployment()
                .tenantId("0")
                .addString(processKey + ".bpmn20.xml", bpmn)
                .deploy();
        var runtimeService = app.getBean(org.flowable.engine.RuntimeService.class);
        var taskService = app.getBean(org.flowable.engine.TaskService.class);
        var instance = runtimeService.startProcessInstanceByKeyAndTenantId(processKey, "0");
        var task = taskService.createTaskQuery().processInstanceId(instance.getProcessInstanceId()).singleResult();
        if (task == null) {
            throw new IllegalStateException("审批任务未生成");
        }
        taskService.complete(task.getId());
        long remaining = runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getProcessInstanceId()).count();
        if (remaining != 0) {
            throw new IllegalStateException("审批流程未到终态，剩余实例=" + remaining);
        }
    }

    static void triggerQuartz(ConfigurableApplicationContext app) throws Exception {
        Scheduler scheduler = app.getBean(Scheduler.class);
        SmokeJob.latch = new CountDownLatch(1);
        SmokeJob.executions.set(0);
        String identity = "smoke-" + UUID.randomUUID();
        scheduler.scheduleJob(
                JobBuilder.newJob(SmokeJob.class).withIdentity(identity, "smoke").build(),
                TriggerBuilder.newTrigger().withIdentity(identity + "-trigger", "smoke").startNow().build());
        if (!SmokeJob.latch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Quartz 定时任务未在 5 秒内触发");
        }
        if (SmokeJob.executions.get() != 1) {
            throw new IllegalStateException("Quartz 定时任务执行次数异常: " + SmokeJob.executions.get());
        }
    }

    public static class SmokeJob implements Job {
        static volatile CountDownLatch latch = new CountDownLatch(1);
        static final AtomicInteger executions = new AtomicInteger();

        @Override
        public void execute(JobExecutionContext context) {
            executions.incrementAndGet();
            latch.countDown();
        }
    }

    private static HttpResponse<String> get(String base, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String base, String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
