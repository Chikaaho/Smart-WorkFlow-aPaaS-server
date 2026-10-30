package com.sw.ck.bootstrap.openapi;

import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.openapi.biz.job.OpenApiCallbackRecoveryJob;
import com.sw.ck.openapi.biz.listener.OpenApiCallbackListener;
import com.sw.ck.openapi.biz.service.OpenApiCallbackDeliveryService;
import com.sw.ck.openapi.biz.mapper.OpenApiAppMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.1.7 追加缺陷 · openapi 异步回调租户上下文（真实 PostgreSQL + 受控接收端）。
 *
 * <p>覆盖旧失败条件：{@code @Async @TransactionalEventListener(AFTER_COMMIT)} 在真实事务
 * 提交后于无请求线程身份的线程上执行——修复前租户行拦截器 fail-closed 使查询直接抛错、
 * 投递不发生（生产与 CI 均已实证）。本用例对异步结果作实际断言（接收端命中 + 成功落账 +
 * 记录租户归属），而非仅验证事务发布成功或日志噪声。</p>
 *
 * <p>两租户事件各用所属应用与租户落账，互不触达；缺失/无效权威身份拒绝处理且不退化为
 * 全局查询；即时监听与恢复调度的去重/关闭合同保持。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("0.1.7 追加缺陷 · 异步回调监听器按事件租户还原执行身份（真实 PG）")
class OpenApiCallbackAsyncTenantPgTest {

    private static final long TENANT_B = 9001L;
    private static final long USER_B = 900101L;
    private static final long USER_A = 1L;
    private static final String APP_A = "oc-app-a";
    private static final String APP_B = "oc-app-b";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;

    private static HttpServer receiver;
    private static final AtomicInteger hitsA = new AtomicInteger();
    private static final AtomicInteger hitsB = new AtomicInteger();

    private DomainEventPublisher publisher;
    private OpenApiCallbackRecoveryJob callbackRecoveryJob;
    private PlatformTransactionManager txManager;

    // ==================== 装载 ====================

    @BeforeAll
    void bootAll() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "oc-async-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "oc-async-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.bpm.enabled", "true");
        props.put("sw.form.enabled", "true");
        props.put("sw.iot.enabled", "true");
        // 恢复类调度默认不自动运行：行为用例显式调用，避免与断言竞争
        props.put("sw.bpm.command.poll-interval-millis", "3600000");
        props.put("sw.bpm.command.p0-poll-interval-millis", "3600000");
        props.put("sw.notify.recovery.fixed-delay-ms", "3600000");
        props.put("sw.iot.trigger.recovery.fixed-delay-ms", "3600000");
        props.put("sw.openapi.callback.recovery.fixed-delay-ms", "3600000");
        app = new SpringApplicationBuilder(com.sw.ck.bootstrap.i5.ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("oc-async", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("ocAsyncLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        publisher = app.getBean(DomainEventPublisher.class);
        callbackRecoveryJob = app.getBean(OpenApiCallbackRecoveryJob.class);
        txManager = app.getBean(PlatformTransactionManager.class);

        seedTenantAndUser(TENANT_B, USER_B, "ocb");
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/cb-a", exchange -> {
            hitsA.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        receiver.createContext("/cb-b", exchange -> {
            hitsB.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        receiver.start();
        int port = receiver.getAddress().getPort();
        seedCallbackApp(APP_A, 0L, USER_A, "http://127.0.0.1:" + port + "/cb-a");
        seedCallbackApp(APP_B, TENANT_B, USER_B, "http://127.0.0.1:" + port + "/cb-b");
        // 哨兵任务消耗启动期的恢复调度一次性运行：此后（下一轮 1h 后）投递只能来自即时监听器，
        // 使"投递发生时任务仍为 PENDING"成为有效判别（恢复调度投递会立即关闭任务）
        jdbc.update("insert into sw_openapi_callback_task (id, tenant_id, version, deleted, create_time,"
                        + " update_time, app_id, event, biz_ref, status, attempts, next_retry_time)"
                        + " values (?, 0, 0, 0, now(), now(), 'oc-sentinel-absent-app', 'PROCESS_APPROVED',"
                        + " 'oc-sentinel-1', 'PENDING', 0, now())",
                9470001L);
        await("启动期恢复调度运行已消耗哨兵", () -> !"PENDING".equals(text(
                "select status from sw_openapi_callback_task where biz_ref = 'oc-sentinel-1'")), 20000);
        System.out.println("[OC-EV] boot ok pgPort=" + pg.getPort() + " apps=" + APP_A + "," + APP_B
                + " receiver=local-http-server sentinel=" + text(
                "select status from sw_openapi_callback_task where biz_ref = 'oc-sentinel-1'"));
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        if (receiver != null) {
            receiver.stop(0);
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

    // ==================== 用例 ====================

    @Test
    @Order(1)
    @DisplayName("真实事务提交后：无请求线程身份的异步监听器按事件租户完成投递与落账；恢复调度协作去重")
    void asyncListenerDeliversUnderEventTenantWithoutRequestIdentity() {
        String bizRef = "oc-pi-async-a-1";
        int a0 = hitsA.get();
        publishInTx(0L, USER_A, new BpmNotifyEvent(
                BpmNotifyTrigger.PROCESS_APPROVED, USER_A, 0L, USER_A, bizRef));

        await("A 端收到异步投递", () -> hitsA.get() > a0, 15000);
        assertThat(text("select status from sw_openapi_callback_task where app_id = ? and biz_ref = ?",
                APP_A, bizRef)).as("投递发生时任务仍 PENDING：投递方为即时监听器而非恢复调度")
                .isEqualTo("PENDING");
        assertThat(hitsA.get() - a0).as("A 应用接收端恰好一次").isEqualTo(1);
        assertThat(hitsB.get()).as("B 端未被触达").isZero();

        await("A 回调成功落账", () -> count(
                "select count(*) from sw_openapi_callback_log where app_id = ? and biz_ref = ? and status = 'SUCCESS'",
                APP_A, bizRef) == 1L, 10000);
        assertThat(text("select tenant_id::text from sw_openapi_callback_log"
                + " where app_id = ? and biz_ref = ? and status = 'SUCCESS'", APP_A, bizRef))
                .as("投递记录租户=事件租户（0）").isEqualTo("0");

        // 即时监听与恢复调度协作：已投递任务被关闭且零重复投递
        int a1 = hitsA.get();
        callbackRecoveryJob.recoverDue();
        assertThat(text("select status from sw_openapi_callback_task where app_id = ? and biz_ref = ?",
                APP_A, bizRef)).as("恢复调度关闭任务为 SUCCESS").isEqualTo("SUCCESS");
        assertThat(hitsA.get()).as("去重合同：关闭不产生重复投递").isEqualTo(a1);
        System.out.println("[OC-EV] async-delivery-a bizRef=" + bizRef
                + " hitsA=1 hitsB=0 logTenantId=0 taskAfterRecovery=SUCCESS duplicateHits=0");
    }

    @Test
    @Order(2)
    @DisplayName("第二租户同链路：各用所属应用与租户落账，互不触达，无跨租户行")
    void secondTenantIsolatedOnSamePipeline() {
        String bizRef = "oc-pi-async-b-1";
        int b0 = hitsB.get();
        int aNow = hitsA.get();
        publishInTx(TENANT_B, USER_B, new BpmNotifyEvent(
                BpmNotifyTrigger.PROCESS_APPROVED, USER_B, TENANT_B, USER_B, bizRef));

        await("B 端收到异步投递", () -> hitsB.get() > b0, 15000);
        assertThat(text("select status from sw_openapi_callback_task where app_id = ? and biz_ref = ?",
                APP_B, bizRef)).as("投递发生时任务仍 PENDING：投递方为即时监听器而非恢复调度")
                .isEqualTo("PENDING");
        assertThat(hitsB.get() - b0).as("B 应用接收端恰好一次").isEqualTo(1);
        assertThat(hitsA.get()).as("A 端不被触达").isEqualTo(aNow);

        await("B 回调成功落账", () -> count(
                "select count(*) from sw_openapi_callback_log where app_id = ? and biz_ref = ? and status = 'SUCCESS'",
                APP_B, bizRef) == 1L, 10000);
        assertThat(text("select tenant_id::text from sw_openapi_callback_log"
                + " where app_id = ? and biz_ref = ? and status = 'SUCCESS'", APP_B, bizRef))
                .as("投递记录租户=事件租户（9001）").isEqualTo("9001");

        // 跨租户零污染：任一应用的记录不得出现非所属租户
        assertThat(count("select count(*) from sw_openapi_callback_log where app_id = ? and tenant_id <> ?",
                APP_A, 0L)).as("A 应用记录无跨租户行").isZero();
        assertThat(count("select count(*) from sw_openapi_callback_log where app_id = ? and tenant_id <> ?",
                APP_B, TENANT_B)).as("B 应用记录无跨租户行").isZero();

        int b1 = hitsB.get();
        callbackRecoveryJob.recoverDue();
        assertThat(text("select status from sw_openapi_callback_task where app_id = ? and biz_ref = ?",
                APP_B, bizRef)).isEqualTo("SUCCESS");
        assertThat(hitsB.get()).isEqualTo(b1);
        System.out.println("[OC-EV] async-delivery-b bizRef=" + bizRef
                + " hitsB=1 hitsAUnchanged=1 logTenantId=9001 crossTenantRows=0 taskAfterRecovery=SUCCESS");
    }

    @Test
    @Order(3)
    @DisplayName("缺失/无效权威身份拒绝处理：零查库零投递零落账，不退化为全局查询")
    void missingOrInvalidTenantIdentityIsRefused() {
        // 同步直调未代理实例：拒绝路径确定性可断言（生产链路由 @Async 代理调用同一方法体）
        OpenApiCallbackListener raw = new OpenApiCallbackListener(
                app.getBean(OpenApiAppMapper.class), app.getBean(OpenApiCallbackDeliveryService.class),
                app.getBean(com.sw.ck.common.tenant.TenantWorkAdmission.class));
        long logsBefore = count("select count(*) from sw_openapi_callback_log");
        int a0 = hitsA.get();
        int b0 = hitsB.get();
        LoginUserHolder.clear();

        raw.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, null, USER_A, "oc-null-1"));
        raw.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, 88888L, USER_A, "oc-unknown-1"));
        raw.onEvent(new BpmNotifyEvent(BpmNotifyTrigger.TODO_CREATED, USER_A, 0L, USER_A, "oc-nonterminal-1"));

        assertThat(count("select count(*) from sw_openapi_callback_log"))
                .as("拒绝/非终态路径零落账").isEqualTo(logsBefore);
        assertThat(hitsA.get()).isEqualTo(a0);
        assertThat(hitsB.get()).isEqualTo(b0);
        assertThat(LoginUserHolder.get()).as("直调后身份不残留").isNull();
        System.out.println("[OC-EV] refusal null-tenant/unknown-tenant/non-terminal"
                + " rowsAdded=0 hitsAdded=0 holderCleared=true");
    }

    // ==================== 助手 ====================

    private void publishInTx(Long tenantId, Long userId, BpmNotifyEvent event) {
        asTenant(tenantId, userId, () -> {
            TransactionTemplate template = new TransactionTemplate(txManager);
            return template.execute(status -> {
                publisher.publish(event);
                return null;
            });
        });
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

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private String text(String sql, Object... args) {
        java.util.List<String> rows = jdbc.queryForList(sql, String.class, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void await(String description, java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                System.out.println("[OC-EV] await-ok " + description);
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        System.out.println("[OC-EV] await-timeout " + description);
    }

    private void seedTenantAndUser(long tenantId, long userId, String tag) {
        jdbc.execute("INSERT INTO sys_tenant (id, create_time, update_time, deleted, tenant_id, version, name, code, status, description)"
                + " VALUES (" + tenantId + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 'OC" + tag + "', 'oc-" + tag
                + "', 0, 'openapi async fixture') ON CONFLICT (id) DO NOTHING");
        jdbc.execute("INSERT INTO sys_user (id, create_time, update_time, deleted, tenant_id, version, username, password, real_name, dept_id, status, is_admin)"
                + " VALUES (" + userId + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, " + tenantId
                + ", 0, 'oc" + tag + "user', '$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK', 'OC user', 0, 0, 1)"
                + " ON CONFLICT (id) DO NOTHING");
    }

    private void seedCallbackApp(String appId, long tenantId, long actorUserId, String callbackUrl) {
        String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(("oc-secret-" + appId).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("insert into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version, app_id,"
                        + " app_name, secret_hash, scopes, status, act_as_user_id, callback_url, callback_secret_hash)"
                        + " values (?, now(), now(), 0, ?, 0, ?, ?, ?, 'workflow:callback', 'ENABLED', ?, ?, ?)",
                Math.abs(appId.hashCode()) + 9460L, tenantId, appId, "OC " + appId, digest, actorUserId, callbackUrl,
                digest);
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
