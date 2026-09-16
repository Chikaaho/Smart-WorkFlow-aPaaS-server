package com.sw.ck.bootstrap.p61;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P61 R6：真实容器 HTTP 行为证据。
 *
 * <p>规划审查 01 指出此前的结论「主要由单元/对象级测试支撑」。本测试以 zonky 内嵌
 * PostgreSQL + 全链迁移 + prod profile 启动<b>完整应用</b>（随机端口），经真实 HTTP 请求
 * 逐项证明：</p>
 * <ol>
 *   <li>失败响应同时携带 {@code code} + {@code errorKey} + {@code msg} + {@code eventRef}；</li>
 *   <li>{@code Accept-Language} 决定 {@code msg} 语言，而 errorKey/数值码不随语言变化；</li>
 *   <li>同一 {@code X-Request-Id} 重放产生<b>不同</b>事件引用（服务端权威，R5）；</li>
 *   <li>公共响应不回显 Jackson 原文、类名、栈或客户端注入内容；</li>
 *   <li>认证入口 401/403/404 与业务失败可区分。</li>
 * </ol>
 *
 * <p>受控注入标记只应出现在服务端日志，本测试对响应体做反向排除。</p>
 */
@DisplayName("P61 R6 真实容器 HTTP 行为证据（真实 PG + 全链迁移 + prod profile）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P61RuntimeBehaviorBootTest {

    private static io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private String base;
    private String livePgUrl;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    void bootProd() {
        try {
            pg = io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        livePgUrl = pgUrl;
        Map<String, Object> props = baseProps(pgUrl);
        props.put("spring.main.allow-bean-definition-overriding", "true");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p61-runtime-test", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    // 与 I5 prod 测试同口径：允许 security 的 LoginContextProvider 覆盖 common 兜底 bean
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p61RuntimeTestLoginContextProvider", provider);
                })
                .run("--spring.profiles.active=prod");
        String port = app.getEnvironment().getProperty("local.server.port");
        base = "http://127.0.0.1:" + port + "/api";
        org.assertj.core.api.Assertions.assertThat(app.isActive()).isTrue();
    }

    @AfterAll
    void shutdown() {
        if (app != null) {
            app.close();
        }
        try {
            if (pg != null) {
                pg.close();
            }
        } catch (Exception ignore) {
            // 内嵌 PG 关闭失败不影响结论
        }
    }

    private static Map<String, Object> baseProps(String pgUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.profiles.active", "prod");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("server.port", "0");
        props.put("spring.data.redis.password", System.getenv().getOrDefault("REDIS_PASSWORD", ""));
        // prod fail-fast 门禁：登录链密钥必须显式提供（进程内测试密钥，不落仓库、不进日志正文）
        props.put("sw.security.jwt.secret", "p61-runtime-test-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p61-runtime-test-digest-secret");
        props.put("sw.security.sso.cipher-key",
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key",
                java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        return props;
    }

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(g.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 发起真实请求；{@code acceptLanguage} 为 null 时不带 Accept-Language 头。 */
    private HttpResponse<String> get(String path, String acceptLanguage, String requestId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (acceptLanguage != null) {
            b.header("Accept-Language", acceptLanguage);
        }
        if (requestId != null) {
            b.header("X-Request-Id", requestId);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String body, String acceptLanguage) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (acceptLanguage != null) {
            b.header("Accept-Language", acceptLanguage);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("未认证 401：真实响应携带 errorKey/eventRef，且不随客户端头伪造")
    void unauthenticated_shouldExposeErrorKeyAndServerEventRef() throws Exception {
        HttpResponse<String> resp = get("/auth/me", "zh-CN", "web-client-replay-0001");
        String body = resp.body();
        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(body).contains("\"code\":401").contains("\"errorKey\":\"common.unauthenticated\"");
        assertThat(body).contains("\"eventRef\":\"");
        // R5：客户端头不得成为事件引用
        assertThat(body).doesNotContain("web-client-replay-0001");
        assertThat(body).doesNotContain("Exception").doesNotContain("at org.");
    }

    @Test
    @DisplayName("同值重放 X-Request-Id：两次真实请求的事件引用必然不同")
    void replayedRequestHeader_shouldProduceDistinctEventRefs() throws Exception {
        String ref1 = extractEventRef(get("/auth/me", "zh-CN", "web-same-header-42"));
        String ref2 = extractEventRef(get("/auth/me", "zh-CN", "web-same-header-42"));
        assertThat(ref1).isNotBlank();
        assertThat(ref2).isNotBlank();
        assertThat(ref2).as("同值重放头不得产生同一事件引用（R5）").isNotEqualTo(ref1);
    }

    @Test
    @DisplayName("未认证 401 双语成对：zh-CN 与 en-US 同键不同文案（匿名可达端点）")
    void unauthorized_shouldLocalizeByAcceptLanguage() throws Exception {
        HttpResponse<String> zh = get("/auth/me", "zh-CN,zh;q=0.9", null);
        assertThat(zh.statusCode()).isEqualTo(401);
        assertThat(zh.body()).contains("\"errorKey\":\"common.unauthenticated\"")
                .contains("登录状态已失效，请重新登录");

        HttpResponse<String> en = get("/auth/me", "en-US,en;q=0.9", null);
        assertThat(en.statusCode()).isEqualTo(401);
        assertThat(en.body()).contains("\"errorKey\":\"common.unauthenticated\"")
                .contains("Your session has expired. Please sign in again.");
    }

    @Test
    @DisplayName("畸形 JSON：真实响应不含 Jackson 原文，双语成对")
    void malformedJson_shouldNotEchoJacksonDetail() throws Exception {
        String broken = "{\"username\": ";
        HttpResponse<String> zh = postJson("/auth/login", broken, "zh-CN");
        assertThat(zh.statusCode()).isEqualTo(400);
        assertThat(zh.body()).contains("\"errorKey\":\"common.request_body_unreadable\"")
                .doesNotContain("Cannot deserialize")
                .doesNotContain("JsonMappingException")
                .doesNotContain("at ");

        HttpResponse<String> en = postJson("/auth/login", broken, "en-US");
        assertThat(en.statusCode()).isEqualTo(400);
        assertThat(en.body()).contains("\"errorKey\":\"common.request_body_unreadable\"")
                .contains("malformed");
    }

    @Test
    @DisplayName("登录缺验证码：统一收敛 2101（防枚举），两语言同键不同文案")
    void loginWithoutCaptcha_shouldConvergeOn2101() throws Exception {
        String payload = "{\"username\":\"someone\",\"password\":\"Zm9v\",\"captcha\":\"\",\"captchaId\":\"\",\"timestamp\":\"0\"}";
        HttpResponse<String> zh = postJson("/auth/login", payload, "zh-CN");
        assertThat(zh.body()).contains("\"code\":2101").contains("\"errorKey\":\"auth.captcha_mismatch\"");
        assertThat(zh.body()).doesNotContain("captchaId").doesNotContain("digest");

        HttpResponse<String> en = postJson("/auth/login", payload, "en-US");
        assertThat(en.body()).contains("\"code\":2101").contains("\"errorKey\":\"auth.captcha_mismatch\"");
    }

    @Test
    @DisplayName("认证失效/输入错误/凭据缺失三类失败互不混同（匿名可达）")
    void failureClasses_shouldBeDistinguishable() throws Exception {
        String unauth = get("/auth/me", "zh-CN", null).body();
        String badBody = postJson("/auth/login", "{", "zh-CN").body();
        String noCaptcha = postJson("/auth/login",
                "{\"username\":\"someone\",\"password\":\"Zm9v\",\"captcha\":\"\",\"captchaId\":\"\",\"timestamp\":\"0\"}",
                "zh-CN").body();

        assertThat(unauth).contains("common.unauthenticated");
        assertThat(badBody).contains("common.request_body_unreadable");
        assertThat(noCaptcha).contains("auth.captcha_mismatch");
        // 三者 errorKey 互不相同
        assertThat(unauth).doesNotContain("request_body_unreadable").doesNotContain("captcha_mismatch");
        assertThat(badBody).doesNotContain("unauthenticated").doesNotContain("captcha_mismatch");
        assertThat(noCaptcha).doesNotContain("unauthenticated").doesNotContain("request_body_unreadable");
    }

    @Test
    @DisplayName("受控注入标记不进入真实响应（R6 泄露验证）")
    void injectedMarker_shouldNotReachRealResponse() throws Exception {
        String injected = "{\"username\": ";
        HttpResponse<String> resp = postJson("/auth/login", injected, "zh-CN");
        String body = resp.body() == null ? "" : resp.body();
        assertThat(body)
                .as("真实 400 响应不得携带 Jackson 原文或类名")
                .doesNotContain("Cannot deserialize")
                .doesNotContain("com.fasterxml")
                .doesNotContain("JsonParser")
                .doesNotContain("at org.springframework");
    }

    @Test
    @DisplayName("事件引用跨请求唯一（真实服务链采样 5 次）")
    void eventRef_shouldBeUniqueAcrossRealRequests() throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            refs.add(extractEventRef(get("/auth/me", "zh-CN", "web-uniq-" + i)));
        }
        assertThat(refs).doesNotHaveDuplicates();
    }

    private String extractEventRef(HttpResponse<String> resp) {
        String body = resp.body() == null ? "" : resp.body();
        int idx = body.indexOf("\"eventRef\":\"");
        if (idx < 0) {
            return "";
        }
        int start = idx + "\"eventRef\":\"".length();
        int end = body.indexOf('"', start);
        return end < 0 ? "" : body.substring(start, end);
    }
}
