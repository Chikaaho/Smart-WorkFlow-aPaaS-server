package com.sw.ck.bootstrap.iot;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bootstrap.p63.P63ControlledMqttBroker;
import com.sw.ck.iot.job.IotReservationDispatchJob;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 原生 MQTT 设备控制通道（延迟/预约语义）在真实 Broker 上的实际接收验证。
 *
 * <ul>
 *   <li>受控隔离 Broker（127.0.0.1:18831，任务资产，与测试同生命周期）承载「实际接收」层级：
 *       并非模拟 provider，也不改写 A6 立即通道；断言直接读 Broker 的接收记录（jsonl 原件）。</li>
 *   <li>正向：预约到点（真实 {@code IotReservationDispatchJob} 入口）→ 依赖校验 → 命令入队 →
 *       自建 MQTT 通道发布 → 命令 SENT（tencent_request_id 带 MQTT 引用）+ 预约 DISPATCHED +
 *       Broker 实收 payload。这正是 UAT 上原先 FAILED（无 provider）的同一路径。</li>
 *   <li>反向：连接停用时不得外发——命令记可重试失败、绝不 SENT、Broker 零新增接收行。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("原生 MQTT 预约命令下发受控 Broker PG 端到端")
class IotNativeMqttDeferredDispatchPgTest {

    private static final Long TENANT = 9001L;
    private static final Long CONN_ID = 91901L;
    private static final Long TOPIC_DOWN_ID = 91902L;
    private static final Long TOPIC_UP_ID = 91903L;
    private static final Long DEVICE_ID = 91911L;
    private static final Long PRODUCT_ID = 91921L;
    private static final Long MODEL_ID = 91922L;
    private static final String DEVICE_KEY = "nat-mqtt-drill-device";
    private static final String COMMAND_KEY = "drill_execute";
    private static final int BROKER_PORT = 18831;
    private static final Path RECEIVED_LOG = Path.of("/tmp/p63ev/mqtt-deferred-received.jsonl");

    private P63ControlledMqttBroker broker;
    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private IotReservationDispatchJob dispatchJob;

    @BeforeAll
    void setUp() throws Exception {
        broker = P63ControlledMqttBroker.start("127.0.0.1", BROKER_PORT, RECEIVED_LOG);
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "nat-mqtt-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "nat-mqtt-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.receipt.secret", "nat-mqtt-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("nat-mqtt", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("natMqttLoginContextProvider", provider);
                })
                .run();
        // 本用例显式调用生产调度入口；取消自动任务，避免后台先认领导致手动调用提前返回。
        // 在插入任何预约或命令前完成取消，生产调度配置保持不变。
        app.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)
                .getScheduledTasks().forEach(task -> task.cancel(false));
        jdbc = app.getBean(JdbcTemplate.class);
        dispatchJob = app.getBean(IotReservationDispatchJob.class);

        jdbc.update("INSERT INTO sw_iot_connection (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, enabled, host, port, use_tls, keepalive, client_id_prefix) "
                + "VALUES (?, now(), now(), 0, ?, 0, 'NATMQTT', '原生MQTT受控broker', 'MQTT', 1, '127.0.0.1', ?, "
                + "0, 30, 'nattest') ON CONFLICT (id) DO NOTHING", CONN_ID, TENANT, BROKER_PORT);
        jdbc.update("INSERT INTO sw_iot_topic (id, create_time, update_time, deleted, tenant_id, version, "
                + "conn_id, product_id, topic, direction, qos, retain, enabled) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, NULL, 'nat/{deviceKey}/cmd', 'DOWN', 1, 0, 1) "
                + "ON CONFLICT (id) DO NOTHING", TOPIC_DOWN_ID, TENANT, CONN_ID);
        jdbc.update("INSERT INTO sw_iot_topic (id, create_time, update_time, deleted, tenant_id, version, "
                + "conn_id, product_id, topic, direction, qos, retain, enabled) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, NULL, 'nat/{deviceKey}/up', 'UP', 1, 0, 1) "
                + "ON CONFLICT (id) DO NOTHING", TOPIC_UP_ID, TENANT, CONN_ID);
        jdbc.update("INSERT INTO sw_iot_product (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, model_status, published_model_id) "
                + "VALUES (?, now(), now(), 0, ?, 0, 'NATPROD', '原生MQTT演练产品', 'MQTT', 'PUBLISHED', ?) "
                + "ON CONFLICT (id) DO NOTHING", PRODUCT_ID, TENANT, MODEL_ID);
        jdbc.update("INSERT INTO sw_iot_thing_model (id, create_time, update_time, deleted, tenant_id, version, "
                + "product_id, model_version, status, content_json, publish_time) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 1, 'PUBLISHED', "
                + "'{\"properties\":[{\"id\":\"" + COMMAND_KEY + "\"}],\"events\":[],\"actions\":[]}', now()) "
                + "ON CONFLICT (id) DO NOTHING", MODEL_ID, TENANT, PRODUCT_ID);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, "
                + "product_ref_id, connection_id) VALUES (?, now(), now(), 0, ?, 0, ?, '原生MQTT演练机房A', "
                + "'ONLINE', 'NATPROD', ?, 'PUBLISHED', 1, ?, ?) ON CONFLICT (id) DO NOTHING",
                DEVICE_ID, TENANT, DEVICE_KEY, DEVICE_KEY, PRODUCT_ID, CONN_ID);
        System.out.println("[NAT-EV] boot ok pgPort=" + pg.getPort() + " broker=127.0.0.1:" + BROKER_PORT);
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
        if (pg != null) {
            try {
                pg.close();
            } catch (Exception ignored) {
                // 关闭容错
            }
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    @DisplayName("预约到点→原生 MQTT 通道真实下发→Broker 实收+命令 SENT+预约 DISPATCHED")
    void reservationDispatchedOverNativeMqttActuallyReceived() throws Exception {
        long reservationId = 91931L;
        long before = receivedLines();
        insertDueReservation(reservationId, "inst-native-mqtt-1");

        dispatchJob.dispatchDueReservations();

        String reservationStatus = jdbc.queryForObject(
                "SELECT status FROM sw_iot_command_reservation WHERE id = ?", String.class, reservationId);
        assertThat(reservationStatus).as("预约已到点下发").isEqualTo("DISPATCHED");

        Long commandId = jdbc.queryForObject(
                "SELECT command_id FROM sw_iot_command_reservation WHERE id = ?", Long.class, reservationId);
        assertThat(commandId).as("预约关联命令已生成").isNotNull();
        Map<String, Object> command = jdbc.queryForMap(
                "SELECT status, tencent_request_id, last_error FROM sw_iot_device_command WHERE id = ?",
                commandId);
        assertThat(command.get("status")).as("命令已发送（不再是无 provider 的可重试失败）").isEqualTo("SENT");
        assertThat(String.valueOf(command.get("tencent_request_id")))
                .as("外发引用指向 MQTT 通道").startsWith("MQTT:nat/" + DEVICE_KEY + "/cmd:");
        assertThat(command.get("last_error")).as("无失败原因残留").isNull();

        // QoS1 发布同步等待 PUBACK；Broker 在确认前已持久化接收行。
        String received = receivedLineAfterPublish(before);
        assertThat(received).as("受控 Broker 实际收到预约命令（主题+载荷）")
                .contains("nat/" + DEVICE_KEY + "/cmd").contains("机房断电").contains("火警告警");
        System.out.println("[NAT-EV] reservation-mqtt reservationId=" + reservationId + " commandId=" + commandId
                + " status=SENT reservation=DISPATCHED received=" + received.trim()
                + " broker=127.0.0.1:" + BROKER_PORT + " controlled-isolated=true");
    }

    @Test
    @DisplayName("连接停用：预约到点不得外发——命令可重试失败、绝不 SENT、Broker 零新增")
    void disabledConnectionNeverDispatchesReservation() throws Exception {
        long reservationId = 91932L;
        jdbc.update("UPDATE sw_iot_connection SET enabled = 0 WHERE id = ?", CONN_ID);
        try {
            long before = receivedLines();
            insertDueReservation(reservationId, "inst-native-mqtt-disabled");
            dispatchJob.dispatchDueReservations();

            Long commandId = jdbc.queryForObject(
                    "SELECT command_id FROM sw_iot_command_reservation WHERE id = ?", Long.class, reservationId);
            assertThat(commandId).as("预约仍完成到点处理（失败可查）").isNotNull();
            Map<String, Object> command = jdbc.queryForMap(
                    "SELECT status, tencent_request_id, last_error FROM sw_iot_device_command WHERE id = ?",
                    commandId);
            assertThat(command.get("status")).as("未外发：可重试失败").isEqualTo("FAILED");
            assertThat(String.valueOf(command.get("tencent_request_id"))).as("绝不写已发送引用").isEqualTo("null");
            assertThat(String.valueOf(command.get("last_error"))).as("失败原因可排障").contains("发送通道未装配");
            assertThat(receivedLines()).as("Broker 零新增接收").isEqualTo(before);
            System.out.println("[NAT-EV] disabled-connection reservationId=" + reservationId
                    + " commandId=" + commandId + " status=FAILED brokerDelta=0");
        } finally {
            jdbc.update("UPDATE sw_iot_connection SET enabled = 1 WHERE id = ?", CONN_ID);
        }
    }

    // ==================== helpers ====================

    private void insertDueReservation(long reservationId, String processInstanceId) {
        jdbc.update("INSERT INTO sw_iot_command_reservation (id, create_time, update_time, deleted, tenant_id, "
                + "version, process_instance_id, process_def_key, def_version, form_key, record_id, device_key, "
                + "product_id, device_name, command_key, command_type, payload, due_at_utc, timezone_id, "
                + "due_local_text, late_window_seconds, status) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, 'nat-def', 1, 'nat_form', 'rec-1', ?, 'NATPROD', ?, ?, "
                + "'PROPERTY', '[\\\"机房断电\\\",\\\"火警告警\\\"]', ?, 'Asia/Shanghai', '2026-10-20 20:00:00', "
                + "600, 'PENDING')",
                reservationId, TENANT, processInstanceId, DEVICE_KEY, DEVICE_KEY, COMMAND_KEY,
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));
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

    private String receivedLineAfterPublish(long before) throws Exception {
        List<String> lines = Files.readAllLines(RECEIVED_LOG);
        return lines.stream().skip(before).filter(line -> line.contains(DEVICE_KEY))
                .findFirst().orElseThrow(() -> new AssertionError("QoS1 发布完成但 Broker 接收记录缺失"));
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
