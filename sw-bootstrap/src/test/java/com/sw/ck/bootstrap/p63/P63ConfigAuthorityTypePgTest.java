package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.api.IotCommandReservationFacade;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P63 G03a 补证（Embedded PG + 全量应用上下文）：同租普通调用者的配置/来源授权边界
 * 与已发布功能的类型权威边界，均在<b>冻结（意图落库）前</b> fail closed：
 * <ul>
 *   <li>设备流程接入授权（process_access_enabled=0，其余与合法设备完全一致）→
 *       createIntent 拒绝、零意图零外发；同租合法对照（=1）正常冻结 PENDING。</li>
 *   <li>已发布物模型 + 未知 commandType（400）与「键已声明但类型不匹配」
 *       （ACTION 键按 PROPERTY 冻结 / PROPERTY 键按 ACTION 冻结，404）→ 拒绝、零意图零外发。</li>
 * </ul>
 * 全部同步直调真实门面（MANDATORY 事务由测试事务模板提供），无轮询等待。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G03a 配置授权/功能类型权威冻结前拒绝 PG 端到端")
class P63ConfigAuthorityTypePgTest {

    private static final Long TENANT = 0L;
    private static final Long OPERATOR = 91651L;
    private static final Long PRODUCT_ID = 91680L;
    private static final Long MODEL_ID = 91681L;
    private static final Long ACCESS_DEVICE_ID = 91682L;
    private static final String ACCESS_DEVICE_KEY = "p63-ca-device";
    private static final String ACCESS_DEVICE_NAME = "P63接入授权设备";
    private static final Long NO_ACCESS_DEVICE_ID = 91683L;
    private static final String NO_ACCESS_DEVICE_KEY = "p63-ca-noaccess-device";
    private static final String NO_ACCESS_DEVICE_NAME = "P63未接入设备";

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private IotCommandReservationFacade reservationFacade;
    private TransactionTemplate tx;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-ca-type-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-ca-type-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", "p63-ca-type-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-ca-type", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63CaTypeLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        reservationFacade = app.getBean(IotCommandReservationFacade.class);
        tx = new TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-ca-operator', 'seed-not-a-login-secret', '同租普通配置者', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", OPERATOR, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91651, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, OPERATOR);
        // 权威链：产品 + 已发布物模型（properties: power_off；actions: factory_reset）
        jdbc.update("INSERT INTO sw_iot_product (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, model_status, published_model_id) "
                + "VALUES (?, now(), now(), 0, ?, 0, 'P63-CA-PROD', 'P63配置授权产品', 'MQTT', 'PUBLISHED', ?) "
                + "ON CONFLICT (id) DO NOTHING", PRODUCT_ID, TENANT, MODEL_ID);
        jdbc.update("INSERT INTO sw_iot_thing_model (id, create_time, update_time, deleted, tenant_id, version, "
                + "product_id, model_version, status, content_json, publish_time) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 1, 'PUBLISHED', "
                + "'{\"properties\":[{\"id\":\"power_off\"}],\"events\":[],"
                + "\"actions\":[{\"id\":\"factory_reset\"}]}', now()) "
                + "ON CONFLICT (id) DO NOTHING", MODEL_ID, TENANT, PRODUCT_ID);
        // 两台设备唯一差异 = process_access_enabled（1=合法候选人 / 0=无流程接入配置授权）
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, "
                + "product_ref_id) VALUES (?, now(), now(), 0, ?, 0, ?, ?, 'ONLINE', 'P63-CA-PROD', ?, "
                + "'PUBLISHED', 1, ?) ON CONFLICT (id) DO NOTHING", ACCESS_DEVICE_ID, TENANT,
                ACCESS_DEVICE_KEY, ACCESS_DEVICE_NAME, ACCESS_DEVICE_NAME, PRODUCT_ID);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, "
                + "product_ref_id) VALUES (?, now(), now(), 0, ?, 0, ?, ?, 'ONLINE', 'P63-CA-PROD', ?, "
                + "'PUBLISHED', 0, ?) ON CONFLICT (id) DO NOTHING", NO_ACCESS_DEVICE_ID, TENANT,
                NO_ACCESS_DEVICE_KEY, NO_ACCESS_DEVICE_NAME, NO_ACCESS_DEVICE_NAME, PRODUCT_ID);
        System.out.println("[P63-EV] ca-type boot ok pgPort=" + pg.getPort());
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

    @Test
    @DisplayName("同租设备无流程接入配置授权（=0）→ 冻结前拒绝、零意图零外发；合法对照（=1）正常冻结")
    void freezeRejectsDeviceWithoutProcessAccessAuthority() {
        String rejectedPi = "p63-ca-noaccess-pi";
        expectFreezeReject(rejectedPi, NO_ACCESS_DEVICE_KEY, NO_ACCESS_DEVICE_NAME,
                "power_off", "PROPERTY", "{\"power_off\":true}", "设备目标已失效");
        System.out.println("[P63-EV] g03a.config-authority pi=" + rejectedPi
                + " deviceKey=" + NO_ACCESS_DEVICE_KEY + " processAccess=0"
                + " rejected=404-device-target reservations=0 commands=0");

        // 合法对照：同租同产品同功能，仅接入授权=1 → 正常冻结 PENDING
        String okPi = "p63-ca-access-pi";
        LocalDateTime dueUtc = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        java.util.Optional<Long> intentId = asUser(() -> tx.execute(status ->
                reservationFacade.createIntent(TENANT, okPi, "p63_ca_proc", 1, "p63_ca_form", "rec-ca-2",
                        ACCESS_DEVICE_KEY, "P63-CA-PROD", ACCESS_DEVICE_NAME,
                        "power_off", "PROPERTY", "{\"power_off\":true}", dueUtc,
                        "Asia/Shanghai", "2026-10-08 12:00:00", 60)));
        assertThat(intentId).as("合法候选人冻结成功").isPresent();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, device_key FROM sw_iot_command_reservation WHERE id = ?", intentId.get());
        assertThat(row.get("status")).as("合法对照：PENDING").isEqualTo("PENDING");
        assertThat(row.get("device_key")).isEqualTo(ACCESS_DEVICE_KEY);
        System.out.println("[P63-EV] g03a.config-authority-control pi=" + okPi
                + " intent=" + intentId.get() + "=PENDING processAccess=1");
    }

    @Test
    @DisplayName("已发布功能 + 未知 commandType → 冻结前 400 拒绝、零意图零外发")
    void freezeRejectsUnknownCommandTypeBeforeFreeze() {
        String pi = "p63-ca-unknown-type-pi";
        expectFreezeReject(pi, ACCESS_DEVICE_KEY, ACCESS_DEVICE_NAME,
                "power_off", "SCRIPT", "{\"power_off\":true}", "未知功能类型", "SCRIPT");
        System.out.println("[P63-EV] g03a.unknown-type pi=" + pi
                + " rejected=400-unknown-command-type reservations=0 commands=0");
    }

    @Test
    @DisplayName("键已声明但类型不匹配（ACTION 键按 PROPERTY / PROPERTY 键按 ACTION）→ 冻结前 404、零意图零外发")
    void freezeRejectsTypeMismatchedDeclaredFunction() {
        String piAction = "p63-ca-mismatch-action-key-pi";
        // factory_reset 仅声明在 actions，按 PROPERTY 冻结 → properties.factory_reset 未声明
        expectFreezeReject(piAction, ACCESS_DEVICE_KEY, ACCESS_DEVICE_NAME,
                "factory_reset", "PROPERTY", "{\"factory_reset\":true}",
                "功能未在已发布物模型中声明", "properties.factory_reset");
        System.out.println("[P63-EV] g03a.type-mismatch-action-key pi=" + piAction
                + " rejected=404-properties.factory_reset reservations=0");

        String piProperty = "p63-ca-mismatch-property-key-pi";
        // power_off 仅声明在 properties，按 ACTION 冻结 → actions.power_off 未声明
        expectFreezeReject(piProperty, ACCESS_DEVICE_KEY, ACCESS_DEVICE_NAME,
                "power_off", "ACTION", "{\"power_off\":true}",
                "功能未在已发布物模型中声明", "actions.power_off");
        System.out.println("[P63-EV] g03a.type-mismatch-property-key pi=" + piProperty
                + " rejected=404-actions.power_off reservations=0 commands=0");
    }

    // ==================== helpers ====================

    /** 冻结前拒绝断言：真实门面 + MANDATORY 事务模板直调；拒绝后零意图零外发。 */
    private void expectFreezeReject(String processInstanceId, String deviceKey, String deviceName,
                                    String commandKey, String commandType, String payload,
                                    String... messageFragments) {
        LocalDateTime dueUtc = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(30);
        assertThatThrownBy(() -> callCreateIntent(processInstanceId, deviceKey, deviceName,
                commandKey, commandType, payload, dueUtc))
                .as("冻结前拒绝").isInstanceOf(BaseException.class)
                .hasMessageContaining(messageFragments[0]);
        for (String fragment : messageFragments) {
            assertThatThrownBy(() -> callCreateIntent(processInstanceId + "-recheck", deviceKey,
                    deviceName, commandKey, commandType, payload, dueUtc))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining(fragment);
        }
        assertThat(reservationCount(processInstanceId)).as("拒绝后零意图（事务回滚）").isZero();
        assertThat(commandCount(processInstanceId)).as("拒绝后零外发").isZero();
    }

    private java.util.Optional<Long> callCreateIntent(String processInstanceId, String deviceKey,
                                                      String deviceName, String commandKey,
                                                      String commandType, String payload,
                                                      LocalDateTime dueUtc) {
        return asUser(() -> tx.execute(status -> reservationFacade.createIntent(TENANT, processInstanceId,
                "p63_ca_proc", 1, "p63_ca_form", "rec-" + processInstanceId,
                deviceKey, "P63-CA-PROD", deviceName, commandKey, commandType, payload, dueUtc,
                "Asia/Shanghai", "2026-10-08 12:00:00", 60)));
    }

    private int reservationCount(String processInstanceId) {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                    Integer.class, processInstanceId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int commandCount(String processInstanceId) {
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_iot_device_command WHERE approval_biz_id = ?",
                    Integer.class, processInstanceId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T asUser(Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(OPERATOR);
        user.setTenantId(TENANT);
        user.setPermissions(new ArrayList<>(List.of("form:action:invoke", "workflow:def:publish")));
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
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair pair = gen.generateKeyPair();
            return java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
