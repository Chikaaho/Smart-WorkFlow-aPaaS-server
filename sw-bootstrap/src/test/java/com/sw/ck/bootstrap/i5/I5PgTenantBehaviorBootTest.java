package com.sw.ck.bootstrap.i5;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.sso.SsoAuthService;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I5 复验（G1a2 / G6a1 / G2a1）：真实 PostgreSQL 层行为证据。
 * <p>
 * zonky 内嵌真实 PostgreSQL 二进制 + 全链迁移 + 完整应用上下文（RANDOM_PORT 真实
 * HTTP）。三个原子项共用一次启动：
 * <ol>
 *   <li>G1a2：两租户同 formKey 发布物理表全局无碰撞、同租户重复被拒（PG JDBC 回读）；</li>
 *   <li>G6a1：两租户同 (provider, 摘要) 真实 PG barrier 并发绑定恰好一方成功，
 *       失败侧为业务冲突且 binding/role 零副作用；</li>
 *   <li>G2a1：登记应用合法签名 HTTP 达业务 scope/handler 边界，PG nonce 行落库；
 *       缺头/坏签名/过期租户拒绝且 nonce/业务行零增量。</li>
 * </ol>
 * 密钥为进程内测试值；PG 层为真实 PostgreSQL（非 H2 替代）。
 */
@DisplayName("I5 G1a2/G6a1/G2a1 真实 PostgreSQL 行为（zonky PG + 全链迁移）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I5PgTenantBehaviorBootTest {

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static String base;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "i5-pg-test-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "i5-pg-test-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("i5-pg-test", props));
                    context.getEnvironment().getSystemProperties().put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("i5PgTestLoginContextProvider", provider);
                })
                .run();
        String port = app.getEnvironment().getProperty("local.server.port");
        base = "http://127.0.0.1:" + port + "/api";
        jdbc = app.getBean(JdbcTemplate.class);
        System.out.println("[PG-BOOT] 真实 PostgreSQL 完整启动成功，port=" + port + "，pg=" + pg.getPort());
        System.out.println("[PG-BOOT] jdbc url=" + pgUrl);
        seedTenantsAndUsers();
    }

    private static void seedTenantsAndUsers() {
        // 租户 100（有效）与 300（过期）+ 部门/用户（与 dev 夹具同构；口令散列同 V900）。
        // PG 方言：INSERT ... ON CONFLICT DO NOTHING（zonky 实例为本轮独占，冲突仅为幂等兜底）
        jdbc.execute("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description) VALUES " +
                "(100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'I5PG测试租户', 'i5-pg-t100', 0, 'PG fixture') ON CONFLICT (id) DO NOTHING");
        jdbc.execute("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description, expire_time) VALUES " +
                "(300, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'I5PG过期租户', 'i5-pg-t300', 0, 'PG fixture', TIMESTAMP '2020-01-01 00:00:00') ON CONFLICT (id) DO NOTHING");
        jdbc.execute("INSERT INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, parent_id, name, code, sort, status, description) VALUES " +
                "(1001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 100, 0, 0, 'I5PG根部门', 'i5-pg-root', 0, 0, 'PG fixture') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, is_admin) VALUES " +
                "(9001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 100, 0, 'i5pgt100admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'PG租户100管理员', 1001, 0, 1) ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, is_admin) VALUES " +
                "(9301, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 300, 0, 'i5pgt300admin', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'PG过期租户管理员', 1001, 0, 1) ON CONFLICT (id) DO NOTHING");
        // OpenAPI 应用：secret 仅 SHA-256 摘要（哨兵明文不出现在任何仓库文件）
        String secretHash = sha256("sentinel-openapi-secret-g2a");
        jdbc.update("INSERT INTO sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version, app_id, app_name, secret_hash, scopes, status, act_as_user_id) VALUES " +
                "(9001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 100, 0, 'i5-pg-openapi-t100', 'I5PG应用-租户100', ?, 'PROCESS_START,PROCESS_QUERY,TASK_HANDLE', 'ENABLED', 9001) ON CONFLICT (id) DO NOTHING", secretHash);
        jdbc.update("INSERT INTO sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version, app_id, app_name, secret_hash, scopes, status, act_as_user_id) VALUES " +
                "(9002, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 300, 0, 'i5-pg-openapi-t300', 'I5PG应用-过期租户', ?, 'PROCESS_START,PROCESS_QUERY,TASK_HANDLE', 'ENABLED', 9301) ON CONFLICT (id) DO NOTHING", secretHash);
    }

    /** G1a2：两租户同 formKey → 物理表全局无碰撞；同租户重复被数据库约束拒绝。 */
    @Test
    @DisplayName("G1a2：两租户同键发布物理表不同且全局无碰撞；同租户重复拒绝")
    void g1a2FormPhysicalTablesPostgres() {
        LoginUser t0 = user(1L, 0L);
        LoginUser t100 = user(9001L, 100L);
        String formKey = "leave_pg_g1a2_" + Long.toString(System.currentTimeMillis(), 36);
        String defJson = "{\"schemaVersion\":1,\"title\":\"PG同键\",\"fields\":[{\"name\":\"reason\",\"type\":\"TEXT\",\"label\":\"事由\",\"required\":true,\"length\":100}]}";

        String id0 = createAndPublish(formKey, t0, "T0-PG同键", defJson);
        String id100 = createAndPublish(formKey, t100, "T100-PG同键", defJson);
        assertThat(id0).isNotNull().isNotEqualTo(id100);

        // PG JDBC 回读：两租户各自 definition/config/table_name
        var rows = jdbc.queryForList(
                "SELECT f.tenant_id AS tid, f.id AS fid, c.table_name AS tbl FROM sw_form_def f " +
                        "LEFT JOIN sw_form_config c ON c.form_id = f.id AND c.deleted = 0 " +
                        "WHERE f.form_key = ? AND f.deleted = 0 ORDER BY f.tenant_id", formKey);
        System.out.println("[G1a2] formRows=" + rows);
        assertThat(rows).hasSize(2);
        String table0 = null, table100 = null;
        for (Map<String, Object> r : rows) {
            String tid = String.valueOf(r.get("tid"));
            if ("0".equals(tid)) table0 = (String) r.get("tbl");
            if ("100".equals(tid)) table100 = (String) r.get("tbl");
        }
        System.out.println("[G1a2] table0=" + table0 + " table100=" + table100);
        assertThat(table0).isNotBlank();
        assertThat(table100).isNotBlank();
        assertThat(table0).as("两租户同键物理表必须全局无碰撞").isNotEqualTo(table100);

        // 物理表真实存在于 PG 且带租户隔离系统列
        for (String tbl : new String[]{table0, table100}) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, tbl);
            assertThat(cnt).as("物理表 %s 应存在于 PostgreSQL", tbl).isEqualTo(1);
            var cols = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_name = ? AND column_name IN ('tenant_id','deleted','id') ORDER BY column_name", String.class, tbl);
            System.out.println("[G1a2] " + tbl + " systemCols=" + cols);
            assertThat(cols).contains("deleted", "id", "tenant_id");
        }

        // 同租户重复 formKey：唯一约束拒绝（发布链路先在创建入口拒绝）
        LoginUserHolder.set(t100);
        try {
            Exception ex = null;
            try {
                var svc = app.getBean(com.sw.ck.form.service.FormDefService.class);
                svc.createDraft(formKey, "dup", null, "dup");
            } catch (Exception e) {
                ex = e;
            }
            System.out.println("[G1a2] dupSameTenant -> " + (ex == null ? "NO-ERROR(UNEXPECTED)" : ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            assertThat(ex).as("同租户重复 formKey 必须被拒绝").isNotNull();
            Integer dupRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_form_def WHERE form_key = ? AND tenant_id = 100 AND deleted = 0", Integer.class, formKey);
            assertThat(dupRows).as("同租户重复键不得新增行").isEqualTo(1);
        } finally {
            LoginUserHolder.clear();
        }
        System.out.println("[G1a2] PASS");
    }

    private String createAndPublish(String formKey, LoginUser actor, String name, String defJson) {
        LoginUserHolder.set(actor);
        try {
            var svc = app.getBean(com.sw.ck.form.service.FormDefService.class);
            var def = svc.createDraft(formKey, name, null, "I5 G1a2");
            String id = def.getId();
            svc.saveConfig(id, defJson);
            svc.publish(id);
            return id;
        } finally {
            LoginUserHolder.clear();
        }
    }

    /** G6a1：两租户同 (provider, 摘要) 真实 PG barrier 并发绑定，恰好一方成功。 */
    @Test
    @DisplayName("G6a1：PG barrier 并发绑定仅一方成功；失败侧业务冲突且零副作用")
    void g6a1BindingBarrierConcurrencyPostgres() throws Exception {
        String externalId = "i5-g6a1-pg-subject-" + Long.toString(System.currentTimeMillis(), 36);
        String digest = SsoAuthService.digest(externalId);
        Integer bindingBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sys_sso_user_binding WHERE deleted=0", Integer.class);
        Integer roleBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE deleted=0", Integer.class);

        // session/cache 零增量基线（与并发批次同时间窗；真实 Redis 回读）
        org.springframework.data.redis.core.StringRedisTemplate redis =
                app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        java.util.Set<String> sessionKeysBefore = redis.keys("sw:security:login-user:*");
        boolean user1SessionBefore = redis.hasKey("sw:security:login-user:1");
        boolean user9001SessionBefore = redis.hasKey("sw:security:login-user:9001");
        System.out.println("[G6a1] sessionBefore keys=" + sessionKeysBefore.size()
                + " user1=" + user1SessionBefore + " user9001=" + user9001SessionBefore
                + " at=" + java.time.LocalDateTime.now());

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<String> r0 = new AtomicReference<>();
        AtomicReference<String> r100 = new AtomicReference<>();
        Thread a = new Thread(() -> r0.set(bindInContext(user(1L, 0L), "WECOM", externalId, barrier)));
        Thread b = new Thread(() -> r100.set(bindInContext(user(9001L, 100L), "WECOM", externalId, barrier)));
        a.start();
        b.start();
        a.join(30000);
        b.join(30000);
        System.out.println("[G6a1] tenant0 result=" + r0.get());
        System.out.println("[G6a1] tenant100 result=" + r100.get());

        boolean oneSucceeded =
                (r0.get().startsWith("OK") && r100.get().startsWith("DENIED")) ||
                        (r100.get().startsWith("OK") && r0.get().startsWith("DENIED"));
        assertThat(oneSucceeded).as("恰好一方成功，另一方业务拒绝").isTrue();
        assertThat(r0.get() + r100.get()).as("失败侧必须是业务冲突（IllegalStateException），不是 500/原始 DuplicateKey")
                .contains("IllegalStateException");

        Integer bindingAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_sso_user_binding WHERE deleted=0 AND external_digest=?", Integer.class, digest);
        System.out.println("[G6a1] bindingRowsForDigest=" + bindingAfter);
        assertThat(bindingAfter).as("同主体全局仅一行绑定").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_sso_user_binding WHERE deleted=0", Integer.class))
                .isEqualTo(bindingBefore + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE deleted=0", Integer.class))
                .as("role 表零增量").isEqualTo(roleBefore);
        // 审计含真实 DENIED 冲突事件
        Integer denied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_sso_audit_record WHERE result='DENIED' AND event_type='CONFLICT_REJECTED' AND external_digest=?",
                Integer.class, digest);
        System.out.println("[G6a1] deniedAuditRows=" + denied);
        assertThat(denied).as("并发失败侧写真实 DENIED 冲突审计（独立事务）").isGreaterThanOrEqualTo(1);

        // session/cache 零增量核对（并发后同窗回读）：bind 不创建任何会话。
        // 断言「无新增键」而非集合相等：并发窗口内遗留键自然过期（TTL）属正常噪声
        java.util.Set<String> sessionKeysAfter = redis.keys("sw:security:login-user:*");
        boolean user1SessionAfter = redis.hasKey("sw:security:login-user:1");
        boolean user9001SessionAfter = redis.hasKey("sw:security:login-user:9001");
        java.util.Set<String> addedKeys = new java.util.HashSet<>(sessionKeysAfter);
        addedKeys.removeAll(sessionKeysBefore);
        System.out.println("[G6a1] sessionAfter keys=" + sessionKeysAfter.size()
                + " addedKeys=" + addedKeys
                + " user1=" + user1SessionAfter + " user9001=" + user9001SessionAfter
                + " at=" + java.time.LocalDateTime.now());
        assertThat(addedKeys).as("并发绑定不得新增任何会话键").isEmpty();
        assertThat(user1SessionAfter).as("user1 无会话产生").isEqualTo(user1SessionBefore);
        assertThat(user9001SessionAfter).as("user9001 无会话产生").isEqualTo(user9001SessionBefore);
        System.out.println("[G6a1] PASS");
    }

    private String bindInContext(LoginUser actor, String provider, String externalId, CyclicBarrier barrier) {
        LoginUserHolder.set(actor);
        try {
            barrier.await();
            app.getBean(SsoAuthService.class).bind(provider, actor.getUserId(), externalId);
            return "OK:" + actor.getTenantId();
        } catch (IllegalStateException e) {
            return "DENIED(IllegalStateException):" + e.getMessage();
        } catch (Exception e) {
            return "UNEXPECTED(" + e.getClass().getSimpleName() + "):" + e.getMessage();
        } finally {
            LoginUserHolder.clear();
        }
    }

    /** G2a1：登记应用合法签名 HTTP 达业务边界 + PG nonce 行；负向零增量。 */
    @Test
    @DisplayName("G2a1：合法签名 HTTP 达业务边界且 PG nonce 落库；负向拒绝零增量")
    void g2a1OpenApiHttpNoncePostgres() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String secretHash = sha256("sentinel-openapi-secret-g2a");
        long ts = System.currentTimeMillis() / 1000;
        String body = "{\"processKey\":\"i5-g2a1-not-exist\"}";
        String bodyHash = sha256(body);
        Integer nonceBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sw_openapi_nonce WHERE deleted=0", Integer.class);

        // 负向1：缺头
        HttpResponse<String> miss = post(client, "/openapi/v1/processes", body, Map.of());
        System.out.println("[G2a1] missingHeaders -> " + miss.statusCode() + " :: " + snippet(miss.body()));
        assertThat(miss.body()).containsAnyOf("缺少鉴权头", "签名");
        // 负向2：坏签名
        String badSig = hmacSha256("wrong-secret-material", "x");
        HttpResponse<String> bad = post(client, "/openapi/v1/processes", body, authHeaders("i5-pg-openapi-t100", String.valueOf(ts), "i5nonce-bad-1", badSig));
        System.out.println("[G2a1] badSignature -> " + bad.statusCode() + " :: " + snippet(bad.body()));
        assertThat(bad.body()).containsAnyOf("签名", "SIGN");
        // 负向3：过期租户应用（签名合法但租户 300 过期）
        String nonceT300 = "i5nonce-t300-1";
        String sig300 = sign(secretHash, "i5-pg-openapi-t300", String.valueOf(ts), nonceT300, bodyHash);
        HttpResponse<String> expired = post(client, "/openapi/v1/processes", body, authHeaders("i5-pg-openapi-t300", String.valueOf(ts), nonceT300, sig300));
        System.out.println("[G2a1] expiredTenant -> " + expired.statusCode() + " :: " + snippet(expired.body()));
        assertThat(expired.body()).containsAnyOf("3009", "TENANT_INVALID", "租户");
        // 负向4：过期时间戳
        String oldTs = String.valueOf(ts - 4000);
        String nonceOld = "i5nonce-old-1";
        String sigOld = sign(secretHash, "i5-pg-openapi-t100", oldTs, nonceOld, bodyHash);
        HttpResponse<String> stale = post(client, "/openapi/v1/processes", body, authHeaders("i5-pg-openapi-t100", oldTs, nonceOld, sigOld));
        System.out.println("[G2a1] staleTimestamp -> " + stale.statusCode() + " :: " + snippet(stale.body()));
        assertThat(stale.body()).containsAnyOf("时间戳", "3003");
        Integer nonceAfterNegatives = jdbc.queryForObject("SELECT COUNT(*) FROM sw_openapi_nonce WHERE deleted=0", Integer.class);
        System.out.println("[G2a1] nonceRowsAfterNegatives=" + nonceAfterNegatives + " (before=" + nonceBefore + ")");
        assertThat(nonceAfterNegatives).as("负向请求不得留下 nonce 行").isEqualTo(nonceBefore);

        // 正向：合法签名达业务 scope/handler 边界（应用 PROCESS_START scope 校验通过后，
        // 业务层因流程不存在返回业务错误——认证与租户有效链全部通过）
        String nonceOk = "i5nonce-ok-1";
        String sigOk = sign(secretHash, "i5-pg-openapi-t100", String.valueOf(ts), nonceOk, bodyHash);
        HttpResponse<String> ok = post(client, "/openapi/v1/processes", body, authHeaders("i5-pg-openapi-t100", String.valueOf(ts), nonceOk, sigOk));
        System.out.println("[G2a1] validSigned -> " + ok.statusCode() + " :: " + snippet(ok.body()));
        // 到达业务边界：错误为业务语义（流程/表单不存在），不再是鉴权错误
        assertThat(ok.body()).doesNotContain("缺少鉴权头").doesNotContain("签名无效").doesNotContain("3009");

        // PG nonce 行真实落库（仅正向 1 行）
        var nonceRow = jdbc.queryForList(
                "SELECT app_id, tenant_id, nonce, deleted FROM sw_openapi_nonce WHERE nonce = ?", nonceOk);
        System.out.println("[G2a1] pgNonceRow=" + nonceRow);
        assertThat(nonceRow).hasSize(1);
        assertThat(nonceRow.get(0)).containsEntry("tenant_id", 100L);
        // nonce 重放拒绝
        HttpResponse<String> replay = post(client, "/openapi/v1/processes", body, authHeaders("i5-pg-openapi-t100", String.valueOf(ts), nonceOk, sigOk));
        System.out.println("[G2a1] nonceReplay -> " + replay.statusCode() + " :: " + snippet(replay.body()));
        assertThat(replay.body()).containsAnyOf("NONCE", "重放", "nonce");
        Integer nonceFinal = jdbc.queryForObject("SELECT COUNT(*) FROM sw_openapi_nonce WHERE deleted=0", Integer.class);
        assertThat(nonceFinal).as("重放不新增 nonce 行").isEqualTo(nonceAfterNegatives + 1);
        System.out.println("[G2a1] PASS");
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, String> authHeaders(String appId, String ts, String nonce, String sig) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("X-App-Id", appId);
        h.put("X-Timestamp", ts);
        h.put("X-Nonce", nonce);
        h.put("X-Signature", sig);
        return h;
    }

    private static String sign(String secretHash, String appId, String ts, String nonce, String bodyHash) {
        return hmacSha256(secretHash, appId + ts + nonce + bodyHash);
    }

    private static String hmacSha256(String key, String material) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LoginUser user(Long userId, Long tenantId) {
        LoginUser u = new LoginUser();
        u.setUserId(userId);
        u.setTenantId(tenantId);
        return u;
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String clean = body.replace('\n', ' ');
        return clean.substring(0, Math.min(160, clean.length()));
    }

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return java.util.Base64.getEncoder().encodeToString(g.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void tearDown() {
        if (app != null) app.close();
        try { if (pg != null) pg.close(); } catch (Exception ignored) { }
    }
}
