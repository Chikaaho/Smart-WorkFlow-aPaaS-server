package com.sw.ck.bootstrap.phase5;

import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.job.CommandCompensationJob;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 · 审批设备命令的两条事务分界行为（真实 PostgreSQL + 真实 Flowable）。
 *
 * <p>方向 §2.D.4 要求先补行为断言锁定分界，再实施边界迁移。本类按生产路径观测：</p>
 * <ol>
 *   <li><b>分界一 · 事务内记录失败</b>：审批事务内无法持久化设备命令意图（含目标设备不存在）时
 *       必须 fail closed，审批不得单边成功——引擎推进与命令行必须同时消失；</li>
 *   <li><b>分界二 · 持久化后发送失败</b>：意图已随审批提交后，下游发送失败不得回滚已完成审批，
 *       失败必须以可审计状态记录并交由既有恢复调度继续处理。</li>
 * </ol>
 * <p>正向对照（设备存在时意图与审批共同提交）用于证明断言不是空转。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase5 · 审批设备命令两条事务分界 · 真实 PostgreSQL")
class Phase5PgDeviceCommandBoundaryBehaviourTest extends Phase5PgSupport {

    private static final String FLOW_KEY = "p5_boundary_flow";
    private static final String FORM_KEY = "p5_boundary_form";
    private static final String PRODUCT_ID = "p5-product";
    private static final String DEVICE_PRESENT = "p5-device-present";
    private static final String DEVICE_ABSENT = "p5-device-absent";
    private static final String COMMAND_KEY = "reboot";

    /** 探针发布的设备身份：切换该值即可在"设备存在"与"设备不存在"之间切换同一生产路径。 */
    private static final AtomicReference<String> PROBE_DEVICE = new AtomicReference<>(DEVICE_PRESENT);

    private RuntimeService runtimeService;
    private TaskService taskService;
    private DomainEventPublisher eventPublisher;
    private CommandCompensationJob commandCompensationJob;

    /** t2 节点 create 监听器：在引擎事务内发布审批设备命令事件（与生产发布点同构）。 */
    public static class DeviceCommandProbe implements org.flowable.task.service.delegate.TaskListener {

        private final DomainEventPublisher publisher;

        public DeviceCommandProbe(DomainEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public void notify(org.flowable.task.service.delegate.DelegateTask delegateTask) {
            System.out.println("[P5-EV] probe.node=" + delegateTask.getTaskDefinitionKey()
                    + " device=" + PROBE_DEVICE.get()
                    + " inTransaction=" + org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            publisher.publish(new BpmDeviceCommandEvent(delegateTask.getProcessInstanceId(), PRODUCT_ID,
                    PROBE_DEVICE.get(), COMMAND_KEY, "PROPERTY", TENANT_A, USER_A));
        }
    }

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrateOnce();
        boot(51L);
        seedTenantAndUser(TENANT_A, USER_A, "boundary");
        // 仅种子"存在"的设备：DEVICE_ABSENT 刻意不落库，用于分界一的 fail-closed 观测
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (9560, now(), now(), 0, ?, 0, ?, 'P5 边界设备', ?, ?, 'ONLINE',"
                        + " 'MANAGED', 1) on conflict (id) do nothing",
                TENANT_A, DEVICE_PRESENT, PRODUCT_ID, DEVICE_PRESENT);
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, FLOW_KEY);
        deployBpmn(FLOW_KEY, bpmn(), FORM_KEY, false);
        eventPublisher = app.getBean(DomainEventPublisher.class);
        app.getBeanFactory().registerSingleton("phase5DeviceCommandProbe", new DeviceCommandProbe(eventPublisher));
        runtimeService = app.getBean(RuntimeService.class);
        taskService = app.getBean(TaskService.class);
        commandCompensationJob = app.getBean(CommandCompensationJob.class);
        System.out.println("[P5-EV] boundary.context=ready processKey=" + FLOW_KEY
                + " devicePresent=" + DEVICE_PRESENT + " deviceAbsent=not-seeded");
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    // ==================== 正向对照 ====================

    @Test
    @Order(1)
    @DisplayName("对照：设备存在时审批推进与设备命令意图在同一提交边界内共同成立")
    void presentDeviceCommitsApprovalAndIntentTogether() {
        PROBE_DEVICE.set(DEVICE_PRESENT);
        String businessKey = "p5-boundary-present";
        String processInstanceId = startAndCompleteFirstTask(businessKey, true);

        assertThat(activeTaskKeys(processInstanceId)).as("审批必须推进到 t2").containsExactly("t2");
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .as("设备命令意图必须随审批一起可追溯").isEqualTo(1L);
        assertThat(text("select status from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .as("意图入队状态").isEqualTo("QUEUED");
        System.out.println("[P5-EV] boundary.positive-control businessKey=" + businessKey
                + " engineTask=t2 intents=1 status=QUEUED");
    }

    // ==================== 分界一 · 事务内记录失败 ====================

    @Test
    @Order(2)
    @DisplayName("分界一：事务内无法持久化意图（目标设备不存在）时 fail closed，审批不得单边成功")
    void inTransactionRecordFailureFailsClosed() {
        PROBE_DEVICE.set(DEVICE_ABSENT);
        String businessKey = "p5-boundary-record-failure";
        String processInstanceId = startProcess(businessKey);
        assertThat(activeTaskKeys(processInstanceId)).as("起始审批停在 t1").containsExactly("t1");

        RuntimeException failure = null;
        try {
            inTransaction(true, () -> {
                taskService.complete(taskService.createTaskQuery()
                        .processInstanceBusinessKey(businessKey).taskDefinitionKey("t1").singleResult().getId());
                return null;
            });
        } catch (RuntimeException e) {
            failure = e;
        }

        assertThat(failure)
                .as("事务内记录失败必须显式失败，而不是静默放过")
                .isNotNull();
        assertThat(activeTaskKeys(processInstanceId))
                .as("审批不得单边成功：引擎推进必须随事务回滚")
                .containsExactly("t1");
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .as("失败路径不得留下设备命令或其可执行孤儿")
                .isZero();
        System.out.println("[P5-EV] boundary.in-tx-record-failure businessKey=" + businessKey
                + " failureType=" + failure.getClass().getSimpleName()
                + " engineTask=t1 intents=0");
    }

    // ==================== 分界二 · 持久化后发送失败 ====================

    @Test
    @Order(3)
    @DisplayName("分界二：意图已随审批提交后，下游发送失败不回滚审批，失败留可审计状态并由恢复调度续跑")
    void postPersistSendFailureDoesNotRollBackApproval() {
        PROBE_DEVICE.set(DEVICE_PRESENT);
        String businessKey = "p5-boundary-send-failure";
        String processInstanceId = startAndCompleteFirstTask(businessKey, true);
        assertThat(activeTaskKeys(processInstanceId)).as("审批已提交并推进到 t2").containsExactly("t2");

        String rowIdText = text("select id from sw_iot_device_command where approval_biz_id = ?", processInstanceId);
        assertThat(rowIdText).as("意图已持久化").isNotNull();
        long rowId = Long.parseLong(rowIdText);

        // 下游发送失败：把命令置为可重试失败态（等价于发送尝试失败后的落库结果）
        jdbc.update("update sw_iot_device_command set status = 'FAILED', retry_count = 0, last_error = '下行发送失败',"
                        + " expiry_time = ? where id = ?",
                java.time.LocalDateTime.now().plusHours(2), rowId);
        commandCompensationJob.execute();

        assertThat(activeTaskKeys(processInstanceId))
                .as("发送失败不得回滚已完成审批")
                .containsExactly("t2");
        assertThat(count("select count(*) from sw_iot_device_command where id = ?", rowId))
                .as("发送失败不得删除意图行")
                .isEqualTo(1L);
        assertThat(Integer.parseInt(text("select retry_count from sw_iot_device_command where id = ?", rowId)))
                .as("失败必须以可重试状态记录并由恢复调度继续处理")
                .isGreaterThanOrEqualTo(1);
        System.out.println("[P5-EV] boundary.post-persist-send-failure businessKey=" + businessKey
                + " rowId=" + rowId
                + " engineTask=t2 intentRows=1 retryCount="
                + text("select retry_count from sw_iot_device_command where id = ?", rowId)
                + " status=" + text("select status from sw_iot_device_command where id = ?", rowId));
    }

    // ==================== 流程夹具 ====================

    private String startProcess(String businessKey) {
        return inTransaction(true, () -> {
            Map<String, Object> variables = new HashMap<>();
            variables.put("approver", String.valueOf(USER_A));
            variables.put("submitter", String.valueOf(USER_A));
            variables.put("tenantId", TENANT_A);
            return runtimeService.startProcessInstanceByKeyAndTenantId(FLOW_KEY, businessKey, variables,
                    String.valueOf(TENANT_A)).getId();
        });
    }

    private String startAndCompleteFirstTask(String businessKey, boolean commit) {
        String processInstanceId = startProcess(businessKey);
        if (commit) {
            inTransaction(true, () -> {
                taskService.complete(taskService.createTaskQuery()
                        .processInstanceBusinessKey(businessKey).taskDefinitionKey("t1").singleResult().getId());
                return null;
            });
        } else {
            inTransaction(false, () -> {
                taskService.complete(taskService.createTaskQuery()
                        .processInstanceBusinessKey(businessKey).taskDefinitionKey("t1").singleResult().getId());
                return null;
            });
        }
        return processInstanceId;
    }

    private String bpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p5.evidence">
                  <process id="%s" name="P5 Boundary" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <userTask id="t1" name="first" flowable:assignee="1"/>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
                    <userTask id="t2" name="second" flowable:assignee="1">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${phase5DeviceCommandProbe}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f3" sourceRef="t2" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(FLOW_KEY);
    }
}
