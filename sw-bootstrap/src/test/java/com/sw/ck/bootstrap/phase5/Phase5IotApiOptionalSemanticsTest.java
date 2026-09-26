package com.sw.ck.bootstrap.phase5;

import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.api.IotDeviceQueryFacade;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 · IoT 契约的 present / empty / exception 两层语义行为证据（真实 PostgreSQL）。
 *
 * <p>Phase 1 规范要求每个契约方法的 <b>"目标不存在"</b> 与 <b>"查询合法执行但零匹配"</b> 两层
 * 语义都有行为测试固定；Phase 5 的 7 个开放方法必须同规范，不得以 empty 代替合法结果、
 * 也不得把真实错误吞成 empty。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase5 · IoT 契约两层语义 · 真实 PostgreSQL")
class Phase5IotApiOptionalSemanticsTest extends Phase5PgSupport {

    private static final String PRODUCT = "p5-opt-product";
    private static final String DEVICE_NAME = "p5-opt-device";
    private static final String DEVICE_KEY_ELIGIBLE = "p5-opt-eligible";
    /** A6 路径设备：与审批路径设备使用不同 device_key，避免 selectOne(limit 1) 命中另一条记录。 */
    private static final String DEVICE_KEY_A6 = "p5-opt-a6-device";
    private static final String DEVICE_KEY_NO_ROUTE = "p5-opt-no-route";
    private static final String DEVICE_KEY_UNKNOWN = "p5-opt-unknown";
    private static final String TRIGGER_KEY = "p5-opt-trigger";

    private IotDeviceFacade deviceFacade;
    private IotDeviceQueryFacade deviceQueryFacade;
    private IotProcessTriggerFacade triggerFacade;

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrateOnce();
        boot(52L);
        seedTenantAndUser(TENANT_A, USER_A, "opt");
        // 审批命令路径设备（product_id + device_name 定位）
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (9570, now(), now(), 0, ?, 0, ?, 'P5 语义设备', ?, ?, 'ONLINE', 'MANAGED', 1)"
                        + " on conflict (id) do nothing",
                TENANT_A, DEVICE_KEY_ELIGIBLE, PRODUCT, DEVICE_NAME);
        // A6 命令路径：合格设备（PUBLISHED + 接入开启 + 启用连接 + 下行主题）
        jdbc.update("insert into sw_iot_connection (id, create_time, update_time, deleted, tenant_id, version,"
                        + " code, name, conn_type, enabled) values (9571, now(), now(), 0, ?, 0,"
                        + " 'p5-opt-conn', 'P5 语义连接', 'MQTT', 1) on conflict (id) do nothing", TENANT_A);
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, status, manage_status, process_access_enabled, connection_id)"
                        + " values (9572, now(), now(), 0, ?, 0, ?, 'P5 合格设备', 'ONLINE', 'PUBLISHED', 1, 9571)"
                        + " on conflict (id) do nothing",
                TENANT_A, DEVICE_KEY_A6);
        jdbc.update("insert into sw_iot_topic (id, create_time, update_time, deleted, tenant_id, version, conn_id,"
                        + " topic, direction, qos, enabled) values (9573, now(), now(), 0, ?, 0, 9571,"
                        + " 'p5/down/{deviceKey}', 'DOWN', 1, 1) on conflict (id) do nothing", TENANT_A);
        // 同一设备的"无下行路由"对照：设备合格但连接不可用
        // （product_id + device_name + tenant_id 上有唯一索引，故使用独立的设备身份）
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (9574, now(), now(), 0, ?, 0, ?, 'P5 无路由设备', ?, ?, 'ONLINE', 'PUBLISHED', 1)"
                        + " on conflict (id) do nothing",
                TENANT_A, DEVICE_KEY_NO_ROUTE, PRODUCT + "-no-route", DEVICE_KEY_NO_ROUTE);
        // 触发回写载体
        jdbc.update("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id, version,"
                        + " idempotent_key, trigger_source, status, trigger_time, retry_count)"
                        + " values (9575, now(), now(), 0, ?, 0, ?, 'RULE', 'PENDING', now(), 0)"
                        + " on conflict (id) do nothing", TENANT_A, TRIGGER_KEY);

        deviceFacade = app.getBean(IotDeviceFacade.class);
        deviceQueryFacade = app.getBean(IotDeviceQueryFacade.class);
        triggerFacade = app.getBean(IotProcessTriggerFacade.class);
        System.out.println("[P5-EV] optional.context=ready approval-device-key=" + DEVICE_KEY_ELIGIBLE
                + " a6-device-key=" + DEVICE_KEY_A6
                + " no-route=" + DEVICE_KEY_NO_ROUTE + " unknown=" + DEVICE_KEY_UNKNOWN);
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    // ==================== 设备命令：present ====================

    @Test
    @Order(1)
    @DisplayName("设备命令 present：入队成功返回命令 ID；重复投递命中同一幂等键复用既有命令")
    void dispatchCommandIdempotentPresent() {
        asTenant(TENANT_A, USER_A, () -> {
            String key = "APPROVAL:p5-opt-pi:" + DEVICE_NAME + ":reboot";
            Optional<Long> first = deviceFacade.dispatchCommandIdempotent(PRODUCT, DEVICE_NAME,
                    "reboot", "PROPERTY", null, "p5-opt-pi", key);
            assertThat(first).as("入队成功必须 present（命令已持久化）").isPresent();
            Optional<Long> second = deviceFacade.dispatchCommandIdempotent(PRODUCT, DEVICE_NAME,
                    "reboot", "PROPERTY", null, "p5-opt-pi", key);
            assertThat(second).as("重复投递必须 present 且复用既有命令").contains(first.orElseThrow());
            assertThat(count("select count(*) from sw_iot_device_command where idempotent_key = ?", key))
                    .as("同一业务身份只产生一条命令").isEqualTo(1L);
            assertThat(text("select status from sw_iot_device_command where idempotent_key = ?", key))
                    .isEqualTo("QUEUED");
            System.out.println("[P5-EV] optional.dispatch-idempotent present=2 rows=1 status=QUEUED");
            return null;
        });
    }

    @Test
    @Order(2)
    @DisplayName("设备命令 exception：目标设备不存在属真实错误，继续抛出而不是吞成 empty")
    void dispatchCommandThrowsForMissingDevice() {
        asTenant(TENANT_A, USER_A, () -> {
            assertThatThrownBy(() -> deviceFacade.dispatchCommand(PRODUCT, "p5-opt-missing-device",
                    "reboot", "PROPERTY", null, "p5-opt-pi"))
                    .as("设备不存在必须抛出（不得伪装为不适用）")
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("设备不存在");
            System.out.println("[P5-EV] optional.dispatch-command exception=device-not-found empty=0");
            return null;
        });
    }

    // ==================== A6 路径：present / empty 三层 ====================

    @Test
    @Order(3)
    @DisplayName("A6 命令 present/empty：合格设备 present；设备不存在与无下行路由均为 empty（不适用）")
    void dispatchByDeviceKeyPresentAndEmpty() {
        asTenant(TENANT_A, USER_A, () -> {
            Optional<Long> eligible = deviceFacade.dispatchByDeviceKey(TENANT_A, DEVICE_KEY_A6,
                    "reset", "{}", "p5-opt-flow", "FIXED");
            assertThat(eligible).as("合格设备必须 present").isPresent();
            assertThat(count("select count(*) from sw_iot_command where flow_instance_id = ?", "p5-opt-flow"))
                    .as("命令已写入统一命令表").isEqualTo(1L);

            Optional<Long> unknown = deviceFacade.dispatchByDeviceKey(TENANT_A, DEVICE_KEY_UNKNOWN,
                    "reset", "{}", "p5-opt-flow", "FIXED");
            assertThat(unknown).as("设备不存在属不适用，必须 empty").isEmpty();

            Optional<Long> noRoute = deviceFacade.dispatchByDeviceKey(TENANT_A, DEVICE_KEY_NO_ROUTE,
                    "reset", "{}", "p5-opt-flow", "FIXED");
            assertThat(noRoute).as("设备合格但无可用连接/下行主题，必须 empty 而不是伪造受理").isEmpty();

            assertThat(count("select count(*) from sw_iot_command where flow_instance_id = ?", "p5-opt-flow"))
                    .as("不适用不得产生命令记录").isEqualTo(1L);
            System.out.println("[P5-EV] optional.dispatch-by-device-key present=1 unknown=empty noRoute=empty"
                    + " commandRows=1");
            return null;
        });
    }

    // ==================== 查询门面：present / empty ====================

    @Test
    @Order(4)
    @DisplayName("设备查询两层语义：命中 present；主键不存在 empty（而不是 null）")
    void getDeviceKeyByIdPresentAndEmpty() {
        asTenant(TENANT_A, USER_A, () -> {
            assertThat(deviceQueryFacade.getDeviceKeyById(9570L))
                    .as("命中设备必须 present").contains(DEVICE_KEY_ELIGIBLE);
            assertThat(deviceQueryFacade.getDeviceKeyById(99999999L))
                    .as("主键不存在必须 empty（查询目标缺失）").isEmpty();
            System.out.println("[P5-EV] optional.get-device-key-by-id present=1 empty=1");
            return null;
        });
    }

    // ==================== 触发回写：present(true)/present(false)/empty ====================

    @Test
    @Order(5)
    @DisplayName("触发回写三层语义：首次写回 present(true)；终态保护重复写回 present(false)；无记录 empty")
    void markTriggerResultThreeLayerSemantics() {
        asTenant(TENANT_A, USER_A, () -> {
        // 首次：改写为 SUCCESS
        assertThat(triggerFacade.markTriggerResult(TRIGGER_KEY, "p5-opt-instance", null))
                .as("首次写回必须 present(true)=已改写终态").contains(true);
        assertThat(text("select status from sw_iot_process_trigger where idempotent_key = ?", TRIGGER_KEY))
                .isEqualTo("SUCCESS");

        // 合法幂等/零变更：合法幂等属于"有值的结果"，必须 present(false)，不得伪装成 empty
        assertThat(triggerFacade.markTriggerResult(TRIGGER_KEY, "p5-opt-instance-2", null))
                .as("重复写回命中终态保护，必须 present(false) 而不是 empty").contains(false);
        assertThat(text("select process_instance_id from sw_iot_process_trigger where idempotent_key = ?",
                TRIGGER_KEY))
                .as("终态不可被降级/覆盖").isEqualTo("p5-opt-instance");

        // 迟到失败写回同样不得覆盖已成立成功
        assertThat(triggerFacade.markTriggerResult(TRIGGER_KEY, null, "迟到的失败写回"))
                .as("迟到失败写回同样 present(false)").contains(false);
        assertThat(text("select status from sw_iot_process_trigger where idempotent_key = ?", TRIGGER_KEY))
                .isEqualTo("SUCCESS");

        // 查询目标不存在：empty
        assertThat(triggerFacade.markTriggerResult("p5-opt-absent-key", "p5-opt-instance", null))
                .as("无该幂等键的触发记录必须 empty").isEmpty();
        System.out.println("[P5-EV] optional.mark-trigger-result firstWrite=true repeatWrite=false"
                + " lateFailure=false unknownKey=empty terminalState=SUCCESS");
        return null;
        });
    }
}
