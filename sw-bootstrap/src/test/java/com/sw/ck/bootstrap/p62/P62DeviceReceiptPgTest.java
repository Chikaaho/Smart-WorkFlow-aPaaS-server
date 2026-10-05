package com.sw.ck.bootstrap.p62;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.api.DeviceCommandSummary;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.job.CommandCompensationJob;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.DeviceReceiptService;
import com.sw.ck.iot.service.impl.DeviceReceiptServiceImpl;
import com.sw.ck.iot.util.DeferredControlUtil;
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
import org.springframework.web.context.WebApplicationContext;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P62 分级执行 S4：设备未知结果与受控回执真实 PostgreSQL 端到端证据（U04/U07）。
 * <p>
 * 受控真实传输对端：命令发送经真实 HTTP loopback（hutool HttpRequest → JDK HttpServer），
 * 回执由对端经真实 HTTP 携 HMAC 签名调用应用回调端点。覆盖：回执超时转 UNKNOWN 且禁止
 * 自动重发（对端仅收到一次）；迟到合法回执收敛 UNKNOWN；重复回执幂等；冲突回执留审计
 * 不覆盖已确定结果；坏签名拒绝；独立授权人工核实（无权限拒绝/无依据拒绝/带依据收敛含审计）；
 * BPM 审批关联命令按流程实例回查。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P62 S4 设备未知结果 PG 端到端（回执超时→UNKNOWN/守卫收敛/人工核实/关联回查）")
class P62DeviceReceiptPgTest {

    private static final Long TENANT = 0L;
    private static final Long USER = 91003L;
    private static final String PRODUCT_ID = "p62-product";
    private static final String DEVICE_NAME = "p62-device-01";
    private static final String RECEIPT_SECRET = "p62-receipt-hmac-secret-0123456789abcdef";

    private static EmbeddedPostgres pg;
    private static ConfigurableApplicationContext app;
    private static JdbcTemplate jdbc;
    private static HttpServer peerServer;
    private static int appPort;
    private static int peerPort;
    /** 对端实际收到的下发请求数（证明"禁止自动重发"）。 */
    private static final AtomicInteger PEER_REQUEST_COUNT = new AtomicInteger();

    private IotDeviceFacade iotDeviceFacade;
    private DeferredControlUtil deferredControlUtil;
    private CommandCompensationJob compensationJob;
    private DeviceReceiptService deviceReceiptService;

    @BeforeAll
    void boot() throws Exception {
        // 受控真实传输对端：记录每次下发，返回成功 + 稳定 RequestId（回执由测试按场景发出）
        peerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        peerServer.createContext("/device", exchange -> {
            PEER_REQUEST_COUNT.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            String response = "{\"success\":true,\"requestId\":\"req-"
                    + PEER_REQUEST_COUNT.get() + "\",\"echo\":" + new String(body, StandardCharsets.UTF_8) + "}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        peerServer.start();
        peerPort = peerServer.getAddress().getPort();

        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p62-rcpt-e2e-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p62-rcpt-e2e-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        // S4 受控回执通道与 IoT 发送路径
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", RECEIPT_SECRET);
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p62-rcpt-e2e", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p62RcptE2eLoginContextProvider", provider);
                    // 受控真实传输对端 Provider：经真实 HTTP loopback 下发（腾讯 provider-mode 未开启）
                    org.springframework.beans.factory.support.RootBeanDefinition controlProvider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    LoopbackDeviceControlProvider.class);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory)
                            context.getBeanFactory()).registerBeanDefinition(
                            "p62LoopbackDeviceControlProvider", controlProvider);
                })
                .run();
        appPort = ((WebApplicationContext) app).getServletContext() == null
                ? app.getEnvironment().getProperty("local.server.port", Integer.class)
                : app.getEnvironment().getProperty("local.server.port", Integer.class);
        jdbc = app.getBean(JdbcTemplate.class);
        iotDeviceFacade = app.getBean(IotDeviceFacade.class);
        deferredControlUtil = app.getBean(DeferredControlUtil.class);
        compensationJob = app.getBean(CommandCompensationJob.class);
        deviceReceiptService = app.getBean(DeviceReceiptService.class);

        // 发起人 + 管理员角色（role 2 经 R 迁移获得 iot:command:verify 最小核实权限）
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'rcpt-e2e-operator', 'seed-not-a-login-secret', '回执操作员', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", USER, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91003, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, USER);
        // 设备种子
        jdbc.update("INSERT INTO sw_iot_device (id, tenant_id, device_key, name, product_id, device_name, status) "
                + "VALUES (92001, ?, 'p62-device-key', 'P62回执测试设备', ?, ?, 'ONLINE') ON CONFLICT (id) DO NOTHING",
                TENANT, PRODUCT_ID, DEVICE_NAME);
        System.out.println("[P62-EV] s4.e2e boot ok pgPort=" + pg.getPort()
                + " appPort=" + appPort + " peerPort=" + peerPort);
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        if (peerServer != null) {
            peerServer.stop(0);
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

    @Test
    @DisplayName("端到端：超时转 UNKNOWN 不重发→迟到回执收敛→重复幂等→冲突不覆盖→坏签名拒绝→人工核实→关联回查")
    void receiptLifecycleEndToEnd() throws Exception {
        // —— 审批联动下发（关联流程实例）+ 真实发送（HTTP loopback 对端）——
        String processInstanceId = "rcpt-inst-01";
        Optional<Long> commandIdOpt = asOperator(() -> iotDeviceFacade.dispatchCommandIdempotent(
                PRODUCT_ID, DEVICE_NAME, "power_on", "ACTION", "{\"switch\":1}",
                processInstanceId, "P62-S4-RCPT-01"));
        assertThat(commandIdOpt).as("命令已受理入队").isPresent();
        Long commandId = commandIdOpt.orElseThrow();
        asOperator(() -> {
            IotDeviceCommand queued = app.getBean(com.sw.ck.iot.mapper.IotDeviceCommandMapper.class)
                    .selectById(commandId);
            deferredControlUtil.sendCommand(queued);
            return null;
        });
        assertThat(statusOf(commandId)).as("真实传输已发出").isEqualTo("SENT");
        assertThat(PEER_REQUEST_COUNT.get()).as("对端恰好收到一次下发").isEqualTo(1);

        // —— 回执超时 → UNKNOWN（禁止自动重发）——
        jdbc.update("UPDATE sw_iot_device_command SET expiry_time = CURRENT_TIMESTAMP - INTERVAL '1 minute'"
                + " WHERE id = ?", commandId);
        compensationJob.execute();
        assertThat(statusOf(commandId)).as("回执超时转待核实").isEqualTo("UNKNOWN");
        assertThat(PEER_REQUEST_COUNT.get()).as("UNKNOWN 不自动重发").isEqualTo(1);
        Long bpmLinked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_device_command WHERE approval_biz_id = ? AND status = 'UNKNOWN'",
                Long.class, processInstanceId);
        assertThat(bpmLinked).as("BPM 关联命令 UNKNOWN 可回查").isEqualTo(1L);

        // —— 坏签名拒绝（不得改状态）——
        assertThat(postReceipt(commandId, "req-1", "SUCCESS", "signature-invalid").getInt("code", -1))
                .as("坏签名拒绝 401").isEqualTo(401);
        assertThat(statusOf(commandId)).isEqualTo("UNKNOWN");

        // —— 迟到合法回执收敛 UNKNOWN ——
        cn.hutool.json.JSONObject lateApplied = postReceipt(commandId, "req-1", "SUCCESS",
                hmac(commandId, "req-1", "SUCCESS"));
        assertThat(lateApplied.getInt("code", -1)).as("迟到合法回执可收敛").isZero();
        assertThat(lateApplied.getJSONObject("data").getStr("verdict")).isEqualTo("APPLIED");
        assertThat(statusOf(commandId)).isEqualTo("SUCCESS");
        String result = resultOf(commandId);
        assertThat(result).contains("\"source\":\"RECEIPT\"").contains("req-1");

        // —— 重复回执幂等 ——
        cn.hutool.json.JSONObject duplicate = postReceipt(commandId, "req-1", "SUCCESS",
                hmac(commandId, "req-1", "SUCCESS"));
        assertThat(duplicate.getInt("code", -1)).isZero();
        assertThat(duplicate.getJSONObject("data").getStr("verdict")).isEqualTo("DUPLICATE");
        Long receiptAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'RECEIPT_APPLIED'"
                        + " AND object_id = ?", Long.class, String.valueOf(commandId));
        assertThat(receiptAudits).as("APPLIED 审计恰一条（重复不重复收敛）").isEqualTo(1L);

        // —— 冲突回执不覆盖已确定结果 ——
        cn.hutool.json.JSONObject conflict = postReceipt(commandId, "req-1", "FAILED",
                hmac(commandId, "req-1", "FAILED"));
        assertThat(conflict.getInt("code", -1)).isZero();
        assertThat(conflict.getJSONObject("data").getStr("verdict")).isEqualTo("CONFLICT");
        assertThat(statusOf(commandId)).as("已确定结果不被覆盖").isEqualTo("SUCCESS");
        Long conflictAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'RECEIPT_CONFLICT'"
                        + " AND object_id = ?", Long.class, String.valueOf(commandId));
        assertThat(conflictAudits).as("冲突留审计").isEqualTo(1L);

        // —— BPM 关联回查（U07）：按流程实例回查命令摘要 ——
        var summaries = asOperator(() -> iotDeviceFacade.findByApprovalBizId(TENANT, processInstanceId))
                .orElse(java.util.List.of());
        assertThat(summaries).hasSize(1);
        DeviceCommandSummary summary = summaries.get(0);
        assertThat(summary.commandId()).isEqualTo(commandId);
        assertThat(summary.status()).isEqualTo("SUCCESS");

        // —— 第二条命令：UNKNOWN + 独立授权人工核实 ——
        Optional<Long> verifyIdOpt = asOperator(() -> iotDeviceFacade.dispatchCommandIdempotent(
                PRODUCT_ID, DEVICE_NAME, "set_brightness", "ACTION", "{\"brightness\":5}",
                "rcpt-inst-02", "P62-S4-RCPT-02"));
        Long verifyId = verifyIdOpt.orElseThrow();
        asOperator(() -> {
            IotDeviceCommand queued = app.getBean(com.sw.ck.iot.mapper.IotDeviceCommandMapper.class)
                    .selectById(verifyId);
            deferredControlUtil.sendCommand(queued);
            return null;
        });
        jdbc.update("UPDATE sw_iot_device_command SET expiry_time = CURRENT_TIMESTAMP - INTERVAL '1 minute'"
                + " WHERE id = ?", verifyId);
        compensationJob.execute();
        assertThat(statusOf(verifyId)).isEqualTo("UNKNOWN");

        // 无权限用户拒绝（仅 iot:view 的普通视角不可改结果）
        asUserWithPermissions(java.util.List.of("iot:view"), () -> {
            assertThatThrownBy(() -> deviceReceiptService.verifyManually(verifyId,
                    new com.sw.ck.iot.model.ManualVerifyRequest("SUCCESS", "工单 GD-001")))
                    .isInstanceOfSatisfying(BaseException.class, e ->
                            assertThat(e.getCode()).isEqualTo(403));
            return null;
        });
        // 有权限但无依据拒绝（不带可信依据不得宣告成功）
        asOperator(() -> {
            assertThatThrownBy(() -> deviceReceiptService.verifyManually(verifyId,
                    new com.sw.ck.iot.model.ManualVerifyRequest("SUCCESS", "  ")))
                    .isInstanceOf(BaseException.class);
            return null;
        });
        // 有权限 + 可信依据 → 收敛并记录依据/操作者/前后状态
        asOperator(() -> deviceReceiptService.verifyManually(verifyId,
                new com.sw.ck.iot.model.ManualVerifyRequest("SUCCESS", "现场复核工单 GD-001，设备已开启")));
        assertThat(statusOf(verifyId)).isEqualTo("SUCCESS");
        String verifyResult = resultOf(verifyId);
        assertThat(verifyResult).contains("MANUAL_VERIFY").contains("现场复核工单 GD-001");
        Long verifyAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'COMMAND_MANUAL_VERIFY'"
                        + " AND object_id = ? AND detail LIKE '%before=UNKNOWN%after=SUCCESS%'"
                        + " AND detail LIKE '%现场复核工单 GD-001%'", Long.class, String.valueOf(verifyId));
        assertThat(verifyAudits).as("人工核实审计含依据与前后状态").isEqualTo(1L);

        System.out.println("[P62-EV] s4.e2e lifecycle sent=1 no-resend=true unknown=true"
                + " late-receipt=APPLIED duplicate=idempotent conflict=audited bad-signature=401"
                + " manual-verify=APPLIED basis-audited=true bpm-linked-lookup=1");
    }

    // ==================== 受控真实传输对端 Provider（真实 HTTP loopback） ====================

    public static class LoopbackDeviceControlProvider implements DeviceControlProvider {
        @Override
        public String queryDeviceStatus(String productId, String deviceName) {
            return "online";
        }

        @Override
        public DeviceControlResult controlDeviceData(String productId, String deviceName, String propertyJson) {
            return send(productId, deviceName, propertyJson);
        }

        @Override
        public DeviceControlResult callDeviceActionSync(String productId, String deviceName,
                                                        String actionId, String inputJson) {
            return send(productId, deviceName, inputJson);
        }

        private DeviceControlResult send(String productId, String deviceName, String payload) {
            // 真实 HTTP 传输到受控对端（hutool HttpRequest；工程宪法指定业务 HTTP 工具）
            String body = cn.hutool.http.HttpRequest.post(
                            "http://127.0.0.1:" + peerPort + "/device")
                    .body("{\"productId\":\"" + productId + "\",\"deviceName\":\"" + deviceName
                            + "\",\"payload\":" + (payload == null ? "null" : payload) + "}")
                    .timeout(5000)
                    .execute()
                    .body();
            cn.hutool.json.JSONObject json = cn.hutool.json.JSONUtil.parseObj(body);
            return DeviceControlResult.success(json.getStr("requestId"), null);
        }
    }

    // ==================== 回执 HTTP 与签名 ====================

    /** 经真实 HTTP 携签名调用应用回执端点，返回统一响应包裹（code=0 成功，裁决在 data.verdict）。 */
    private static cn.hutool.json.JSONObject postReceipt(Long commandId, String requestId,
                                                         String outcome, String signature) {
        String body = cn.hutool.http.HttpRequest.post(
                        "http://127.0.0.1:" + appPort + "/api/iot/commands/receipt")
                .header("X-Sw-Receipt-Signature", signature)
                .body("{\"commandId\":" + commandId + ",\"requestId\":\"" + requestId
                        + "\",\"outcome\":\"" + outcome + "\",\"output\":\"ok\",\"tenantKey\":\""
                        + TENANT + "\"}")
                .timeout(5000)
                .execute()
                .body();
        return cn.hutool.json.JSONUtil.parseObj(body);
    }

    private static String hmac(Long commandId, String requestId, String outcome) {
        String canonical = commandId + "|" + requestId + "|" + outcome + "|ok|" + TENANT;
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

    // ==================== 查询与身份辅助 ====================

    private String statusOf(Long commandId) {
        return jdbc.queryForObject(
                "SELECT status FROM sw_iot_device_command WHERE id = ?", String.class, commandId);
    }

    private String resultOf(Long commandId) {
        return jdbc.queryForObject(
                "SELECT result FROM sw_iot_device_command WHERE id = ?", String.class, commandId);
    }

    private static <T> T asOperator(Callable<T> action) {
        return asUserWithPermissions(
                java.util.List.of("form:action:invoke", "iot:command:verify", "iot:device:manage"),
                action);
    }

    private static <T> T asUserWithPermissions(java.util.List<String> permissions, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(USER);
        user.setTenantId(TENANT);
        user.setPermissions(new java.util.ArrayList<>(permissions));
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
