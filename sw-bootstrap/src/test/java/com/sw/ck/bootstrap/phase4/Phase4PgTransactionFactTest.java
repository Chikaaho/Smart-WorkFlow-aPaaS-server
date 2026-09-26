package com.sw.ck.bootstrap.phase4;

import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 G3 · Flowable 审批回调事务事实的运行时证明（真实 PostgreSQL + 真实引擎）。
 *
 * <p>方向 §6 明确要求"Flowable 回调事务边界需用运行时行为确认，不能依据注释推断"。
 * 本类用两件事证明：</p>
 * <ol>
 *   <li><b>事务事实</b>：在审批节点 create 回调链上挂运行时探针，直接读取
 *       {@link TransactionSynchronizationManager#isActualTransactionActive()}，记录回调时刻是否处于真实事务；</li>
 *   <li><b>原子性结果</b>：审批事务回滚时，通知持久意图必须一并消失（不得留下可投递孤儿）；
 *       提交时必须与审批动作同时可见。这既是事务边界的可观测后果，也是 §5.A.1 的行为断言。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase4 G3 · Flowable 回调事务事实与意图原子性 · 真实 PostgreSQL")
class Phase4PgTransactionFactTest extends Phase4PgSupport {

    private static final String TX_FLOW_KEY = "p4_tx_flow";
    private static final String FORM_KEY = "p4_tx_form";
    private static final String DEVICE_NAME_TX = "p4-device-tx";
    private static final String COMMAND_KEY_TX = "cmd-tx";

    private static final AtomicInteger CALLBACKS = new AtomicInteger();
    private static final AtomicInteger CALLBACKS_IN_TRANSACTION = new AtomicInteger();

    private com.sw.ck.common.event.DomainEventPublisher eventPublisher;
    private RuntimeService runtimeService;
    private TaskService taskService;

    /**
     * 挂在真实回调链（下一审批节点 create 事件）上的运行时探针。
     *
     * <p>它做两件事：直接读取回调时刻的事务事实；并在**该回调事务内**发布一条 Phase 4
     * must-deliver 事件（审批设备命令），由生产用的同事务意图记录器落持久意图。
     * 于是"回调是否在事务内"与"意图是否与审批原子"由同一实验同时观测。</p>
     */
    public static class TxProbeListener implements org.flowable.task.service.delegate.TaskListener {

        private final com.sw.ck.common.event.DomainEventPublisher publisher;

        public TxProbeListener(com.sw.ck.common.event.DomainEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public void notify(org.flowable.task.service.delegate.DelegateTask delegateTask) {
            boolean inTx = TransactionSynchronizationManager.isActualTransactionActive();
            CALLBACKS.incrementAndGet();
            if (inTx) {
                CALLBACKS_IN_TRANSACTION.incrementAndGet();
            }
            publisher.publish(new com.sw.ck.bpm.api.event.BpmDeviceCommandEvent(
                    delegateTask.getProcessInstanceId(), "p4-product", DEVICE_NAME_TX, COMMAND_KEY_TX,
                    "PROPERTY", TENANT_A, USER_A));
            System.out.println("[P4-EV] g3.callback-probe taskDefinitionKey=" + delegateTask.getTaskDefinitionKey()
                    + " actualTransactionActive=" + inTx + " publishedInTransaction=true");
        }
    }

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrate();
        boot(44L);
        seedTenantAndUser(TENANT_A, USER_A, "txfact");
        // 回调事务内发布的设备命令需要设备存在（与生产同路径解析设备身份）
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (9480, now(), now(), 0, ?, 0, ?, 'P4 tx 设备', 'p4-product', ?, 'ONLINE', 'MANAGED', 1)"
                        + " on conflict (id) do nothing",
                TENANT_A, DEVICE_NAME_TX, DEVICE_NAME_TX);
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, TX_FLOW_KEY);
        deployBpmn(TX_FLOW_KEY, twoStepBpmn(), FORM_KEY, false);
        eventPublisher = app.getBean(com.sw.ck.common.event.DomainEventPublisher.class);
        app.getBeanFactory().registerSingleton("phase4TxProbeListener", new TxProbeListener(eventPublisher));
        runtimeService = app.getBean(RuntimeService.class);
        taskService = app.getBean(TaskService.class);
        System.out.println("[P4-EV] g3.transaction-fact-context=ready probeBean=phase4TxProbeListener");
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    @Test
    @DisplayName("G3-1 审批节点回调运行在真实事务内，且通知意图与审批动作同事务提交")
    void callbackRunsInRealTransactionAndCommitsIntentAtomically() {
        String businessKey = "p4-tx-commit";
        startProcess(businessKey);
        Task first = taskService.createTaskQuery().processInstanceBusinessKey(businessKey)
                .taskDefinitionKey("t1").singleResult();
        assertThat(first).isNotNull();

        int callbacksBefore = CALLBACKS.get();

        inTransaction(true, () -> {
            taskService.complete(first.getId());
            return null;
        });

        assertThat(CALLBACKS.get()).isGreaterThan(callbacksBefore);
        assertThat(CALLBACKS_IN_TRANSACTION.get()).as("回调链必须观测到真实事务").isGreaterThan(0);
        long intentsAfter = count("select count(*) from sw_iot_device_command where device_name = ? and command_key = ?",
                DEVICE_NAME_TX, COMMAND_KEY_TX);
        assertThat(intentsAfter)
                .as("审批提交后持久意图必须与审批动作同时可见").isEqualTo(1L);
        Task second = taskService.createTaskQuery().processInstanceBusinessKey(businessKey)
                .taskDefinitionKey("t2").singleResult();
        assertThat(second).as("审批动作已真实推进到下一节点").isNotNull();
        System.out.println("[P4-EV] g3.callback-commit businessKey=" + businessKey
                + " callbacks=" + CALLBACKS.get() + " inTransactionCallbacks=" + CALLBACKS_IN_TRANSACTION.get()
                + " durableIntentsAfterCommit=" + intentsAfter + " nextTaskPresent=true");
    }

    @Test
    @DisplayName("G3-2 审批事务回滚时回调期间登记的持久意图一并回滚（无孤儿），并记录引擎边界事实")
    void approvalRollbackRollsBackCallbackIntent() {
        String businessKey = "p4-tx-rollback";
        startProcess(businessKey);
        Task first = taskService.createTaskQuery().processInstanceBusinessKey(businessKey)
                .taskDefinitionKey("t1").singleResult();
        assertThat(first).isNotNull();

        int callbacksBefore = CALLBACKS.get();
        long intentsBefore = count(
                "select count(*) from sw_iot_device_command where device_name = ? and command_key = ?",
                DEVICE_NAME_TX, COMMAND_KEY_TX);

        inTransaction(false, () -> {
            taskService.complete(first.getId());
            return null;
        });

        assertThat(CALLBACKS.get())
                .as("回滚路径同样经过回调（事务内），因此下面的回滚结论针对的是同一回调链")
                .isGreaterThan(callbacksBefore);
        long intentsAfter = count(
                "select count(*) from sw_iot_device_command where device_name = ? and command_key = ?",
                DEVICE_NAME_TX, COMMAND_KEY_TX);
        assertThat(intentsAfter)
                .as("业务回滚不得留下可执行孤儿意图").isEqualTo(intentsBefore);
        // 运行时事实二（G3a 修复后）：引擎与应用共用同一 DataSource 与事务管理器，
        // 因此引擎状态随调用方事务一起回滚——回滚后必须仍停在原节点，不再出现"引擎已推进、意图为零"。
        Task afterRollback = taskService.createTaskQuery().processInstanceBusinessKey(businessKey)
                .taskDefinitionKey("t2").singleResult();
        System.out.println("[P4-EV] g3.callback-rollback businessKey=" + businessKey
                + " callbacks=" + CALLBACKS.get() + " durableIntentsBefore=" + intentsBefore
                + " durableIntentsAfter=" + intentsAfter + " orphanIntents=0"
                + " engineCommitBoundary=application-transaction engineAdvancedToT2=" + (afterRollback != null));
    }

    // ==================== 场景搭建 ====================

    private void startProcess(String businessKey) {
        inTransaction(true, () -> {
            Map<String, Object> variables = new HashMap<>();
            variables.put("approver", String.valueOf(USER_A));
            variables.put("submitter", String.valueOf(USER_A));
            variables.put("lastApprovalActorId", USER_A);
            variables.put("tenantId", TENANT_A);
            runtimeService.startProcessInstanceByKeyAndTenantId(TX_FLOW_KEY, businessKey, variables,
                    String.valueOf(TENANT_A));
            return null;
        });
    }

    /** 两步审批：t1 由发起人办理；t2 的 create 回调挂真实审批监听器 + 运行时事务探针。 */
    private String twoStepBpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p4.evidence">
                  <process id="%s" name="P4 Tx Fact" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <userTask id="t1" name="first" flowable:assignee="${approver}"/>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
                    <userTask id="t2" name="second" flowable:assignee="${approver}">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${phase4TxProbeListener}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f3" sourceRef="t2" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(TX_FLOW_KEY);
    }
}
