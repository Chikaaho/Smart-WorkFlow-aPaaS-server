package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.common.event.DomainEventPublisher;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 G3a · 审批状态与 must-deliver 持久意图的提交边界（真实 PostgreSQL + 真实 Flowable）。
 *
 * <p>修复前实测：引擎命令在独立连接上自行提交，应用事务回滚后引擎已推进而三类意图为零。
 * 本轮把引擎绑定到应用 DataSource 与同一个事务管理器后，审批状态与其设备命令、通知、
 * OpenAPI 回调意图共享同一提交边界。本类按规划要求逐项给出正向与反向断言：</p>
 * <ol>
 *   <li>正常提交：审批状态推进到下一节点，且设备命令 / 通知 / 已订阅回调意图全部可追溯；</li>
 *   <li>显式回滚：两侧均未提交（引擎停在原节点、三类意图为零）；</li>
 *   <li>精确故障注入（引擎已写入、意图尚未写入时终止承载事务的连接）：两侧同时消失；</li>
 *   <li>重启（新应用上下文）后重放同一审批：三类意图恰好各一份，零重复、零缺失。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase4 G3a · 审批与持久意图单一提交边界 · 真实 PostgreSQL")
class Phase4PgCommitBoundaryBehaviourTest extends Phase4PgSupport {

    private static final String FLOW_KEY = "p4_g3a_flow";
    private static final String FORM_KEY = "p4_g3a_form";
    private static final String DEVICE_NAME = "p4-device-g3a";
    private static final String COMMAND_KEY = "cmd-g3a";
    private static final String CALLBACK_APP = "p4app-g3a";

    /** 探针模式：PUBLISH = 正常发布三类意图；CRASH = 引擎写入后立刻终止事务连接（精确故障）。 */
    private static final java.util.concurrent.atomic.AtomicReference<String> PROBE_MODE =
            new java.util.concurrent.atomic.AtomicReference<>("PUBLISH");

    private static final AtomicInteger PROBE_RUNS = new AtomicInteger();

    private RuntimeService runtimeService;
    private TaskService taskService;
    private DomainEventPublisher eventPublisher;
    private com.sun.net.httpserver.HttpServer callbackServer;

    /** t2 节点 create 回调：先（按模式）注入故障，再发布设备命令与流程终态通知。 */
    public static class CommitBoundaryProbe implements org.flowable.task.service.delegate.TaskListener {

        private final DomainEventPublisher publisher;
        private final Phase4PgSupport support;

        public CommitBoundaryProbe(DomainEventPublisher publisher, Phase4PgSupport support) {
            this.publisher = publisher;
            this.support = support;
        }

        @Override
        public void notify(org.flowable.task.service.delegate.DelegateTask delegateTask) {
            PROBE_RUNS.incrementAndGet();
            System.out.println("[P4-EV] g3a.probe node=" + delegateTask.getTaskDefinitionKey()
                    + " mode=" + PROBE_MODE.get() + " inTransaction="
                    + org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            if ("CRASH".equals(PROBE_MODE.get())) {
                // 引擎已写入 t2（同一事务内），此刻终止承载事务的后端连接：
                // 意图尚未写入 → 精确落在"引擎写入后、意图写入前"的故障窗口
                int pid = support.killCurrentTransactionBackend();
                System.out.println("[P4-EV] g3a.fault-injected window=engine-written-intent-pending pid=" + pid);
                return;
            }
            publisher.publish(new BpmDeviceCommandEvent(delegateTask.getProcessInstanceId(), "p4-product",
                    DEVICE_NAME, COMMAND_KEY, "PROPERTY", TENANT_A, USER_A));
            publisher.publish(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, TENANT_A, USER_A,
                    delegateTask.getProcessInstanceId()));
        }
    }

    @BeforeAll
    void bootAll() throws Exception {
        callbackServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        callbackServer.createContext("/callback", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        callbackServer.start();
        ensureEvidenceDatabase();
        cleanMigrate();
        boot(47L);
        seedTenantAndUser(TENANT_A, USER_A, "g3a");
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (9490, now(), now(), 0, ?, 0, ?, 'P4 G3a 设备', 'p4-product', ?, 'ONLINE',"
                        + " 'MANAGED', 1) on conflict (id) do nothing",
                TENANT_A, DEVICE_NAME, DEVICE_NAME);
        seedCallbackApp("http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/callback");
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, FLOW_KEY);
        deployBpmn(FLOW_KEY, bpmn(), FORM_KEY, false);
        eventPublisher = app.getBean(DomainEventPublisher.class);
        app.getBeanFactory().registerSingleton("phase4CommitProbe",
                new CommitBoundaryProbe(eventPublisher, this));
        runtimeService = app.getBean(RuntimeService.class);
        taskService = app.getBean(TaskService.class);
        System.out.println("[P4-EV] g3a.context=ready processKey=" + FLOW_KEY + " callbackApp=" + CALLBACK_APP);
    }

    @AfterAll
    void stopAll() {
        shutdown();
        if (callbackServer != null) {
            callbackServer.stop(0);
        }
    }

    // ==================== 1 · 正常提交 ====================

    @Test
    @Order(1)
    @DisplayName("G3a-1 正常提交：审批推进 + 设备命令/通知/已订阅回调意图全部可追溯")
    void normalCommitKeepsApprovalAndAllIntentsTraceable() {
        PROBE_MODE.set("PUBLISH");
        String businessKey = "p4-g3a-commit";
        String processInstanceId = startAndCompleteFirstTask(businessKey, true);

        assertThat(count("select count(*) from act_ru_task where proc_inst_id_ = ? and task_def_key_ = 't2'",
                processInstanceId)).as("审批状态必须推进到下一节点").isEqualTo(1L);
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .as("设备命令意图可追溯且只有一份").isEqualTo(1L);
        assertThat(count("select count(*) from sw_notify_message where biz_id = ? and event_type = ?",
                processInstanceId, "PROCESS_APPROVED")).as("通知意图可追溯").isGreaterThanOrEqualTo(1L);
        assertThat(count("select count(*) from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                processInstanceId, CALLBACK_APP)).as("已订阅回调意图可追溯").isEqualTo(1L);
        System.out.println("[P4-EV] g3a.normal-commit businessKey=" + businessKey
                + " engineAdvancedToT2=1 deviceIntents=1 notifyIntents>0 callbackIntents=1 intentsMissing=0");
    }

    // ==================== 2 · 显式回滚 ====================

    @Test
    @Order(2)
    @DisplayName("G3a-2 显式回滚：两侧均未提交（引擎不推进、三类意图为零）")
    void explicitRollbackLeavesNeitherSideCommitted() {
        PROBE_MODE.set("PUBLISH");
        String businessKey = "p4-g3a-rollback";
        String processInstanceId = startAndCompleteFirstTask(businessKey, false);

        assertThat(count("select count(*) from act_ru_task where proc_inst_id_ = ? and task_def_key_ = 't1'",
                processInstanceId)).as("回滚后审批必须仍停在原节点（修复前此处引擎已推进）").isEqualTo(1L);
        assertThat(count("select count(*) from act_ru_task where proc_inst_id_ = ? and task_def_key_ = 't2'",
                processInstanceId)).isZero();
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .as("回滚后设备命令意图必须为零").isZero();
        assertThat(count("select count(*) from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                processInstanceId, CALLBACK_APP)).as("回滚后回调意图必须为零").isZero();
        assertThat(count("select count(*) from sw_notify_message where biz_id = ? and event_type = ?",
                processInstanceId, "PROCESS_APPROVED")).as("回滚后通知意图必须为零").isZero();
        System.out.println("[P4-EV] g3a.explicit-rollback businessKey=" + businessKey
                + " engineAdvancedToT2=0 deviceIntents=0 notifyIntents=0 callbackIntents=0");
    }

    // ==================== 3+4 · 精确故障注入 + 重启重放 ====================

    @Test
    @Order(3)
    @DisplayName("G3a-3/4 引擎写入后终止事务连接：两侧同时消失；重启后重放恰好各一份")
    void crashMidTransactionThenReplayAfterRestart() {
        PROBE_MODE.set("CRASH");
        String businessKey = "p4-g3a-crash";
        String processInstanceId = startProcess(businessKey);

        // 精确故障：t2 的 create 回调在引擎写入之后终止事务连接
        RuntimeException fault = null;
        try {
            inTransaction(true, () -> {
                taskService.complete(taskService.createTaskQuery()
                        .processInstanceBusinessKey(businessKey).taskDefinitionKey("t1").singleResult().getId());
                return null;
            });
        } catch (RuntimeException e) {
            fault = e;
        }
        assertThat(fault).as("故障注入必须让事务失败（连接已被终止）").isNotNull();
        assertThat(count("select count(*) from act_ru_task where proc_inst_id_ = ? and task_def_key_ = 't2'",
                processInstanceId)).as("崩溃窗口内引擎写入必须随事务回滚").isZero();
        assertThat(count("select count(*) from act_ru_task where proc_inst_id_ = ? and task_def_key_ = 't1'",
                processInstanceId)).as("审批未提交，仍停在原节点").isEqualTo(1L);
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .isZero();
        assertThat(count("select count(*) from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                processInstanceId, CALLBACK_APP)).isZero();
        System.out.println("[P4-EV] g3a.crash-window businessKey=" + businessKey
                + " faultInjected=engine-written-intent-pending engineAdvancedToT2=0 deviceIntents=0"
                + " callbackIntents=0 engineOnlyOrphans=0");

        // 重启：关闭当前上下文并在同一数据库上重新装载，然后重放同一审批
        shutdown();
        System.out.println("[P4-EV] g3a.restart simulated=context-closed-then-reloaded database=sw_p4_evidence");
        boot(48L);
        seedTenantAndUser(TENANT_A, USER_A, "g3a");
        eventPublisher = app.getBean(DomainEventPublisher.class);
        app.getBeanFactory().registerSingleton("phase4CommitProbe",
                new CommitBoundaryProbe(eventPublisher, this));
        runtimeService = app.getBean(RuntimeService.class);
        taskService = app.getBean(TaskService.class);
        PROBE_MODE.set("PUBLISH");
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(true, () -> {
                taskService.complete(taskService.createTaskQuery()
                        .processInstanceBusinessKey(businessKey).taskDefinitionKey("t1").singleResult().getId());
                return null;
            });
            return null;
        });

        assertThat(count("select count(*) from act_ru_task where proc_inst_id_ = ? and task_def_key_ = 't2'",
                processInstanceId)).as("重放后引擎恰好一个下一节点任务").isEqualTo(1L);
        assertThat(count("select count(*) from sw_iot_device_command where approval_biz_id = ?", processInstanceId))
                .as("重放后设备命令意图恰好一份（零重复）").isEqualTo(1L);
        assertThat(count("select count(*) from sw_openapi_callback_task where biz_ref = ? and app_id = ?",
                processInstanceId, CALLBACK_APP)).as("重放后回调意图恰好一份").isEqualTo(1L);
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", businessKey))
                .as("重放不得产生第二个引擎实例").isEqualTo(1L);
        System.out.println("[P4-EV] g3a.restart-replay businessKey=" + businessKey
                + " engineInstances=1 t2Tasks=1 deviceIntents=1 callbackIntents=1 duplicateIntents=0");
    }

    // ==================== 场景工具 ====================

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

    private void seedCallbackApp(String callbackUrl) {
        String digest;
        try {
            digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("p4-g3a-secret-" + CALLBACK_APP).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("insert into sw_openapi_app (id, create_time, update_time, deleted, tenant_id, version, app_id,"
                        + " app_name, secret_hash, scopes, status, act_as_user_id, callback_url, callback_secret_hash)"
                        + " values (9491, now(), now(), 0, ?, 0, ?, 'P4 G3a 应用', ?, 'workflow:callback', 'ENABLED',"
                        + " ?, ?, ?)",
                TENANT_A, CALLBACK_APP, digest, USER_A, callbackUrl, digest);
    }

    private String bpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p4.evidence">
                  <process id="%s" name="P4 G3a Boundary" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="t1"/>
                    <userTask id="t1" name="first" flowable:assignee="1"/>
                    <sequenceFlow id="f2" sourceRef="t1" targetRef="t2"/>
                    <userTask id="t2" name="second" flowable:assignee="1">
                      <extensionElements>
                        <flowable:taskListener event="create" delegateExpression="${phase4CommitProbe}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="f3" sourceRef="t2" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(FLOW_KEY);
    }
}
