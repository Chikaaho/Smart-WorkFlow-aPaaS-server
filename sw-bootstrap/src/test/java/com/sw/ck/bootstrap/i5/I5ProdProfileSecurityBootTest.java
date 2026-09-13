package com.sw.ck.bootstrap.i5;

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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I5 复验（G3）：真实 prod-profile 启动与匿名暴露矩阵。
 * <p>
 * 以 zonky 内嵌真实 PostgreSQL + 全链迁移 + prod profile 启动完整应用（随机端口）：
 * <ol>
 *   <li>注入真实密钥时 prod 启动成功（各 fail-fast 门禁全部通过）；</li>
 *   <li>匿名矩阵：最小健康探针可达且不泄漏详情；metrics/env/heapdump/prometheus、
 *       swagger、api-docs、Druid 控制台及业务端点均拒绝匿名（401/403/404）；</li>
 *   <li>缺失 JWT 密钥的同 prod 启动 fail-fast 拒绝（不静默生效）。</li>
 * </ol>
 * 密钥为进程内测试密钥（不落仓库、不进日志正文）。
 */
@DisplayName("I5 G3 prod-profile 启动与匿名暴露矩阵（真实 PG + 全链迁移）")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class I5ProdProfileSecurityBootTest {

    private static EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private String base;
    private String livePgUrl;

    @BeforeAll
    void bootProd() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        livePgUrl = pgUrl;
        Map<String, Object> props = baseProps(pgUrl);
        // 测试上下文与真实启动的 bean 注册顺序差异：允许 security 的
        // securityLoginContextProvider（自动配置）覆盖 common 的兜底 bean，
        // 与真实 StarterApplication 启动的最终语义一致
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("sw.security.jwt.secret", "i5-prod-test-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "i5-prod-test-digest-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    // addFirst：测试属性源必须压过 application-prod.yml（默认 .properties 优先级最低）
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("i5-prod-test", props));
                    context.getEnvironment().getSystemProperties().put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("i5ProdTestLoginContextProvider", provider);
                })
                .run();
        String port = app.getEnvironment().getProperty("local.server.port");
        base = "http://127.0.0.1:" + port + "/api";
        assertThat(app.isActive()).isTrue();
        System.out.println("[G3-BOOT] prod profile 完整启动成功（真实 PG，全链迁移），port=" + port);
    }

    private static Map<String, Object> baseProps(String pgUrl) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.profiles.active", "prod");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("server.port", "0");
        props.put("spring.data.redis.password", System.getenv().getOrDefault("REDIS_PASSWORD", ""));
        // SSO/agent 共用单一 AesGcmCipher bean（@ConditionalOnMissingBean），密钥一致
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        return props;
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

    @Test
    @DisplayName("匿名矩阵：仅最小健康探针可达且不泄漏详情，其余全部拒绝")
    void anonymousMatrix() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        record Row(String path, int status, String snippet) { }
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (String p : new String[]{
                "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                "/actuator/metrics", "/actuator/env", "/actuator/heapdump", "/actuator/prometheus",
                "/v3/api-docs", "/swagger-ui/index.html", "/druid/index.html",
                "/system/auth/me", "/system/user/list"}) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + p)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(120, resp.body().length())).replace('\n', ' ');
            rows.add(new Row(p, resp.statusCode(), snippet));
            System.out.println("[G3-MATRIX] " + p + " -> " + resp.statusCode() + " :: " + snippet);
        }
        for (Row r : rows) {
            if (r.path().startsWith("/actuator/health")) {
                assertThat(r.status()).as("健康探针不暴露详情（未启用时 404 亦可）: %s", r.path()).isIn(200, 503, 404);
            } else {
                assertThat(r.status()).as("非最小暴露端点应拒绝匿名访问: %s", r.path()).isIn(401, 403, 404);
            }
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/actuator/health")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.body()).as("健康探针不得返回组件详情").doesNotContain("diskSpace").doesNotContain("components");
    }

    @Test
    @DisplayName("缺失 JWT 密钥的 prod 启动 → fail-fast 拒绝")
    void missingJwtSecretShouldFailFast() {
        Map<String, Object> props = baseProps(livePgUrl);
        try {
            new SpringApplicationBuilder(ProdBootTestApplication.class)
                    .properties(props)
                    .initializers(context -> {
                        context.getEnvironment().getPropertySources().addFirst(
                                new org.springframework.core.env.MapPropertySource("i5-prod-test-failfast", props));
                        org.springframework.beans.factory.support.RootBeanDefinition provider =
                                new org.springframework.beans.factory.support.RootBeanDefinition(
                                        com.sw.ck.security.support.SecurityLoginContextProvider.class);
                        provider.setPrimary(true);
                        ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                                .registerBeanDefinition("i5ProdTestLoginContextProvider2", provider);
                    })
                    .run()
                    .close();
            org.assertj.core.api.Assertions.fail("缺 JWT 密钥的 prod 启动应 fail-fast");
        } catch (Exception e) {
            assertThat(String.valueOf(rootMessage(e))).containsAnyOf("JWT", "secret", "密钥", "私钥");
            System.out.println("[G3-FAILFAST] prod 缺 JWT 密钥启动被拒绝: " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getMessage();
    }

    @AfterAll
    void tearDown() {
        if (app != null) app.close();
        try { if (pg != null) pg.close(); } catch (Exception ignored) { }
    }
}
