package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.iot.api.impl.IotDeviceMqttDispatchService;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G10a 立即 MQTT 外发（隔离受控 Broker 实际接收）：
 * <ul>
 *   <li>受控自有 broker（127.0.0.1:18830，任务资产，PID 管理、退出清理）承载 A6 原通道——
 *       不改 A6 到 loopback，不部署长期服务，只证「实际接收」层级（非机房物理效果）。</li>
 *   <li>合法立即命令：真实 dispatch 入口 → sw_iot_command 先行 → broker publish →
 *       BROKER_ACK + 对端实际收到（jsonl 原件）+ 审计行；立即动作零预约意图。</li>
 *   <li>未知设备负向：运行时校验拒绝（返回 null），零命令副作用。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G10a 立即 MQTT 外发受控 Broker PG 端到端")
class P63ImmediateMqttBrokerPgTest {

    private static final Long TENANT = 0L;
    private static final Long CONN_ID = 91861L;
    private static final Long TOPIC_ID = 91862L;
    private static final Long DEVICE_ID = 91811L;
    private static final String DEVICE_KEY = "p63-mqtt-device";
    private static final Path RECEIVED_LOG = Path.of("/tmp/p63ev/mqtt-received.jsonl");

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private IotDeviceMqttDispatchService dispatchService;
    private MqttBrokerManager brokerManager;

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
        props.put("sw.security.jwt.secret", "p63-mqtt-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-mqtt-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", "p63-mqtt-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-mqtt", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63MqttLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        dispatchService = app.getBean(IotDeviceMqttDispatchService.class);
        brokerManager = app.getBean(MqttBrokerManager.class);

        // 受控隔离 broker（任务自有进程，隔离端口 18830；PID 在 /tmp/p63ev 由启动脚本登记）
        assertThat(brokerReady()).as("受控 broker 已就绪（任务启动）").isTrue();

        jdbc.update("INSERT INTO sw_iot_connection (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, enabled, host, port, use_tls, keepalive, client_id_prefix) "
                + "VALUES (?, now(), now(), 0, ?, 0, 'P63MQTT', 'P63受控broker', 'MQTT', 1, '127.0.0.1', 18830, "
                + "0, 30, 'p63test') ON CONFLICT (id) DO NOTHING", CONN_ID, TENANT);
        jdbc.update("INSERT INTO sw_iot_topic (id, create_time, update_time, deleted, tenant_id, version, "
                + "conn_id, product_id, topic, direction, qos, retain, enabled) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, NULL, 'p63/{deviceKey}/cmd', 'DOWN', 1, 0, 1) "
                + "ON CONFLICT (id) DO NOTHING", TOPIC_ID, TENANT, CONN_ID);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, "
                + "connection_id) VALUES (?, now(), now(), 0, ?, 0, ?, 'P63立即外发设备', 'ONLINE', 'P63PROD', "
                + "?, 'PUBLISHED', 1, ?) ON CONFLICT (id) DO NOTHING", DEVICE_ID, TENANT, DEVICE_KEY,
                DEVICE_KEY, CONN_ID);
        System.out.println("[P63-EV] mqtt boot ok pgPort=" + pg.getPort() + " broker=127.0.0.1:18830");
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
    }

    @Test
    @DisplayName("合法立即命令：真实入口→命令先行→broker BROKER_ACK→对端实际收到→审计；零预约")
    void immediateCommandActuallyReceivedByControlledBroker() throws Exception {
        // 真实连接建立入口（运行时同路径）；mapper 读与建立连接同在认证上下文内
        asSystemUser(() -> {
            IotConnection conn = app.getBean(IotConnectionMapper.class).selectById(CONN_ID);
            IotTopic topic = app.getBean(IotTopicMapper.class).selectById(TOPIC_ID);
            brokerManager.ensureStarted(conn, List.of(topic));
            return null;
        });

        long before = receivedLines();
        Long commandId = asSystemUser(() -> dispatchService.dispatch(TENANT, DEVICE_KEY, "power_on",
                "{\"level\":1}", "p63-g10a-biz"));
        assertThat(commandId).as("立即命令记录已创建").isNotNull();

        String status = jdbc.queryForObject(
                "SELECT status FROM sw_iot_command WHERE id = ?", String.class, commandId);
        assertThat(status).as("broker 实际接收确认（BROKER_ACK）").isEqualTo("BROKER_ACK");

        String received = awaitReceivedLineGrowth(before);
        // jsonl 信封内 payload 为转义 JSON 原文：断言下行主题与载荷键名
        assertThat(received).as("对端实际收到本命令（下行主题+载荷原文）")
                .contains("p63/" + DEVICE_KEY + "/cmd").contains("\"payload\"").contains("level");
        String capabilityId = jdbc.queryForObject(
                "SELECT capability_id FROM sw_iot_command WHERE id = ?", String.class, commandId);

        Integer reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                Integer.class, "p63-g10a-biz");
        assertThat(reservations).as("立即动作零预约意图").isZero();
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE object_type = 'COMMAND' "
                        + "AND object_id = ?", Integer.class, String.valueOf(commandId));
        assertThat(audits).as("下发审计行可查").isGreaterThanOrEqualTo(1);
        System.out.println("[P63-EV] g10a.immediate-received commandId=" + commandId + " status=BROKER_ACK"
                + " capabilityId=" + capabilityId + " received=" + received.trim()
                + " reservations=0 audits=" + audits
                + " broker=127.0.0.1:18830 controlled-isolated=true");
    }

    @Test
    @DisplayName("未知设备：运行时校验拒绝返回 null、零命令副作用")
    void unknownDeviceRejectedWithZeroSideEffects() {
        Long commandId = asSystemUser(() -> dispatchService.dispatch(TENANT, "p63-no-such-device",
                "power_on", "{}", "p63-g10a-neg"));
        assertThat(commandId).as("未知设备拒绝").isNull();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command WHERE source_ref LIKE ?", Integer.class,
                "%p63-g10a-neg%");
        assertThat(rows).as("拒绝零命令副作用").isZero();
        System.out.println("[P63-EV] g10a.unknown-device commandId=null commandRows=0 result=rejected");
    }

    // ==================== helpers ====================

    /** 生产 dispatch 携带认证上下文运行；测试以同构登录态调用真实入口。 */
    private <T> T asSystemUser(java.util.concurrent.Callable<T> action) {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(91899L);
        user.setTenantId(TENANT);
        user.setPermissions(List.of("iot:device:manage"));
        com.sw.ck.security.holder.LoginUserHolder.set(user);
        try {
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    private boolean brokerReady() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 18830), 3000);
            return socket.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private long receivedLines() {
        try {
            if (!Files.exists(RECEIVED_LOG)) {
                return 0;
            }
            return Files.readAllLines(RECEIVED_LOG).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private String awaitReceivedLineGrowth(long before) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(RECEIVED_LOG)) {
                List<String> lines = Files.readAllLines(RECEIVED_LOG);
                for (int i = (int) Math.min(before, lines.size()); i < lines.size(); i++) {
                    if (lines.get(i).contains(DEVICE_KEY)) {
                        return lines.get(i);
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("30s 内受控 broker 未实际收到命令");
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
