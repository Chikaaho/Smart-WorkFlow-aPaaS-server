package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.dto.TxnBatchView;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sun.net.httpserver.HttpServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P62 S6 补证（G4）：设备结果写入口边界与并发收敛（真实 HTTP 请求，U04/U05/U07）。
 *
 * <p>覆盖：旧运维回写端点不得收敛 UNKNOWN / 不得覆盖确定终态（409）；
 * manage 而无 iot:command:verify 调人工核实被拒（403）；仅 iot:view 更被拒；
 * 跨租户回执（租户不一致）拒绝留审计；合法回执并发收敛同 UNKNOWN 命令恰一次 APPLIED；
 * 批次查询同租户非发起人且无管理权限不可见。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 G4 设备结果写入口边界（真实 HTTP：旧入口守卫/权限负例/租户负例/并发恰一次）")
class P62ReceiptBoundaryPgTest {

    private static final Long TENANT = 0L;
    private static final String RECEIPT_SECRET = "p62-boundary-hmac-secret-0123456789abcdef";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static int appPort;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-boundary-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-boundary-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "true");
        props.put("sw.security.debug-auth.enabled", "true");
        props.put("sw.iot.receipt.secret", RECEIPT_SECRET);
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .profiles("dev")
                .initializers(context -> {
                    // 测试环境无 Redis：登录缓存以 no-op 替身替换（Configuration 类处理完成后执行，
                    // kickOut/get 均安全无操作，debug 回查每次走正式 UserDetailsProvider）
                    context.addBeanFactoryPostProcessor(beanFactory -> {
                        var registry = (org.springframework.beans.factory.support.DefaultListableBeanFactory) beanFactory;
                        registry.removeBeanDefinition("loginUserCacheService");
                        org.springframework.beans.factory.support.GenericBeanDefinition cacheBean =
                                new org.springframework.beans.factory.support.GenericBeanDefinition();
                        cacheBean.setBeanClass(com.sw.ck.security.cache.LoginUserCacheService.class);
                        cacheBean.setInstanceSupplier(() -> org.mockito.Mockito.mock(
                                com.sw.ck.security.cache.LoginUserCacheService.class));
                        registry.registerBeanDefinition("loginUserCacheService", cacheBean);
                    });
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-boundary", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62BoundaryLoginContextProvider", provider);
                })
                .run();
        appPort = app.getEnvironment().getProperty("local.server.port", Integer.class);
        jdbc = app.getBean(JdbcTemplate.class);
        seed();
        System.out.println("[P62-EV] g4 boot ok pgPort=" + pg.getPort() + " appPort=" + appPort);
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
        LoginUserHolder.clear();
    }

    private void seed() {
        // 角色：9202=仅 IoT 视图；9203=设备管理+批量管理但无核实权限
        jdbc.update("INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version,"
                + " name, code, sort, status, data_scope, built_in, remark)"
                + " VALUES (9202, current_timestamp, current_timestamp, 0, 0, 0,"
                + " '仅视图', 'iot-view-only', 99, 1, 0, false, 'G4 边界测试') ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO sys_role (id, create_time, update_time, deleted, tenant_id, version,"
                + " name, code, sort, status, data_scope, built_in, remark)"
                + " VALUES (9203, current_timestamp, current_timestamp, 0, 0, 0,"
                + " '管理无核实', 'manage-no-verify', 99, 1, 0, false, 'G4 边界测试') ON CONFLICT (id) DO NOTHING");
        // 菜单授权：9202→iot:view(菜单8)；9203→iot:device:manage(332)+form:action:manage(9101)
        jdbc.update("INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id) VALUES (94021, 0, 9202, 8) ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id) VALUES (94031, 0, 9203, 332) ON CONFLICT (id) DO NOTHING");
        jdbc.update("INSERT INTO sys_role_menu (id, tenant_id, role_id, menu_id) VALUES (94032, 0, 9203, 9101) ON CONFLICT (id) DO NOTHING");
        // 用户
        seedUser(92001, "boundary-admin", 2L);
        seedUser(92002, "boundary-viewer", 9202L);
        seedUser(92003, "boundary-manager", 9203L);
        // 设备 + 三条 UNKNOWN 命令（旧入口负例/跨租户负例/并发收敛各一）+ 一条 SUCCESS（覆盖负例）
        jdbc.update("INSERT INTO sw_iot_device (id, tenant_id, device_key, name, product_id, device_name, status)"
                + " VALUES (94011, 0, 'b-device', '边界设备', 'b-product', 'b-ac-01', 'ONLINE') ON CONFLICT (id) DO NOTHING");
        seedCommand(94012, "UNKNOWN", null);
        seedCommand(94013, "UNKNOWN", null);
        seedCommand(94014, "UNKNOWN", null);
        seedCommand(94015, "SUCCESS", null);
        // 批次查询隔离数据：发起人 92001 的批次（service 层直接受理落库）
        asUser(92001, TENANT, List.of("form:action:invoke"), () -> {
            jdbc.update("INSERT INTO sw_bpm_command_batch (id, create_time, update_time, deleted, tenant_id, version,"
                    + " batch_key, action_id, action_version, status, total_count, succeeded_count, failed_count,"
                    + " command_id, initiator_id) VALUES (94021, current_timestamp, current_timestamp, 0, 0, 0,"
                    + " 'boundary-batch-01', 'act-x', 1, 'COMPLETED', 1, 1, 0, null, 92001) ON CONFLICT (id) DO NOTHING");
            jdbc.update("INSERT INTO sw_bpm_command_batch_item (id, create_time, update_time, deleted, tenant_id, version,"
                    + " batch_id, item_key, record_id, quantity, status, attempt_count)"
                    + " VALUES (94022, current_timestamp, current_timestamp, 0, 0, 0,"
                    + " 94021, 'it-1', 'r-1', '1', 'SUCCEEDED', 1) ON CONFLICT (id) DO NOTHING");
            return null;
        });
        System.out.println("[P62-EV] g4 seeded users=3 roles=2 commands=4");
    }

    private void seedUser(long id, String username, long roleId) {
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status)"
                        + " VALUES (?, ?, 'seed-not-a-login-secret', 'G4边界', ?, 0) ON CONFLICT (id) DO NOTHING",
                id, username, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (id) DO NOTHING",
                id, TENANT, id, roleId);
    }

    private void seedCommand(long id, String status, String result) {
        jdbc.update("INSERT INTO sw_iot_device_command (id, create_time, update_time, deleted, tenant_id, version,"
                + " product_id, device_name, device_key, command_type, command_key, semantic_mode, payload,"
                + " status, idempotent_key, expiry_time, retry_count, last_error, tencent_request_id, result)"
                + " VALUES (?, current_timestamp, current_timestamp, 0, 0, 0,"
                + " 'b-product', 'b-ac-01', 'b-device', 'ACTION', 'power_on', 'DEFERRED', '{}',"
                + " ?, ?, current_timestamp + interval '30 minutes', 0, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                id, status, "idem-" + id,
                "UNKNOWN".equals(status) ? "回执超时：传输已发出但未获确定业务回执" : null,
                "req-" + id, result);
    }

    // ==================== G4-1 旧运维回写端点守卫（真实 HTTP） ====================

    @Test
    @DisplayName("旧入口守卫：UNKNOWN 经运维回写拒绝(409)；确定终态拒绝覆盖(409)；中间态原语义可用")
    void legacyReportResultGuardedOverHttp() {
        // UNKNOWN（94012）→ 409，状态不变
        Map<String, Object> r1 = postReport(92001, 94012, "SUCCESS");
        assertThat(r1.get("code")).as("UNKNOWN 拒绝运维回写").isEqualTo(409);
        assertThat(statusOf(94012)).isEqualTo("UNKNOWN");
        // SUCCESS 终态（94015）→ 409 不覆盖
        Map<String, Object> r2 = postReport(92001, 94015, "FAILED");
        assertThat(r2.get("code")).as("确定终态拒绝覆盖").isEqualTo(409);
        assertThat(statusOf(94015)).isEqualTo("SUCCESS");
        // 中间态（SENT）原语义保持：先造 SENT 再回写 SUCCESS 可用
        seedCommand(94016, "SENT", null);
        Map<String, Object> r3 = postReport(92001, 94016, "SUCCESS");
        assertThat(r3.get("code")).as("中间态运维回写原语义保持").isEqualTo(0);
        assertThat(statusOf(94016)).isEqualTo("SUCCESS");
        System.out.println("[P62-EV] g4.legacy-report unknown=409 terminal=409 mid-state=allowed");
    }

    // ==================== G4-2 权限负例（真实 HTTP） ====================

    @Test
    @DisplayName("权限负例：仅 iot:view 与 manage 无 verify 均不能人工收敛（403）")
    void manualVerifyPermissionNegative() {
        // 仅 iot:view（92002）
        Map<String, Object> r1 = postVerify(92002, 94013, "SUCCESS");
        assertThat(r1.get("code")).as("仅监控视角拒绝").isEqualTo(403);
        assertThat(statusOf(94013)).isEqualTo("UNKNOWN");
        // manage 无 verify（92003）
        Map<String, Object> r2 = postVerify(92003, 94013, "SUCCESS");
        assertThat(r2.get("code")).as("管理权限不足以人工核实").isEqualTo(403);
        assertThat(statusOf(94013)).isEqualTo("UNKNOWN");
        // 无依据（合法身份 92001）→ 400
        Map<String, Object> r3 = postVerifyBlankBasis(92001, 94013);
        assertThat(r3.get("code")).as("无依据拒绝").isEqualTo(400);
        assertThat(statusOf(94013)).isEqualTo("UNKNOWN");
        System.out.println("[P62-EV] g4.permission view=403 manage-no-verify=403 blank-basis=400");
    }

    // ==================== G4-3 跨租户回执负例 ====================

    @Test
    @DisplayName("租户负例：他租户签名的合法回执被拒并留审计，命令保持 UNKNOWN")
    void crossTenantReceiptRejected() {
        String sig = hmac(94014, "req-x1", "SUCCESS", "999");
        Map<String, Object> resp = postReceiptRaw(94014, "req-x1", "SUCCESS", "999", sig);
        assertThat(resp.get("code")).as("租户不一致拒绝").isEqualTo(0);
        assertThat(((Map<?, ?>) resp.get("data")).get("verdict")).isEqualTo("REJECTED");
        assertThat(statusOf(94014)).isEqualTo("UNKNOWN");
        Long audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'RECEIPT_REJECTED'"
                        + " AND object_id = '94014' AND detail LIKE '%租户不一致%'", Long.class);
        assertThat(audits).as("跨租户拒绝留审计").isEqualTo(1L);
        System.out.println("[P62-EV] g4.cross-tenant rejected=REJECTED audited=true");
    }

    // ==================== G4-4 并发收敛恰一次 ====================

    @Test
    @DisplayName("并发收敛：两路合法回执并发收敛同 UNKNOWN 命令，恰一次 APPLIED")
    void concurrentReceiptsSingleEffect() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Map<String, Object>> fa = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return postReceiptRaw(94012, "req-c1", "SUCCESS", "0", hmac(94012, "req-c1", "SUCCESS", "0"));
        });
        Future<Map<String, Object>> fb = pool.submit(() -> {
            start.await(10, TimeUnit.SECONDS);
            return postReceiptRaw(94012, "req-c2", "SUCCESS", "0", hmac(94012, "req-c2", "SUCCESS", "0"));
        });
        start.countDown();
        Map<String, Object> ra = fa.get(60, TimeUnit.SECONDS);
        Map<String, Object> rb = fb.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        String verdictA = String.valueOf(((Map<?, ?>) ra.get("data")).get("verdict"));
        String verdictB = String.valueOf(((Map<?, ?>) rb.get("data")).get("verdict"));
        long applied = ("APPLIED".equals(verdictA) ? 1 : 0) + ("APPLIED".equals(verdictB) ? 1 : 0);
        assertThat(applied).as("并发回执恰一次 APPLIED").isEqualTo(1L);
        assertThat(statusOf(94012)).isEqualTo("SUCCESS");
        Long applyAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'RECEIPT_APPLIED'"
                        + " AND object_id = '94012'", Long.class);
        assertThat(applyAudits).as("APPLIED 审计恰一条").isEqualTo(1L);
        System.out.println("[P62-EV] g4.concurrent verdicts=" + verdictA + "/" + verdictB
                + " applied=1 audit=1");
    }

    // ==================== G4-5 批次查询隔离（service 层语义） ====================

    @Test
    @DisplayName("批次查询隔离：同租户非发起人且无管理权限不可见；发起人与管理权限可见")
    void batchQueryIsolation() {
        com.sw.ck.bpm.process.service.TxnBatchService service =
                app.getBean(com.sw.ck.bpm.process.service.TxnBatchService.class);
        // 发起人（92001）可见
        TxnBatchView owner = asUser(92001, TENANT, List.of("form:action:invoke"), () ->
                service.get("boundary-batch-01"));
        assertThat(owner.getBatchKey()).isEqualTo("boundary-batch-01");
        // 同租户其他用户（仅 invoke，非发起人、无 manage）不可见
        asUser(92002, TENANT, List.of("form:action:invoke", "iot:view"), () -> {
            try {
                service.get("boundary-batch-01");
                throw new AssertionError("应不可见");
            } catch (com.sw.ck.common.exception.BaseException e) {
                assertThat(e.getCode()).as("非发起人无管理权限不可见").isEqualTo(2424);
            }
            return null;
        });
        // 管理权限（92003 持 form:action:manage）可见
        TxnBatchView manager = asUser(92003, TENANT, List.of("form:action:manage"), () ->
                service.get("boundary-batch-01"));
        assertThat(manager.getBatchKey()).isEqualTo("boundary-batch-01");
        System.out.println("[P62-EV] g4.batch-query owner=visible peer=2424 manager=visible");
    }

    // ==================== HTTP 辅助 ====================

    private Map<String, Object> postReceiptRaw(long commandId, String requestId, String outcome,
                                               String tenantKey, String signature) {
        String body = cn.hutool.http.HttpRequest.post(
                        "http://127.0.0.1:" + appPort + "/api/iot/commands/receipt")
                .header("X-Sw-Receipt-Signature", signature)
                .body("{\"commandId\":" + commandId + ",\"requestId\":\"" + requestId
                        + "\",\"outcome\":\"" + outcome + "\",\"output\":\"ok\",\"tenantKey\":\""
                        + tenantKey + "\"}")
                .timeout(5000)
                .execute()
                .body();
        return cn.hutool.json.JSONUtil.parseObj(body);
    }

    private String hmac(long commandId, String requestId, String outcome, String tenantKey) {
        String canonical = commandId + "|" + requestId + "|" + outcome + "|ok|" + tenantKey;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    RECEIPT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return String.format("%064x", new BigInteger(1,
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> postReport(long userId, long commandId, String status) {
        return cn.hutool.json.JSONUtil.parseObj(cn.hutool.http.HttpRequest.post(
                        "http://127.0.0.1:" + appPort + "/api/iot/devices/commands/" + commandId + "/result")
                .header("Authorization", "Bearer test_" + userId)
                .body("{\"status\":\"" + status + "\",\"result\":\"boundary-probe\"}")
                .timeout(5000)
                .execute()
                .body());
    }

    private Map<String, Object> postVerify(long userId, long commandId, String outcome) {
        return cn.hutool.json.JSONUtil.parseObj(cn.hutool.http.HttpRequest.post(
                        "http://127.0.0.1:" + appPort + "/api/iot/commands/" + commandId + "/manual-verify")
                .header("Authorization", "Bearer test_" + userId)
                .body("{\"outcome\":\"" + outcome + "\",\"basis\":\"边界探测依据\"}")
                .timeout(5000)
                .execute()
                .body());
    }

    private Map<String, Object> postVerifyBlankBasis(long userId, long commandId) {
        return cn.hutool.json.JSONUtil.parseObj(cn.hutool.http.HttpRequest.post(
                        "http://127.0.0.1:" + appPort + "/api/iot/commands/" + commandId + "/manual-verify")
                .header("Authorization", "Bearer test_" + userId)
                .body("{\"outcome\":\"SUCCESS\",\"basis\":\"   \"}")
                .timeout(5000)
                .execute()
                .body());
    }

    private String statusOf(long commandId) {
        return jdbc.queryForObject(
                "SELECT status FROM sw_iot_device_command WHERE id = ?", String.class, commandId);
    }

    private static <T> T asUser(long userId, long tenantId, List<String> permissions, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(tenantId);
        user.setPermissions(new ArrayList<>(permissions));
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
