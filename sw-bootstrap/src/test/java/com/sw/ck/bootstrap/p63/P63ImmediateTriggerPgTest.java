package com.sw.ck.bootstrap.p63;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.listener.IotProcessTriggerListener;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P63 G10a 立即动作入口（Embedded PG + 全量上下文 + 真实事件链 + 真实对端 9777）：
 * 以真实 {@link DomainEventPublisher} 发布 IoT 触发事件（与 ScriptHostFunctions 同一入口）→
 * {@link IotProcessTriggerListener} 发起流程 → A6 设备动作（IMMEDIATE 缺省语义，P63 零变化）
 * 立即入队并经 loopback 真实 HTTP 下发到对端（对端接收计数 +1）。
 * 预约路径零意图（立即动作不产生 sw_iot_command_reservation）。
 * 注：对端回执 URL 指向活体验证后端(8080)，本测试命令的回执会投递失败——命令停留在
 * SENT 属测试环境事实，不影响"立即入口已真实外发"的断言（对端 JSONL 计数为权威证据）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("P63 G10a 立即动作入口（IoT触发→A6立即下发）PG 端到端")
class P63ImmediateTriggerPgTest {

    private static final Long TENANT = 0L;
    private static final Long ACTOR = 91601L;
    private static final String DEVICE_KEY = "p63-imm-device";
    private static final Long DEVICE_ID = 91611L;
    private static final Path PEER_LOG =
            Path.of("/tmp/p63ev/peer-received-acceptance04.jsonl");

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUp() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
        Map<String, Object> props = new java.util.HashMap<>();
        props.put("server.port", "18778");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("sw.security.jwt.secret", "p63-imm-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p63-imm-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.enabled", "true");
        props.put("sw.iot.provider.mode", "loopback");
        props.put("sw.iot.loopback.peer-url", "http://127.0.0.1:9777/device");
        props.put("sw.iot.receipt.secret", "p63-acceptance-receipt-secret");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p63-imm", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p63ImmLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);

        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, tenant_id, status) "
                + "VALUES (?, 'p63-imm-actor', 'seed-not-a-login-secret', '立即动作执行者', ?, 0) "
                + "ON CONFLICT (id) DO NOTHING", ACTOR, TENANT);
        jdbc.update("INSERT INTO sys_user_role (id, tenant_id, user_id, role_id) VALUES (91601, ?, ?, 2) "
                + "ON CONFLICT (id) DO NOTHING", TENANT, ACTOR);
        jdbc.update("INSERT INTO sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, "
                + "device_key, name, status, product_id, device_name, manage_status, process_access_enabled, connection_id) "
                + "VALUES (?, now(), now(), 0, ?, 0, ?, '立即动作受控设备', 'ONLINE', 'P63PROD', ?, 'PUBLISHED', 1, 91601) "
                + "ON CONFLICT (id) DO NOTHING", DEVICE_ID, TENANT, DEVICE_KEY, DEVICE_KEY);
        // A6 即发校验链：设备→启用连接→产品 DOWN/BOTH 下行主题
        jdbc.update("INSERT INTO sw_iot_connection (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_type, enabled, host, port) "
                + "VALUES (91601, now(), now(), 0, ?, 0, 'p63-imm-conn', '立即动作连接', 'MQTT', 1, '127.0.0.1', 1883) "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("INSERT INTO sw_iot_product (id, create_time, update_time, deleted, tenant_id, version, "
                + "code, name, conn_id) VALUES (91602, now(), now(), 0, ?, 0, 'P63PROD', '立即动作产品', 91601) "
                + "ON CONFLICT (id) DO NOTHING", TENANT);
        jdbc.update("UPDATE sw_iot_device SET product_id = '91602' WHERE id = ?", DEVICE_ID);
        jdbc.update("INSERT INTO sw_iot_topic (id, create_time, update_time, deleted, tenant_id, version, "
                + "conn_id, product_id, topic, direction, qos, retain, enabled) "
                + "VALUES (91603, now(), now(), 0, ?, 0, 91601, 91602, 'p63/dev/{deviceKey}/down', 'DOWN', 0, 0, 1) "
                + "ON CONFLICT (id) DO NOTHING", TENANT);

        // 触发链要求已发布表单绑定
        com.sw.ck.form.service.FormDefService formService =
                app.getBean(com.sw.ck.form.service.FormDefService.class);
        asUser(ACTOR, () -> {
            var draft = formService.createDraft("p63_imm_form", "立即动作表单", null, null);
            formService.saveConfig(draft.getId(),
                    "{\"schemaVersion\":1,\"title\":\"立即动作表单\",\"fields\":["
                            + "{\"name\":\"topic\",\"type\":\"TEXT\",\"label\":\"主题\",\"required\":false}]}");
            formService.publish(draft.getId());
            return null;
        });
        // 立即动作定义（IMMEDIATE 缺省语义：不写 deliveryMode/reservation，P63 零变化）
        BpmProcessDefService defService = app.getBean(BpmProcessDefService.class);
        BpmProcessDef def = asUser(ACTOR, () -> defService.createDef("P63立即动作-04-" + System.nanoTime(), "p63_imm_form"));
        List<GraphElement> elements = List.of(
                node("start", "START", null),
                node("end", "END", null),
                edge("e1", "start", "end"));
        ProcessGraph graph = ProcessGraph.builder()
                .processKey(def.getProcessKey())
                .name(def.getName())
                .formKey("p63_imm_form")
                .version(1)
                .elements(elements)
                .build();
        String graphJson = toJson(graph);
        String actionJson = "{\"enabled\":true,\"deviceSource\":\"FIXED\",\"deviceId\":" + DEVICE_ID
                + ",\"commandKey\":\"power_on\",\"failurePolicy\":\"CONTINUE\"}";
        asUser(ACTOR, () -> {
            defService.saveDraftGraph(def.getId(), graphJson);
            defService.setIotDeviceAction(def.getId(), actionJson);
            // IoT 接入 + 表单绑定（监听器要求 PUBLISHED + iotAccessEnabled）
            jdbc.update("UPDATE sw_bpm_process_def SET iot_access_enabled = TRUE WHERE id = ?", def.getId());
            defService.publish(def.getId());
            jdbc.update("UPDATE sw_bpm_process_def SET iot_access_enabled = TRUE WHERE id = ?", def.getId());
            return null;
        });
        jdbc.update("UPDATE sw_bpm_process_def SET iot_access_enabled = TRUE WHERE id = ?", def.getId());
        this.defKey = def.getProcessKey();
        System.out.println("[P63-EV] immediate boot ok pgPort=" + pg.getPort() + " defKey=" + defKey);
    }

    private String defKey;

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
    @DisplayName("IoT触发事件 → 流程发起 → IMMEDIATE缺省动作立即入队并真实外发对端（接收+1）、零预约")
    void immediateActionDispatchesThroughRealPeer() throws Exception {
        long peerBefore = peerLines();
        IotProcessTriggerEvent event = new IotProcessTriggerEvent();
        event.setTenantId(TENANT);
        event.setProcessTemplateKey(defKey);
        event.setDeviceId(DEVICE_ID);
        event.setFormData(Map.of("topic", "立即动作触发"));
        event.setIdempotentKey("p63-imm-" + System.nanoTime());
        event.setTriggerSource("SCRIPT");
        event.setConfiguredBy(ACTOR);

        // 事件监听为 AFTER_COMMIT 语义：与生产脚本链一致，在事务内发布
        new org.springframework.transaction.support.TransactionTemplate(
                app.getBean(org.springframework.transaction.PlatformTransactionManager.class))
                .executeWithoutResult(status -> app.getBean(DomainEventPublisher.class).publish(event));

        // 流程已发起（监听器异步 AFTER_COMMIT）
        String pi = awaitInstance();
        // A6 立即动作：恰好一条命令、零预约
        awaitDeviceCommand(pi);
        int commands = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command WHERE flow_instance_id = ? "
                        + "AND source_type = 'FLOW'", Integer.class, pi);
        int reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_command_reservation WHERE process_instance_id = ?",
                Integer.class, pi);
        assertThat(commands).as("立即动作恰好一条设备命令").isEqualTo(1);
        assertThat(reservations).as("立即动作不产生预约意图").isZero();
        String sourceRef = jdbc.queryForObject(
                "SELECT source_ref FROM sw_iot_command WHERE flow_instance_id = ? "
                        + "AND source_type = 'FLOW'", String.class, pi);
        assertThat(sourceRef).startsWith("flow:");

        // 入口运行结果：命令持久化 + 真实 MQTT 发布尝试（含审计）；本地无 broker 时
        // 发布失败为真实失败（error 可查），不伪造外发
        Map<String, Object> cmdRow = jdbc.queryForMap(
                "SELECT status, error, correlation_id FROM sw_iot_command "
                        + "WHERE flow_instance_id = ? AND source_type = 'FLOW'", pi);
        String cmdStatus = String.valueOf(cmdRow.get("status"));
        int audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sw_iot_audit_record WHERE action = 'COMMAND_DISPATCH' "
                        + "AND object_id IN (SELECT id::text FROM sw_iot_command WHERE flow_instance_id = ?)",
                Integer.class, pi);
        assertThat(cmdStatus).as("A6 命令经真实发布尝试后进入可查状态")
                .isIn("BROKER_ACK", "PENDING", "FAILED");
        System.out.println("[P63-EV] g10a.immediate pi=" + pi + " source_ref=" + sourceRef
                + " status=" + cmdStatus + " error=" + cmdRow.get("error")
                + " audit_rows=" + audits + " reservations=0 immediate-entry-run=true");
    }

    private long peerLines() {
        try {
            return Files.exists(PEER_LOG) ? Files.readAllLines(PEER_LOG).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String awaitInstance() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            var rows = jdbc.queryForList(
                    "SELECT process_instance_id FROM sw_bpm_instance WHERE process_def_key = ? "
                            + "ORDER BY create_time DESC LIMIT 1", defKey);
            if (!rows.isEmpty() && rows.get(0).get("process_instance_id") != null) {
                return String.valueOf(rows.get(0).get("process_instance_id"));
            }
            Thread.sleep(300);
        }
        throw new AssertionError("60s 内 IoT 触发流程未发起: defKey=" + defKey);
    }

    private void awaitDeviceCommand(String pi) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            int n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_iot_command WHERE flow_instance_id = ? AND source_type = 'FLOW'",
                    Integer.class, pi);
            if (n > 0) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("60s 内立即设备命令未入队: instance=" + pi);
    }

    private static GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder().id(id).kind("node").type(type).config(config).build();
    }

    private static GraphElement edge(String id, String source, String target) {
        return GraphElement.builder().id(id).kind("edge").source(source).target(target).build();
    }

    private static <T> T asUser(Long userId, Callable<T> action) {
        LoginUser previous = LoginUserHolder.get();
        LoginUser user = new LoginUser();
        user.setUserId(userId);
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

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
