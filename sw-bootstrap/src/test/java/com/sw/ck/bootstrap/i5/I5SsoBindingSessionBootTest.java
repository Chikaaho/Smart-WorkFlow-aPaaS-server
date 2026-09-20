package com.sw.ck.bootstrap.i5;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.system.controller.SsoTicketStore;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I5 复验（G6b1）：绑定—受控票据—会话生命周期的真实 HTTP 行为链。
 * <p>
 * Provider 真实回调成功链属 G8 外部依赖；本测试以真实 {@link SsoTicketStore}
 * bean 受控签发一次性会话票据（等价"受控 Provider 对端"边界），其余全部走
 * 真实层级：RANDOM_PORT 真实 HTTP、dev H2 独立实例（devseed 夹具 + 本测试
 * 专属用户 9501，避免与运行中 dev server 的 Redis 会话键串扰）、本机真实
 * Redis。验证：有效租户票据兑换会话（与第一方同 TokenResponse/cookie 契约）、
 * 票据一次性消费、租户停用后兑换拒绝且既有会话权威装载收敛、解绑后绑定
 * 视图清空、role 表零增量。
 * </p>
 */
@DisplayName("I5 G6b1 绑定/票据/会话生命周期（真实 HTTP + H2 + Redis）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I5SsoBindingSessionBootTest {

    private static final long USER_ID = 9501L;
    private static final long TENANT_ID = 100L;
    private static final String SESSION_KEY = "sw:security:login-user:" + USER_ID;

    private ConfigurableApplicationContext app;
    private String base;
    private HttpClient http;
    private JdbcTemplate jdbc;
    private String bindingDigest = "a1b2c3d4e5f60718293a4b5c6d7e8f9a0a1b2c3d4e5f60718293a4b5c6d7e8f9";

    private int roleCountBefore;

    @BeforeAll
    void boot() {
        Map<String, Object> props = new HashMap<>();
        // 真实完整启动（application.yml 基线 + 显式测试密钥）；数据源为本测试
        // 专属 H2 内存库，避免与其他 JVM 串扰；devseed 不在基线 locations，
        // tenant/dept 夹具由本测试 JDBC 显式建立
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.profiles.active", "dev");
        props.put("ch.dev.test-mock", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.h2.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url",
                "jdbc:h2:mem:i5g6b1;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        props.put("spring.datasource.dynamic.datasource.master.username", "sa");
        props.put("spring.datasource.dynamic.datasource.master.password", "");
        props.put("sw.security.jwt.secret", "i5-g6b1-test-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "i5-g6b1-test-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    // addFirst：测试属性源必须压过 application.yml（默认 .properties 优先级最低）
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("i5-g6b1-test", props));
                    // dev profile 需显式激活：getActiveProfiles 在环境准备阶段已缓存，
                    // 仅靠属性源中的 spring.profiles.active 不会生效
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getSystemProperties().put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("i5G6b1LoginContextProvider", provider);
                })
                .run();
        String port = app.getEnvironment().getProperty("local.server.port");
        base = "http://127.0.0.1:" + port + "/api";
        http = HttpClient.newHttpClient();
        jdbc = app.getBean(JdbcTemplate.class);
        seedTenantFixture();
        System.out.println("[G6b1-BOOT] 完整启动成功（H2 + 真实 Redis），port=" + port);
    }

    private String issueTicket(long userId, long tenantId) {
        return app.getBean(SsoTicketStore.class).issue(userId, tenantId);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private int roleCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE deleted=0", Integer.class);
        return n == null ? -1 : n;
    }

    @Test
    @DisplayName("有效租户票据兑换会话 + 一次性消费 + 停用租户拒绝 + 权威收敛 + 解绑视图")
    void bindingTicketSessionLifecycle() throws Exception {
        // 夹具：测试专属用户 9501（租户 100，未停用）+ ACTIVE 绑定行
        jdbc.update("MERGE INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, " +
                        "username, password, real_name, dept_id, status, is_admin) KEY (id) VALUES " +
                        "(?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'i5g6b1user', " +
                        "'$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'I5G6b1用户', 1001, 0, 0)",
                USER_ID, TENANT_ID);
        jdbc.update("MERGE INTO sys_sso_user_binding (id, create_time, update_time, deleted, tenant_id, version, " +
                        "provider, external_id, external_digest, user_id, bind_status) KEY (id) VALUES " +
                        "(90011, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'WECOM', ?, ?, ?, 'ACTIVE')",
                TENANT_ID, bindingDigest, bindingDigest, USER_ID);
        roleCountBefore = roleCount();

        // 1) 有效租户：受控票据兑换真实会话
        String ticket1 = issueTicket(USER_ID, TENANT_ID);
        HttpResponse<String> r1 = post("/auth/sso/ticket", "{\"ticket\":\"" + ticket1 + "\"}");
        String body1 = r1.body();
        System.out.println("[G6b1] ticket1兑换 -> " + r1.statusCode() + " :: " + snippet(body1));
        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(body1).contains("accessToken").contains("expiresIn");
        String token1 = extract(body1, "accessToken");

        // 会话可用（me 200）+ Redis 会话行存在
        HttpResponse<String> me1 = get("/system/auth/me", token1);
        System.out.println("[G6b1] me(token1) -> " + me1.statusCode() + " :: " + snippet(me1.body()));
        assertThat(me1.statusCode()).isEqualTo(200);
        assertThat(sessionRowExists()).isTrue();
        System.out.println("[G6b1] redis session row exists=true");

        // 2) 票据一次性：同一票据二次兑换拒绝
        HttpResponse<String> r1b = post("/auth/sso/ticket", "{\"ticket\":\"" + ticket1 + "\"}");
        System.out.println("[G6b1] ticket1重放 -> " + r1b.statusCode() + " :: " + snippet(r1b.body()));
        assertThat(r1b.body()).contains("票据无效或已过期");

        // 3) 租户停用：新票据兑换拒绝（票据仍在有效期内）
        jdbc.update("UPDATE sys_tenant SET status=1 WHERE id=?", TENANT_ID);
        String ticket2 = issueTicket(USER_ID, TENANT_ID);
        HttpResponse<String> r2 = post("/auth/sso/ticket", "{\"ticket\":\"" + ticket2 + "\"}");
        System.out.println("[G6b1] 停用租户ticket2 -> " + r2.statusCode() + " :: " + snippet(r2.body()));
        assertThat(r2.body()).contains("system.sso_tenant_invalid");

        // 4) 既有会话在下一次权威装载收敛：清缓存后 me 401
        delSessionRow();
        HttpResponse<String> me2 = get("/system/auth/me", token1);
        System.out.println("[G6b1] me停用租户+权威装载 -> " + me2.statusCode() + " :: " + snippet(me2.body()));
        assertThat(me2.statusCode()).isEqualTo(401);

        // 5) 恢复租户后解绑：绑定视图清空，绑定行 UNBOUND
        jdbc.update("UPDATE sys_tenant SET status=0 WHERE id=?", TENANT_ID);
        delSessionRow();
        HttpResponse<String> me3 = get("/system/auth/me", token1);
        System.out.println("[G6b1] me恢复租户 -> " + me3.statusCode());
        assertThat(me3.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE sys_sso_user_binding SET bind_status='UNBOUND' WHERE id=90011");
        HttpResponse<String> bindings = get("/auth/sso/bindings", token1);
        System.out.println("[G6b1] 解绑后bindings -> " + bindings.statusCode() + " :: " + snippet(bindings.body()));
        assertThat(bindings.body()).contains("\"bindings\":[]");
        String status = jdbc.queryForObject(
                "SELECT bind_status FROM sys_sso_user_binding WHERE id=90011", String.class);
        assertThat(status).isEqualTo("UNBOUND");

        // 6) role 表零增量
        assertThat(roleCount()).as("sys_user_role 增量必须为 0").isEqualTo(roleCountBefore);
        System.out.println("[G6b1] role delta=0（before=" + roleCountBefore + " after=" + roleCount() + "）");
    }

    /** 带响应头的 POST（捕获 refresh cookie）。 */
    private HttpResponse<String> postCapture(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("G6b1 续：过期租户票据/refresh/权威装载拒绝 + 解绑既有会话收敛（真实 unbind 路径）")
    void expiredTenantAndUnbindConvergence() throws Exception {
        // 新隔离对象（iteration-05 的 9501/90011 已随其测试 JVM 销毁，旧→新登记）
        long userId = 9502L;
        String sessionKey = "sw:security:login-user:" + userId;
        org.springframework.data.redis.core.StringRedisTemplate redis =
                app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        int roleBefore = roleCount();
        String secondDigest = new StringBuilder(bindingDigest).reverse().toString();
        jdbc.update("MERGE INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, " +
                        "username, password, real_name, dept_id, status, is_admin) KEY (id) VALUES " +
                        "(?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'i5g6b1user2', " +
                        "'$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'I5G6b1用户2', 1001, 0, 0)",
                userId, TENANT_ID);
        jdbc.update("MERGE INTO sys_sso_user_binding (id, create_time, update_time, deleted, tenant_id, version, " +
                        "provider, external_id, external_digest, user_id, bind_status) KEY (id) VALUES " +
                        "(90012, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'WECOM', ?, ?, ?, 'ACTIVE')",
                TENANT_ID, secondDigest, secondDigest, userId);

        // ===== 1) 有效租户：受控票据建立真实会话 =====
        HttpResponse<String> r1 = postCapture("/auth/sso/ticket", "{\"ticket\":\"" + issueTicket(userId, TENANT_ID) + "\"}");
        System.out.println("[G6b1b] ticket1 -> " + r1.statusCode() + " :: " + snippet(r1.body()));
        assertThat(r1.statusCode()).isEqualTo(200);
        String token1 = extract(r1.body(), "accessToken");
        String refreshCookie1 = (r1.headers().firstValue("set-cookie").orElse("")).split(";")[0];
        int me1Status = get("/system/auth/me", token1).statusCode();
        System.out.println("[G6b1b][dbg] me1 -> " + me1Status + " token1Prefix=" + (token1 == null ? "null" : token1.substring(0, 20)));
        System.out.println("[G6b1b][dbg] user9502=" + jdbc.queryForList(
                "SELECT id, tenant_id, status, deleted FROM sys_user WHERE id=9502"));
        System.out.println("[G6b1b][dbg] tenant100=" + jdbc.queryForList(
                "SELECT id, status, expire_time FROM sys_tenant WHERE id=100"));
        System.out.println("[G6b1b][dbg] allSecurityKeys=" + redis.keys("sw:security:*"));
        assertThat(me1Status).isEqualTo(200);
        assertThat(redis.hasKey(sessionKey)).isTrue();
        System.out.println("[G6b1b] me1=200, sessionRow=true");

        // ===== 2) 过期租户（权威 expire_time 置过去）=====
        jdbc.update("UPDATE sys_tenant SET expire_time = TIMESTAMP '2020-01-01 00:00:00' WHERE id=?", TENANT_ID);
        HttpResponse<String> r2 = postCapture("/auth/sso/ticket", "{\"ticket\":\"" + issueTicket(userId, TENANT_ID) + "\"}");
        System.out.println("[G6b1b] 过期租户ticket2 -> " + r2.statusCode() + " :: " + snippet(r2.body()));
        assertThat(r2.body()).contains("system.sso_tenant_invalid");
        HttpResponse<String> rf = postCapture("/auth/refresh", "");
        // refresh 需携带 cookie：单独请求
        HttpRequest rfReq = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookie1)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rfResp = http.send(rfReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6b1b] 过期租户refresh -> " + rfResp.statusCode() + " :: " + snippet(rfResp.body()));
        assertThat(rfResp.body()).containsAnyOf("租户不可用", "401");
        HttpResponse<String> meCached = get("/system/auth/me", token1);
        System.out.println("[G6b1b] me过期租户(缓存命中) -> " + meCached.statusCode());
        // 清缓存模拟 TTL 到期 → 下一次权威装载（租户过期）拒绝
        redis.delete(sessionKey);
        HttpResponse<String> meReload = get("/system/auth/me", token1);
        System.out.println("[G6b1b] me过期租户+权威装载 -> " + meReload.statusCode());
        assertThat(meReload.statusCode()).isEqualTo(401);
        assertThat(redis.hasKey(sessionKey)).as("拒绝的装载不得重建会话行").isFalse();

        // ===== 3) 恢复租户 → 重建会话 → 真实 unbind → 既有会话收敛 =====
        jdbc.update("UPDATE sys_tenant SET expire_time = NULL WHERE id=?", TENANT_ID);
        assertThat(get("/system/auth/me", token1).statusCode()).isEqualTo(200);
        HttpResponse<String> r3 = postCapture("/auth/sso/ticket", "{\"ticket\":\"" + issueTicket(userId, TENANT_ID) + "\"}");
        assertThat(r3.statusCode()).isEqualTo(200);
        String token3 = extract(r3.body(), "accessToken");
        String refreshCookie3 = (r3.headers().firstValue("set-cookie").orElse("")).split(";")[0];
        assertThat(get("/system/auth/me", token3).statusCode()).isEqualTo(200);
        System.out.println("[G6b1b] 恢复租户后重建会话 me=200");
        // 真实 HTTP 解绑路径（Bearer token3）→ 服务端撤销会话
        HttpRequest ubReq = HttpRequest.newBuilder(URI.create(base + "/auth/sso/unbind"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token3)
                .POST(HttpRequest.BodyPublishers.ofString("{\"provider\":\"WECOM\"}"))
                .build();
        HttpResponse<String> ubResp = http.send(ubReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6b1b] unbind -> " + ubResp.statusCode() + " :: " + snippet(ubResp.body()));
        assertThat(ubResp.statusCode()).isEqualTo(200);
        // 既有会话在下一次权威装载时拒绝（解绑：清缓存 + 撤销标记）
        HttpResponse<String> meAfterUnbind = get("/system/auth/me", token3);
        System.out.println("[G6b1b] me解绑后 -> " + meAfterUnbind.statusCode() + " :: " + snippet(meAfterUnbind.body()));
        assertThat(meAfterUnbind.statusCode()).as("解绑后既有会话权威装载必须拒绝").isEqualTo(401);
        assertThat(redis.hasKey(sessionKey)).as("会话行已清理").isFalse();
        assertThat(redis.hasKey("sw:security:token-revoked:" + sha256Hex(token3))).as("token 摘要撤销标记已写入").isTrue();
        HttpRequest rfReq3 = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookie3)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rf3 = http.send(rfReq3, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6b1b] 解绑后refresh -> " + rf3.statusCode() + " :: " + snippet(rf3.body()));
        assertThat(rf3.body()).containsAnyOf("401", "无效", "过期");
        String bindStatus = jdbc.queryForObject(
                "SELECT bind_status FROM sys_sso_user_binding WHERE id=90012", String.class);
        assertThat(bindStatus).isEqualTo("UNBOUND");
        assertThat(roleCount()).as("role 表零增量").isEqualTo(roleBefore);
        System.out.println("[G6b1b] role delta=0（before=" + roleBefore + " after=" + roleCount() + "）");
        System.out.println("[G6b1b] PASS");
    }

    /** 第一方登录（真实 /auth/login：challenge + RSA-OAEP + dev 固定验证码 1234）。 */
    private HttpResponse<String> loginFirstParty(String username) throws Exception {
        Challenge ch = fetchChallenge();
        return submitLogin(username, ch);
    }

    private record Challenge(String captchaId, String publicKey) {}

    /** 预取登录 challenge（RSA 密钥生成在登录序列外完成，用于压缩 A/B 签发间隔以制造同秒条件）。 */
    private Challenge fetchChallenge() throws Exception {
        HttpResponse<String> ch = get("/auth/challenge", null);
        com.fasterxml.jackson.databind.JsonNode data = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(ch.body()).path("data");
        return new Challenge(data.path("captchaId").asText(), data.path("publicKey").asText());
    }

    /** 用预取的 challenge 提交真实登录（RSA-OAEP-SHA256 加密 + dev 固定验证码）。 */
    private HttpResponse<String> submitLogin(String username, Challenge ch) throws Exception {
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        java.security.PublicKey pub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(
                java.util.Base64.getDecoder().decode(ch.publicKey())));
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        // SunJCE 默认 MGF1 用 SHA-1，与服务端 OAEP-SHA256 不一致，必须显式指定
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pub,
                new javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
                        java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT));
        String encrypted = java.util.Base64.getEncoder().encodeToString(
                cipher.doFinal("admin123".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "username", username, "password", encrypted,
                "captcha", "1234", "captchaId", ch.captchaId(),
                "timestamp", String.valueOf(System.currentTimeMillis())));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("G6c1：解绑旧会话 A → 第一方新会话 B 建立与缓存重载有效 → A 全程拒绝（会话代际隔离）")
    void unbindGenerationIsolation() throws Exception {
        // 新隔离对象：user 9503 + ACTIVE 绑定（9501/9502 已随前轮测试 JVM 销毁，旧→新登记）
        long userId = 9503L;
        String username = "i5g6c1user";
        String sessionKey = "sw:security:login-user:" + userId;
        org.springframework.data.redis.core.StringRedisTemplate redis =
                app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        int roleBefore = roleCount();
        String thirdDigest = new StringBuilder(bindingDigest).reverse().toString();
        char flip = thirdDigest.charAt(0) == 'f' ? 'e' : 'f';
        thirdDigest = flip + thirdDigest.substring(1);
        jdbc.update("MERGE INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, " +
                        "username, password, real_name, dept_id, status, is_admin) KEY (id) VALUES " +
                        "(?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, ?, " +
                        "'$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'I5G6c1用户', 1001, 0, 0)",
                userId, TENANT_ID, username);
        jdbc.update("MERGE INTO sys_sso_user_binding (id, create_time, update_time, deleted, tenant_id, version, " +
                        "provider, external_id, external_digest, user_id, bind_status) KEY (id) VALUES " +
                        "(90013, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'WECOM', ?, ?, ?, 'ACTIVE')",
                TENANT_ID, thirdDigest, thirdDigest, userId);

        // ===== 1) 建立 A：同一 user 的第一方登录 =====
        HttpResponse<String> loginA = loginFirstParty(username);
        System.out.println("[G6c1] loginA -> " + loginA.statusCode() + " :: " + snippet(loginA.body()));
        assertThat(loginA.statusCode()).isEqualTo(200);
        String tokenA = extract(loginA.body(), "accessToken");
        String refreshCookieA = (loginA.headers().firstValue("set-cookie").orElse("")).split(";")[0];
        String digestA = sha256Hex(tokenA);
        assertThat(get("/system/auth/me", tokenA).statusCode()).isEqualTo(200);
        assertThat(redis.hasKey(sessionKey)).isTrue();
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestA)).isFalse();
        System.out.println("[G6c1] A 建立：me=200, digestA=" + digestA.substring(0, 12) + ", sessionRow=true");

        // ===== 2) 解绑（真实 HTTP，Bearer A）=====
        HttpRequest ubReq = HttpRequest.newBuilder(URI.create(base + "/auth/sso/unbind"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tokenA)
                .POST(HttpRequest.BodyPublishers.ofString("{\"provider\":\"WECOM\"}"))
                .build();
        HttpResponse<String> ub = http.send(ubReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6c1] unbind -> " + ub.statusCode() + " :: " + snippet(ub.body()));
        assertThat(ub.statusCode()).isEqualTo(200);
        HttpResponse<String> meA1 = get("/system/auth/me", tokenA);
        System.out.println("[G6c1] A 解绑后 me -> " + meA1.statusCode());
        assertThat(meA1.statusCode()).isEqualTo(401);
        HttpRequest rfA1 = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookieA)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rfA1Resp = http.send(rfA1, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6c1] A 解绑后 refresh -> " + rfA1Resp.statusCode() + " :: " + snippet(rfA1Resp.body()));
        assertThat(rfA1Resp.body()).containsAnyOf("401", "无效", "失效");
        assertThat(redis.hasKey(sessionKey)).isFalse();
        System.out.println("[G6c1][dbg] revokedKeys=" + redis.keys("sw:security:token-revoked:*"));
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestA)).isTrue();

        // ===== 3) 第一方登录建立 B（同 user）=====
        // JWT 无 jti、iat 秒级：同秒内两次登录会产出逐字节相同的 token；
        // 跨秒等待保证 A/B token 不同（同秒碰撞语义作为已知边界登记于证据）
        Thread.sleep(1100);
        HttpResponse<String> loginB = loginFirstParty(username);
        System.out.println("[G6c1] loginB -> " + loginB.statusCode());
        assertThat(loginB.statusCode()).isEqualTo(200);
        String tokenB = extract(loginB.body(), "accessToken");
        String refreshCookieB = (loginB.headers().firstValue("set-cookie").orElse("")).split(";")[0];
        String digestB = sha256Hex(tokenB);
        assertThat(digestB).isNotEqualTo(digestA);
        HttpResponse<String> meB1 = get("/system/auth/me", tokenB);
        System.out.println("[G6c1] B me -> " + meB1.statusCode());
        assertThat(meB1.statusCode()).as("新会话 B 不得被 A 的撤销标记误伤").isEqualTo(200);
        HttpRequest rfB1 = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookieB)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rfB1Resp = http.send(rfB1, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6c1] B refresh -> " + rfB1Resp.statusCode() + " :: " + snippet(rfB1Resp.body()));
        assertThat(rfB1Resp.body()).contains("accessToken");
        // refresh 旋转语义：rfB1 轮换出新的 refresh cookie，后续必须用新 cookie
        String refreshCookieB2 = (rfB1Resp.headers().firstValue("set-cookie").orElse(refreshCookieB)).split(";")[0];

        // ===== 4) A 再拒绝（B 的 userId 缓存存在，A 不得借缓存复活）=====
        assertThat(redis.hasKey(sessionKey)).as("B 的缓存条目存在（A 借道复活的诱因）").isTrue();
        HttpResponse<String> meA2 = get("/system/auth/me", tokenA);
        System.out.println("[G6c1] A 借 B 缓存尝试 me -> " + meA2.statusCode());
        assertThat(meA2.statusCode()).as("A 不得借 B 的 userId 缓存复活").isEqualTo(401);

        // ===== 5) 清 B 缓存 → B 权威装载成功 =====
        redis.delete(sessionKey);
        HttpResponse<String> meB2 = get("/system/auth/me", tokenB);
        System.out.println("[G6c1] B 缓存重载 me -> " + meB2.statusCode());
        assertThat(meB2.statusCode()).as("B 权威装载恢复").isEqualTo(200);
        HttpRequest rfB2 = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookieB2)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rfB2Resp = http.send(rfB2, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6c1] B 重载后 refresh -> " + rfB2Resp.statusCode());
        assertThat(rfB2Resp.body()).contains("accessToken");

        // ===== 6) A 终局再拒绝 =====
        HttpResponse<String> meA3 = get("/system/auth/me", tokenA);
        System.out.println("[G6c1] A 终局 me -> " + meA3.statusCode());
        assertThat(meA3.statusCode()).isEqualTo(401);
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestA)).isTrue();
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestB)).isFalse();

        assertThat(roleCount()).as("role 表零增量").isEqualTo(roleBefore);
        System.out.println("[G6c1] role delta=0（before=" + roleBefore + " after=" + roleCount() + "）");
        System.out.println("[G6c1] PASS");
    }

    /** 解码 JWT payload（不验签，仅读 iat/jti 用于证据记录）。 */
    private static java.util.Map<String, Object> jwtClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] json = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, java.util.Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("G6c1a：同秒 A/B 代际隔离——解绑 A 后立即登录 B，B 全链有效、A 持续拒绝（无人为等待）")
    void unbindSameSecondGenerationIsolation() throws Exception {
        // 新隔离对象：user 9504 + ACTIVE 绑定（9501—9503 属历史轮对象，旧→新登记）
        long userId = 9504L;
        String username = "i5g6c1auser";
        String sessionKey = "sw:security:login-user:" + userId;
        org.springframework.data.redis.core.StringRedisTemplate redis =
                app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        int roleBefore = roleCount();
        String fourthDigest = new StringBuilder(bindingDigest).reverse().toString();
        // 首字符取 '8'：与 90012 夹具（reversed 原值）在外部身份唯一键上区分
        fourthDigest = "8" + fourthDigest.substring(1);
        jdbc.update("MERGE INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, " +
                        "username, password, real_name, dept_id, status, is_admin) KEY (id) VALUES " +
                        "(?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, ?, " +
                        "'$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'I5G6c1a用户', 1001, 0, 0)",
                userId, TENANT_ID, username);
        jdbc.update("MERGE INTO sys_sso_user_binding (id, create_time, update_time, deleted, tenant_id, version, " +
                        "provider, external_id, external_digest, user_id, bind_status) KEY (id) VALUES " +
                        "(90014, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 'WECOM', ?, ?, ?, 'ACTIVE')",
                TENANT_ID, fourthDigest, fourthDigest, userId);

        // ===== 1) 建立 A → 解绑 → 立即 B：预取双 challenge 压缩 A/B 签发间隔（无 sleep/等待），
        // 若该代 A/B 恰跨秒则重试下一代（记录尝试次数），直至取得同签发秒的一代 =====
        String tokenA = null, tokenB = null, refreshCookieA = null, refreshCookieB = null;
        String digestA = null, digestB = null;
        long iatA = 0, iatB = 0;
        int attempts = 0;
        while (true) {
            attempts++;
            if (attempts > 20) {
                throw new AssertionError("20 次尝试未取得同签发秒代际（非预期）");
            }
            jdbc.update("UPDATE sys_sso_user_binding SET bind_status='ACTIVE' WHERE id=90014");
            Challenge chA = fetchChallenge();
            Challenge chB = fetchChallenge();
            HttpResponse<String> loginA = submitLogin(username, chA);
            assertThat(loginA.statusCode()).as("attempt %s loginA", attempts).isEqualTo(200);
            tokenA = extract(loginA.body(), "accessToken");
            refreshCookieA = (loginA.headers().firstValue("set-cookie").orElse("")).split(";")[0];
            digestA = sha256Hex(tokenA);
            iatA = ((Number) jwtClaims(tokenA).get("iat")).longValue();
            HttpRequest ubReq = HttpRequest.newBuilder(URI.create(base + "/auth/sso/unbind"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tokenA)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"provider\":\"WECOM\"}"))
                    .build();
            HttpResponse<String> ub = http.send(ubReq, HttpResponse.BodyHandlers.ofString());
            assertThat(ub.statusCode()).as("attempt %s unbind", attempts).isEqualTo(200);
            HttpResponse<String> loginB = submitLogin(username, chB);
            assertThat(loginB.statusCode()).as("attempt %s loginB", attempts).isEqualTo(200);
            tokenB = extract(loginB.body(), "accessToken");
            refreshCookieB = (loginB.headers().firstValue("set-cookie").orElse("")).split(";")[0];
            digestB = sha256Hex(tokenB);
            iatB = ((Number) jwtClaims(tokenB).get("iat")).longValue();
            System.out.println("[G6c1a] attempt " + attempts + ": iatA=" + iatA + " iatB=" + iatB
                    + " 同秒=" + (iatA == iatB)
                    + " digestA=" + digestA.substring(0, 12) + " digestB=" + digestB.substring(0, 12));
            if (iatA == iatB) {
                break;
            }
        }
        System.out.println("[G6c1a] A 建立→解绑→B 立即登录完成（无等待），attempts=" + attempts
                + "，同签发秒=" + iatA);
        assertThat(iatB).as("A/B 必须落在同一签发秒（无人为等待）").isEqualTo(iatA);
        assertThat(digestB).as("同秒两代 token 必须身份不同（jti 唯一性）").isNotEqualTo(digestA);
        assertThat(get("/system/auth/me", tokenA).statusCode())
                .as("被解绑撤销的 A 必须拒绝").isEqualTo(401);
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestA)).isTrue();
        System.out.println("[G6c1a] A 撤销确认：me=401, marker(digestA)=true");

        // ===== 4) B 全链有效：me + refresh（轮换 cookie）=====
        assertThat(get("/system/auth/me", tokenB).statusCode())
                .as("B 不得被 A 的撤销标记误伤").isEqualTo(200);
        HttpRequest rfB1 = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookieB)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rfB1Resp = http.send(rfB1, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6c1a] B me=200, refresh -> " + rfB1Resp.statusCode());
        assertThat(rfB1Resp.body()).contains("accessToken");
        String refreshCookieB2 = (rfB1Resp.headers().firstValue("set-cookie").orElse(refreshCookieB)).split(";")[0];

        // ===== 5) A 借 B 的 userId 缓存尝试复活：拒绝 =====
        assertThat(redis.hasKey(sessionKey)).as("B 的缓存条目存在（复活诱因）").isTrue();
        assertThat(get("/system/auth/me", tokenA).statusCode())
                .as("A 不得借 B 缓存复活").isEqualTo(401);

        // ===== 6) 清 B 缓存 → B 权威装载恢复 =====
        redis.delete(sessionKey);
        assertThat(get("/system/auth/me", tokenB).statusCode())
                .as("B 权威装载恢复").isEqualTo(200);
        HttpRequest rfB2 = HttpRequest.newBuilder(URI.create(base + "/auth/refresh"))
                .header("Cookie", refreshCookieB2)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> rfB2Resp = http.send(rfB2, HttpResponse.BodyHandlers.ofString());
        System.out.println("[G6c1a] B 重载 me=200, refresh -> " + rfB2Resp.statusCode());
        assertThat(rfB2Resp.body()).contains("accessToken");

        // ===== 7) A 终局拒绝 + 撤销标记与 B 身份不混淆 =====
        assertThat(get("/system/auth/me", tokenA).statusCode()).isEqualTo(401);
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestA)).isTrue();
        assertThat(redis.hasKey("sw:security:token-revoked:" + digestB)).isFalse();
        assertThat(roleCount()).as("role 表零增量").isEqualTo(roleBefore);
        System.out.println("[G6c1a] marker(digestA)=true marker(digestB)=false, role delta=0");
        System.out.println("[G6c1a] PASS");
    }

    private void seedTenantFixture() {
        jdbc.update("MERGE INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, " +
                        "name, code, status, description) KEY (id) VALUES " +
                        "(?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'I5G6b1测试租户', 'i5-g6b1-t100', 0, 'G6b1 controlled fixture')",
                TENANT_ID);
        jdbc.update("MERGE INTO sys_dept (id, create_time, update_time, deleted, tenant_id, version, " +
                        "parent_id, name, code, sort, status, description) KEY (id) VALUES " +
                        "(?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, ?, 0, 0, 'I5G6b1根部门', 'i5-g6b1-root', 0, 0, 'G6b1 controlled fixture')",
                1001L, TENANT_ID);
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

    private boolean sessionRowExists() {
        try {
            return Boolean.TRUE.equals(app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
                    .hasKey(SESSION_KEY));
        } catch (Exception e) {
            System.out.println("[G6b1] redis hasKey 失败: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void delSessionRow() {
        try {
            app.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).delete(SESSION_KEY);
        } catch (Exception e) {
            System.out.println("[G6b1] redis del 失败: " + e.getClass().getSimpleName());
        }
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String clean = body.replace('\n', ' ');
        return clean.substring(0, Math.min(150, clean.length()));
    }

    private static String extract(String body, String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return node.path("data").path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @AfterAll
    void tearDown() {
        if (app != null) app.close();
    }
}
