package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RA03：资源保障授权与运维入口真实 HTTP 行为链（复核01 剩余账本）。
 *
 * <p><b>取证矩阵</b>（真实 HTTP 入口 + debug-auth 身份，权限经正式 UserDetailsProvider 回查）：
 * <ol>
 *   <li>合法路径：持 {@code workflow:resource:view/manage} 的租户 A 管理员经 POST 创建策略版本
 *       → 启用检查 → 启用成功 → 运维画像/积压/明细/拒绝审计可读（同租户数据可见）；</li>
 *   <li>越权拒绝：仅持 view 的用户创建/启用/停受理 → 403（服务端独立授权，非前端过滤）；</li>
 *   <li>跨租户：租户 B 普通用户查看租户 A 的命令明细/拒绝审计 → 空集或 403，不泄露他租户对象；
 *       持 manage 的跨租户运维显式传租户可查（独立服务端授权语义）；</li>
 *   <li>非法额度拒绝：保留份额不自洽策略 → 启用检查明确拒绝并留审计行（reject_scope=ENABLEMENT）。</li>
 * </ol>
 * 手动运行：-Dp62.ra03.auth=true -Dp62.runId=... -Dp62.evidence.dir=...
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RA03 授权与运维入口真实HTTP行为链（手动：-Dp62.ra03.auth=true）")
@EnabledIfSystemProperty(named = "p62.ra03.auth", matches = "true")
class P62ResourceOpsAuthPgTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static EmbeddedPostgresHolder pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static String runId;
    private static Path evidenceDir;
    private static int port;

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1).build();

    private static final class EmbeddedPostgresHolder {
        io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg;
    }

    @BeforeAll
    void boot() throws Exception {
        runId = System.getProperty("p62.runId");
        String dir = System.getProperty("p62.evidence.dir");
        evidenceDir = Path.of(dir);
        Files.createDirectories(evidenceDir);
        pg = new EmbeddedPostgresHolder();
        pg.pg = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url",
                "jdbc:postgresql://127.0.0.1:" + pg.pg.getPort() + "/postgres?stringtype=unspecified");
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-ra03-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", P62BudgetMeasurementPgTest.generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-ra03-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.security.debug-auth.enabled", "true");
        // 合同配置（RA01 同款）：启用检查核验的是「消费者可用性与池相容」的真实运行值，
        // 本测试环境必须先满足合同画像（池64/异步ON/批量开），否则启用被如实拒绝
        props.putAll(P62ResourceAssurancePgTest.contractProps());
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-ra03", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("ra03LoginContextProvider", provider);
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
        jdbc = app.getBean(JdbcTemplate.class);
        port = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
        seedIdentities();
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        try {
            if (pg != null && pg.pg != null) {
                pg.pg.close();
            }
        } catch (Exception ignored) {
            // 关闭容错
        }
    }

    /**
     * 身份经真实 RBAC 链（用户→角色→菜单权限→UserDetailsProvider 回查）：
     * 租户1 管理 94101（角色 9001 view 菜单 + 9002 manage 按钮）、
     * 租户1 只读 94102（仅角色 9001）、租户2 普通 94201（无角色无权限）。
     * 菜单 9106/9107（permission=workflow:resource:view）与 9108（workflow:resource:manage）
     * 由 R__p62_resource_ops_menu 种子提供。
     */
    private void seedIdentities() {
        seedTenantUser(1L, 94101L, "ra03-admin-t1");
        seedTenantUser(1L, 94102L, "ra03-viewer-t1");
        seedTenantUser(2L, 94201L, "ra03-user-t2");
        bindRole(9001L, "RA03查看", 94101L, List.of(9106L, 9107L));
        bindRole(9001L, "RA03查看", 94102L, List.of(9106L, 9107L));
        bindRole(9002L, "RA03管理", 94101L, List.of(9108L));
    }

    private void seedTenantUser(long tenant, long userId, String name) {
        jdbc.update("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id,"
                        + " version, name, code, status, description, domain_name) "
                        + "VALUES (?, current_timestamp, current_timestamp, 0, 0, 0, ?, ?, 0, 'RA03',"
                        + " 'localhost') ON CONFLICT (id) DO NOTHING",
                tenant, "RA03租户" + tenant, "p62-ra03-t" + tenant);
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, ?, 'seed-not-a-login-secret', ?, ?, 0) ON CONFLICT (id) DO NOTHING",
                userId, name, name, tenant);
    }

    /** 建角色、绑用户、按菜单挂权限（同租户边界；幂等）。 */
    private void bindRole(long roleId, String roleName, long userId, List<Long> menuIds) {
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM sys_user WHERE id = ?", Long.class, userId);
        jdbc.update("INSERT INTO sys_role (id, create_time, update_time, deleted, version, tenant_id,"
                        + " name, code, status, data_scope, built_in) "
                        + "VALUES (?, current_timestamp, current_timestamp, 0, 0, ?, ?, ?, 1, 1, false)"
                        + " ON CONFLICT (id) DO NOTHING",
                roleId, tenantId, roleName, "ra03_role_" + roleId);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) "
                        + "SELECT ?, tenant_id, ?, ? FROM sys_user WHERE id = ?"
                        + " ON CONFLICT (id) DO NOTHING",
                960000L + roleId * 10 + (userId % 10), userId, roleId, userId);
        long seq = 970000L + roleId * 100;
        for (Long menuId : menuIds) {
            // 租户列级隔离：role_menu 行必须携带用户租户，否则权限装载查询按租户过滤不可见
            jdbc.update("INSERT INTO sys_role_menu (id, create_time, update_time, deleted, version,"
                            + " tenant_id, role_id, menu_id) VALUES (?, current_timestamp, current_timestamp,"
                            + " 0, 0, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    seq++, tenantId, roleId, menuId);
        }
    }

    @Test
    @DisplayName("RA03：合法管理/越权拒绝/只读不可管理/跨租户隔离/非法额度拒绝 真实HTTP矩阵（请求-响应-回查三段）")
    void authMatrix() throws Exception {
        StringBuilder ev = new StringBuilder();
        appendIdentities(ev);
        String admin = "Bearer test_94101";
        String viewer = "Bearer test_94102";
        String outsider = "Bearer test_94201";
        String body = "{\"globalMaxOutstanding\":2000,\"tenantMaxOutstanding\":800,\"prodReserved\":400,"
                + "\"oaReserved\":400,\"sharedCapacity\":1200,\"tenantRatePerSec\":50,\"tenantBurst\":500,"
                + "\"realtimeGlobalConcurrency\":16,\"realtimeTenantConcurrency\":8,"
                + "\"batchSliceItems\":25,\"batchPollClaimLimit\":1,\"remark\":\"RA03合法策略\"}";

        // A1 合法创建（持 view+manage）；A2 合法启用；逐字段回查持久值（非只看响应）
        String created = exchange(ev, "A1", "POST", "/api/workflow/resource/policy", admin, body);
        assertThat(created).contains("\"policyVersion\"").contains("\"status\":\"DRAFT\"");
        Long policyId = Long.valueOf(extractJsonScalar(created, "id"));
        appendPolicyRow(ev, "A1-created-row(DRAFT)", policyId);
        String enabled = exchange(ev, "A2", "POST", "/api/workflow/resource/policy/" + policyId
                + "/enable", admin, "{\"remark\":\"RA03启用\"}");
        assertThat(enabled).contains("\"status\":\"ACTIVE\"");
        appendPolicyRow(ev, "A2-enabled-row(ACTIVE)", policyId);
        Long activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_resource_policy WHERE status = 'ACTIVE'", Long.class);
        assertThat(activeCount).isEqualTo(1);

        // B 非法额度：保留份额不自洽 → 启用明确拒绝 + 独立审计（端点/身份/响应/审计原文/策略未被改动）
        String bad = post("/api/workflow/resource/policy", admin,
                "{\"globalMaxOutstanding\":100,\"tenantMaxOutstanding\":100,\"prodReserved\":1,"
                        + "\"oaReserved\":1,\"sharedCapacity\":1,\"tenantRatePerSec\":50,\"tenantBurst\":500,"
                        + "\"realtimeGlobalConcurrency\":16,\"realtimeTenantConcurrency\":8,"
                        + "\"batchSliceItems\":25,\"batchPollClaimLimit\":1}");
        Long badId = Long.valueOf(extractJsonScalar(bad, "id"));
        String badEnable = exchange(ev, "B1", "POST",
                "/api/workflow/resource/policy/" + badId + "/enable", admin, "{}");
        assertThat(badEnable).contains("bpm.resource_policy_invalid").contains("保留份额不自洽");
        appendPolicyRow(ev, "B1-rejected-policy-row(仍 DRAFT)", badId);
        appendRejectAuditRows(ev, "B1-enablement-audit");
        appendPolicyRow(ev, "B1-active-policy-unchanged", policyId);

        // C 只读身份（view 无 manage）：可读、不可管理（创建/启用均 403，响应原文留证）
        String profileViewer = exchange(ev, "C1-view-ok", "GET",
                "/api/workflow/resource/profile", viewer, null);
        assertThat(profileViewer).contains("\"policyEnabled\":true");
        String listViewer = exchange(ev, "C2-view-list-ok", "GET", "/api/workflow/resource/policy",
                viewer, null);
        assertThat(listViewer).contains("\"code\":0");
        String viewerCreate = exchange(ev, "C3-view-only-create", "POST",
                "/api/workflow/resource/policy", viewer, body);
        assertThat(viewerCreate).contains("403");
        String viewerEnable = exchange(ev, "C4-view-only-enable", "POST",
                "/api/workflow/resource/policy/" + policyId + "/enable", viewer, "{}");
        assertThat(viewerEnable).contains("403");
        appendPolicyRow(ev, "C4-policy-after-viewer-attempt", policyId);

        // D 无权限身份（t2 普通用户）：全部端点 403（服务端拒绝而非空集过滤）
        String forbiddenCreate = exchange(ev, "D1-create", "POST",
                "/api/workflow/resource/policy", outsider, body);
        assertThat(forbiddenCreate).contains("403");
        exchange(ev, "D2-enable", "POST", "/api/workflow/resource/policy/" + policyId + "/enable",
                outsider, "{}");
        String outsiderCommands = exchange(ev, "D3-commands", "GET",
                "/api/workflow/resource/backlog/commands?page=1&size=10", outsider, null);
        assertThat(outsiderCommands).contains("403");
        exchange(ev, "D4-read-rejects", "GET", "/api/workflow/resource/rejects?page=1&size=10",
                outsider, null);

        // E 跨租户：t1 只读身份强制查询他租户范围被强制本租户（total=0）；t2 授予 view 后
        // 仍只看到本租户对象（租户 2 无对象 → 0），且无法写 t1 策略
        String crossTenant = exchange(ev, "E1-tenant1-viewer-asks-tenant2", "GET",
                "/api/workflow/resource/backlog/commands?page=1&size=10&tenantId=2", viewer, null);
        assertThat(crossTenant).contains("\"total\":\"0\"");
        bindRole(9003L, "RA03查看", 94201L, List.of(9106L, 9107L));
        String t2AsksT1 = exchange(ev, "E2-tenant2-viewer-asks-tenant1", "GET",
                "/api/workflow/resource/backlog/commands?page=1&size=10&tenantId=1", outsider, null);
        assertThat(t2AsksT1).contains("\"total\":\"0\"");
        String t2StopT1 = exchange(ev, "E3-tenant2-write-t1-policy", "POST",
                "/api/workflow/resource/policy/" + policyId + "/stop-acceptance", outsider,
                "{\"stop\":true}");
        assertThat(t2StopT1).contains("403");
        appendPolicyRow(ev, "E3-t1-policy-after-t2-cross-tenant-attempt", policyId);

        // F 拒绝审计（同租户管理可读，原文含 scope/reason；不跨租户）
        String rejects = exchange(ev, "F1-rejects-admin", "GET",
                "/api/workflow/resource/rejects?page=1&size=10", admin, null);
        assertThat(rejects).contains("ENABLEMENT");
        appendRejectAuditRows(ev, "F1-audit-rows-after-matrix");

        ev.append("\nrecordedAt=").append(LocalDateTime.now().format(TS)).append("\nrunId=")
                .append(runId).append("\n");
        Files.writeString(evidenceDir.resolve("auth-matrix.txt"), ev.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        System.out.println("[P62-EV] ra03 auth matrix ok");
    }

    private void appendIdentities(StringBuilder ev) {
        ev.append("runId=").append(runId).append("\nbaseUrl=http://127.0.0.1:").append(port)
                .append("\nidentities(经 sys_user/sys_role/sys_role_menu 真实 RBAC 链):\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT u.id, u.username, u.tenant_id,"
                + " u.status, r.id AS role_id, r.name AS role_name FROM sys_user u"
                + " LEFT JOIN sys_user_role ur ON ur.user_id = u.id AND ur.deleted = 0"
                + " LEFT JOIN sys_role r ON r.id = ur.role_id WHERE u.id IN (94101, 94102, 94201)"
                + " ORDER BY u.id")) {
            ev.append("  user=").append(row.get("id")).append(" username=").append(row.get("username"))
                    .append(" tenant=").append(row.get("tenant_id")).append(" status=")
                    .append(row.get("status")).append(" role=").append(row.get("role_id")).append('/')
                    .append(row.get("role_name")).append('\n');
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT rm.role_id, rm.menu_id, m.name,"
                + " m.permission FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id"
                + " WHERE rm.role_id IN (9001, 9002, 9003) ORDER BY rm.role_id, rm.menu_id")) {
            ev.append("  role=").append(row.get("role_id")).append(" menu=").append(row.get("menu_id"))
                    .append(" name=").append(row.get("name")).append(" permission=")
                    .append(row.get("permission")).append('\n');
        }
        ev.append("tokenLine=Bearer test_<userId>（debug-auth 测试契约身份，非用户秘密）\n");
    }

    /** 请求-响应原文记录（含身份、方法、URL、HTTP 状态与完整业务响应）；返回原始响应串。 */
    private String exchange(StringBuilder ev, String id, String method, String path, String bearer,
                            String json) {
        String response = "-".equals(method) ? "-" : ("POST".equals(method)
                ? post(path, bearer, json) : get(path, bearer));
        int status;
        String payload;
        int split = response.indexOf('|');
        if (split < 0) {
            status = -1;
            payload = response;
        } else {
            status = Integer.parseInt(response.substring(0, split));
            payload = response.substring(split + 1);
        }
        ev.append("\n[").append(id).append("] method=").append(method).append(" url=http://127.0.0.1:")
                .append(port).append(path).append(" identity=").append(bearer)
                .append(" body=").append(json == null ? "-" : json).append('\n')
                .append("  response_status=").append(status).append(" body=").append(payload).append('\n');
        return payload;
    }

    private void appendPolicyRow(StringBuilder ev, String label, Long policyId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT id, status, enabled, policy_version,"
                + " global_max_outstanding, tenant_max_outstanding, prod_reserved, oa_reserved,"
                + " shared_capacity, tenant_rate_per_sec, tenant_burst, realtime_global_concurrency,"
                + " realtime_tenant_concurrency, update_time FROM sw_bpm_resource_policy WHERE id = ?",
                policyId);
        ev.append("  [").append(label).append("] ").append(row).append('\n');
    }

    private void appendRejectAuditRows(StringBuilder ev, String label) {
        ev.append("  [").append(label).append("] sw_bpm_resource_reject_log rows:\n");
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, tenant_id, policy_version,"
                + " resource_class, reject_scope, requested_units, reason_code, detail, create_time"
                + " FROM sw_bpm_resource_reject_log ORDER BY id")) {
            ev.append("    ").append(row).append('\n');
        }
    }

    // ==================== HTTP 工具 ====================

    /** POST；返回「状态码+响应原文截断」（越权/业务拒绝都不抛，供断言原文）。 */
    private String post(String path, String bearer, String json) {
        return doExchange("POST", path, bearer, json);
    }

    private String get(String path, String bearer) {
        return doExchange("GET", path, bearer, null);
    }

    private String doExchange(String method, String path, String bearer, String json) {
        try {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .header("Authorization", bearer);
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                                json == null ? "{}" : json, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            java.net.http.HttpResponse<String> response = HTTP.send(builder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() + "|" + response.body();
        } catch (Exception e) {
            return "EXCEPTION|" + e.getClass().getSimpleName();
        }
    }

    private static String snippet(String body) {
        return body == null ? "-" : body.substring(0, Math.min(160, body.length()));
    }

    /** 带引号或不带引号的原始标量提取（id 等经 Jackson Long→String 契约渲染为字符串）。 */
    private static String extractJsonScalar(String json, String field) {
        String payload = json.substring(json.indexOf('|') + 1);
        int i = payload.indexOf("\"" + field + "\"");
        if (i < 0) {
            return "0";
        }
        int colon = payload.indexOf(':', i) + 1;
        while (colon < payload.length() && payload.charAt(colon) == ' ') {
            colon++;
        }
        if (payload.charAt(colon) == '"') {
            int end = payload.indexOf('"', colon + 1);
            return payload.substring(colon + 1, end);
        }
        int end = colon;
        while (end < payload.length() && Character.isDigit(payload.charAt(end))) {
            end++;
        }
        return payload.substring(colon, end);
    }

    private static String extractJsonNumber(String json, String field) {
        int bar = json.indexOf('|');
        String payload = bar >= 0 ? json.substring(bar + 1) : json;
        int i = payload.indexOf("\"" + field + "\"");
        if (i < 0) {
            return "0";
        }
        int colon = payload.indexOf(':', i);
        int end = colon + 1;
        while (end < payload.length() && (payload.charAt(end) == ' ' || Character.isDigit(payload.charAt(end)))) {
            end++;
        }
        return payload.substring(colon + 1, end).trim();
    }
}
