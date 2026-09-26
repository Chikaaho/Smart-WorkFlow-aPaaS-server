package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.ScheduledFlowCommandHandler;
import com.sw.ck.form.service.FormSubmitService;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotScript;
import com.sw.ck.iot.entity.IotScriptVersion;
import com.sw.ck.iot.job.ProcessTriggerRecoveryJob;
import com.sw.ck.iot.script.ScriptEngineService;
import com.sw.ck.iot.service.RuleEngineService;
import com.sw.ck.job.scheduler.SwJobBean;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.process.listener.BpmNotifyIntentRecorder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 G2 · 流程类可靠接缝的端到端内部行为证据（真实 PostgreSQL + 真实 Flowable 引擎）。
 *
 * <p>覆盖接缝：Scheduled FLOW（{@code SwJobBean} → {@code sw_bpm_command} → 调度器 → 真实流程实例）、
 * IoT 规则触发（{@code RuleEngineService} → {@code sw_iot_process_trigger} → 恢复调度 → 监听器 → 实例）、
 * IoT 脚本触发（{@code ScriptEngineService} → 持久触发行）。每个接缝都断言：持久意图、
 * 恢复执行、幂等结果、失败/终态与业务状态（{@code sw_bpm_instance} + Flowable 运行时实例）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase4 G2 · 流程类接缝端到端行为 · 真实 PostgreSQL + 真实引擎")
class Phase4PgFlowSeamBehaviourTest extends Phase4PgSupport {

    private static final String FLOW_KEY = "p4_sched_flow";
    private static final String IOT_FLOW_KEY = "p4_iot_flow";
    private static final String UNBOUND_KEY = "p4_unbound_flow";
    private static final String FORM_KEY = "p4_flow_form";
    private static final String PLAIN_FORM_KEY = "p4_plain_form";
    private static final long DEVICE_ID = 9401L;
    private static final long RULE_ID = 9402L;
    private static final long SCRIPT_ID = 9403L;
    private static final long JOB_ID = 9410L;

    private SwJobBean swJobBean;
    private BpmCommandQueue queue;
    private ScheduledFlowCommandHandler scheduledFlowHandler;
    private RuleEngineService ruleEngineService;
    private ScriptEngineService scriptEngineService;
    private ProcessTriggerRecoveryJob triggerRecoveryJob;
    private FormSubmitService formSubmitService;
    private BpmNotifyIntentRecorder notifyRecorder;
    private NotifyFacade notifyFacade;

    /** 在真实上下文里登记的内存事件监听器：用于证明"不再依赖内存事件兜底"。 */
    private final AtomicInteger inMemoryFormSubmittedEvents = new AtomicInteger();

    /** 本类需要观测真实命令调度消费，故把轮询间隔调快（生产默认 500ms 量级）。 */
    @Override
    protected Map<String, Object> properties() {
        Map<String, Object> props = super.properties();
        props.put("sw.bpm.command.poll-interval-millis", "300");
        props.put("sw.bpm.command.p0-poll-interval-millis", "300");
        return props;
    }

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrate();
        boot(42L);
        seedTenantAndUser(TENANT_A, USER_A, "flow");
        swJobBean = app.getBean(SwJobBean.class);
        queue = app.getBean(BpmCommandQueue.class);
        scheduledFlowHandler = app.getBean(ScheduledFlowCommandHandler.class);
        ruleEngineService = app.getBean(RuleEngineService.class);
        scriptEngineService = app.getBean(ScriptEngineService.class);
        triggerRecoveryJob = app.getBean(ProcessTriggerRecoveryJob.class);
        formSubmitService = app.getBean(FormSubmitService.class);
        notifyRecorder = app.getBean(BpmNotifyIntentRecorder.class);
        notifyFacade = app.getBean(NotifyFacade.class);
        app.addApplicationListener(new org.springframework.context.ApplicationListener<
                org.springframework.context.ApplicationEvent>() {
            @Override
            public void onApplicationEvent(org.springframework.context.ApplicationEvent event) {
                // FormSubmittedEvent 是普通 POJO，发布时由 Spring 包成载荷事件
                if (event instanceof org.springframework.context.PayloadApplicationEvent<?> payload
                        && payload.getPayload() instanceof com.sw.ck.form.api.event.FormSubmittedEvent) {
                    inMemoryFormSubmittedEvents.incrementAndGet();
                }
            }
        });
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            publishForm(PLAIN_FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, FLOW_KEY);
        deployAndRegister(FLOW_KEY, FORM_KEY, false, "${approver}");
        deployAndRegister(IOT_FLOW_KEY, FORM_KEY, true, "1");
        System.out.println("[P4-EV] g2.flow-seam-context=ready processKeys=" + FLOW_KEY + "," + IOT_FLOW_KEY
                + " boundForm=" + FORM_KEY);
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    // ==================== 接缝 1 · Scheduled FLOW ====================

    @Test
    @Order(1)
    @DisplayName("Scheduled FLOW：定时任务提交后形成持久意图，调度器消费后真实发起流程实例")
    void scheduledFlowCreatesDurableIntentAndRealInstance() throws Exception {
        long fireBucket = System.currentTimeMillis();
        seedFlowJob(JOB_ID, FLOW_KEY, "p4-flow-job");
        JobExecutionContext context = jobContext(JOB_ID);

        swJobBean.execute(context);

        String commandKeyPrefix = "SCHEDULED_FLOW:" + JOB_ID + ":";
        assertThat(count("select count(*) from sw_bpm_command where command_key like ?", commandKeyPrefix + "%"))
                .isEqualTo(1L);
        assertThat(text("select exec_status from sw_job_log where job_id = ? order by start_time desc limit 1", JOB_ID))
                .isEqualTo("SUCCESS");
        assertThat(text("select result_msg from sw_job_log where job_id = ? order by start_time desc limit 1", JOB_ID))
                .contains("已受理流程启动意图");

        await("scheduled-flow-command-completed", () -> count(
                "select count(*) from sw_bpm_command where command_key like ? and status = 'COMPLETED'",
                commandKeyPrefix + "%") == 1L, 30_000);

        String businessKey = text("select business_key from sw_bpm_instance where process_def_key = ?", FLOW_KEY);
        String processInstanceId = text("select process_instance_id from sw_bpm_instance where process_def_key = ?",
                FLOW_KEY);
        assertThat(businessKey).startsWith("sched-");
        assertThat(processInstanceId).isNotBlank();
        assertThat(count("select count(*) from act_hi_procinst where proc_inst_id_ = ?", processInstanceId))
                .as("真实 Flowable 引擎必须存在对应流程实例").isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY)).isEqualTo(1L);
        System.out.println("[P4-EV] g2.scheduled-flow durableIntent=1 commandStatus=COMPLETED businessKey=" + businessKey
                + " engineInstance=1 jobLog=SUCCESS elapsedMs=" + (System.currentTimeMillis() - fireBucket));
    }

    @Test
    @Order(2)
    @DisplayName("Scheduled FLOW：同一命令重放（重复领取/迟到完成）不产生第二条流程实例")
    void scheduledFlowReplayIsIdempotent() {
        String commandKey = text("select command_key from sw_bpm_command where command_key like 'SCHEDULED_FLOW:%'"
                + " order by create_time desc limit 1");
        assertThat(commandKey).isNotNull();
        CommandEnvelope envelope = asTenant(TENANT_A, USER_A, () -> queue.findByKey(TENANT_A, commandKey))
                .orElseThrow();
        long instanceCountBefore = count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY);
        String result = asTenant(TENANT_A, USER_A, () -> {
            try {
                return scheduledFlowHandler.handle(envelope);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(result).contains("SKIP_DUPLICATE");
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY))
                .isEqualTo(instanceCountBefore);
        assertThat(count("select count(*) from act_hi_procinst where proc_def_id_ like ?", "%" + FLOW_KEY + "%"))
                .isEqualTo(1L);
        System.out.println("[P4-EV] g2.scheduled-flow-replay commandKey=" + commandKey
                + " handlerResult=" + result + " instances=" + instanceCountBefore + " duplicateInstances=0");
    }

    @Test
    @Order(3)
    @DisplayName("Scheduled FLOW：无启用绑定时 Job 日志记为 FAILED，禁止静默成功")
    void scheduledFlowWithoutBindingFailsLoudly() throws Exception {
        long jobId = JOB_ID + 1;
        seedFlowJob(jobId, UNBOUND_KEY, "p4-flow-job-unbound");
        swJobBean.execute(jobContext(jobId));
        assertThat(text("select exec_status from sw_job_log where job_id = ? order by start_time desc limit 1", jobId))
                .isEqualTo("FAILED");
        assertThat(text("select result_msg from sw_job_log where job_id = ? order by start_time desc limit 1", jobId))
                .contains("失败");
        assertThat(text("select exception_stack from sw_job_log where job_id = ? order by start_time desc limit 1", jobId))
                .contains("ref=");
        assertThat(count("select count(*) from sw_bpm_command where command_key like ?", "SCHEDULED_FLOW:" + jobId + ":%"))
                .as("未受理不得留下可执行意图").isZero();
        System.out.println("[P4-EV] g2.scheduled-flow-no-binding jobId=" + jobId
                + " jobLog=FAILED stageMessage=explicit-failure durableIntent=0");
    }

    // ==================== 接缝 2 · IoT 规则 ====================

    @Test
    @Order(4)
    @DisplayName("IoT 规则：触发形成可恢复持久意图，恢复调度续跑并回写业务状态")
    void iotRuleTriggerIsRecoverableAndIdempotent() {
        seedDeviceAndRule();
        IotDevice device = new IotDevice();
        device.setId(DEVICE_ID);
        device.setTenantId(TENANT_A);
        device.setDeviceKey("p4-device");
        device.setName("P4 设备");

        // 生产口径：规则评估发生在有事务的消息摄入路径内，事件在提交后驱动监听器
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(true, () -> {
                ruleEngineService.onProperty(device, "temp", "35", "20", LocalDateTime.now(), "dedup-k1");
                return null;
            });
            return null;
        });

        String idempotentKey = text("select idempotent_key from sw_iot_process_trigger where rule_id = ?", RULE_ID);
        assertThat(idempotentKey).contains("dedup-k1");
        assertThat(text("select process_template_key from sw_iot_process_trigger where rule_id = ?", RULE_ID))
                .as("规则触发行必须携带恢复身份，否则崩溃后不可恢复").isEqualTo(IOT_FLOW_KEY);
        assertThat(text("select trigger_source from sw_iot_process_trigger where rule_id = ?", RULE_ID))
                .isEqualTo("RULE");
        assertThat(count("select count(*) from sw_iot_process_trigger where rule_id = ?", RULE_ID)).isEqualTo(1L);

        await("iot-rule-trigger-succeeded", () -> "SUCCESS".equals(
                text("select status from sw_iot_process_trigger where rule_id = ?", RULE_ID)), 30_000);
        String instanceId = text("select process_instance_id from sw_iot_process_trigger where rule_id = ?", RULE_ID);
        assertThat(instanceId).isNotBlank();
        assertThat(count("select count(*) from act_hi_procinst where proc_inst_id_ = ?", instanceId)).isEqualTo(1L);

        // 重复消息（同 dedupKey）：不得产生第二条触发行/实例
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(true, () -> {
                ruleEngineService.onProperty(device, "temp", "36", "35", LocalDateTime.now(), "dedup-k1");
                return null;
            });
            return null;
        });
        assertThat(count("select count(*) from sw_iot_process_trigger where rule_id = ?", RULE_ID)).isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-" + text(
                "select id from sw_iot_process_trigger where rule_id = ?", RULE_ID))).isEqualTo(1L);
        System.out.println("[P4-EV] g2.iot-rule triggerKey=" + idempotentKey + " recoveryIdentity=" + IOT_FLOW_KEY
                + " status=SUCCESS engineInstance=1 duplicateDeliveryInstances=1");
    }

    @Test
    @Order(5)
    @DisplayName("IoT 触发：提交后进程退出的遗留 PENDING 触发被恢复调度续跑，且终态不被重复发起")
    void iotTriggerCrashRecoveryResumesWithoutDuplicateInstance() {
        long crashedRuleId = RULE_ID + 1;
        jdbc.update("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id, version,"
                        + " rule_id, device_id, idempotent_key, status, trigger_time, retry_count, process_template_key,"
                        + " trigger_source, configured_by, form_snapshot)"
                        + " values (?, now(), now(), 0, ?, 0, ?, ?, ?, 'PENDING', now(), 0, ?, 'RULE', ?, '{}')",
                94011L, TENANT_A, crashedRuleId, DEVICE_ID, "crash-rule-1", IOT_FLOW_KEY, USER_A);

        triggerRecoveryJob.recoverDue();

        await("iot-trigger-recovery-succeeded", () -> "SUCCESS".equals(
                text("select status from sw_iot_process_trigger where id = ?", 94011L)), 30_000);
        String instanceId = text("select process_instance_id from sw_iot_process_trigger where id = ?", 94011L);
        assertThat(instanceId).isNotBlank();
        int retryCount = Integer.parseInt(text("select retry_count from sw_iot_process_trigger where id = ?", 94011L));
        assertThat(retryCount).isGreaterThanOrEqualTo(1);
        long instancesForTrigger = count("select count(*) from sw_bpm_instance where business_key = ?", "iot-94011");
        assertThat(instancesForTrigger).isEqualTo(1L);

        // 恢复轮再次运行：终态触发不得被重新发起
        triggerRecoveryJob.recoverDue();
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-94011")).isEqualTo(1L);
        assertThat(count("select count(*) from act_hi_procinst where proc_inst_id_ = ?", instanceId)).isEqualTo(1L);
        System.out.println("[P4-EV] g2.iot-crash-recovery triggerId=94011 retryCount=" + retryCount
                + " status=SUCCESS instances=1 secondRunInstances=1");
    }

    @Test
    @Order(6)
    @DisplayName("IoT 触发：消费者崩溃留下的 PROCESSING 触发被租约回收后续跑")
    void iotTriggerStaleProcessingIsReclaimed() {
        long staleRuleId = RULE_ID + 2;
        jdbc.update("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id, version,"
                        + " rule_id, device_id, idempotent_key, status, trigger_time, retry_count, process_template_key,"
                        + " trigger_source, configured_by, form_snapshot)"
                        + " values (?, now(), now(), 0, ?, 0, ?, ?, ?, 'PROCESSING', now(), 1, ?, 'RULE', ?, '{}')",
                94012L, TENANT_A, staleRuleId, DEVICE_ID, "stale-rule-1", IOT_FLOW_KEY, USER_A);
        // 模拟消费者崩溃：领取时间已超出租约阈值
        jdbc.update("update sw_iot_process_trigger set update_time = ? where id = ?",
                LocalDateTime.now().minusMinutes(30), 94012L);

        triggerRecoveryJob.recoverDue();

        await("iot-stale-processing-recovered", () -> "SUCCESS".equals(
                text("select status from sw_iot_process_trigger where id = ?", 94012L)), 30_000);
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-94012")).isEqualTo(1L);
        System.out.println("[P4-EV] g2.iot-stale-reclaim triggerId=94012 reclaimedTo=PENDING then=SUCCESS instances=1");
    }

    // ==================== 接缝 3 · IoT 脚本 ====================

    @Test
    @Order(7)
    @DisplayName("IoT 脚本：真实运行登记持久触发意图，恢复调度续跑；试运行不产生副作用")
    void iotScriptRunPersistsIntentAndDryRunDoesNot() {
        IotScript script = script();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("threshold", 35);
        String scriptKey = "p4-script-key-1";

        asTenant(TENANT_A, USER_A, () -> {
            scriptEngineService.realRun(script, scriptVersion(), TENANT_A, USER_A, "MANUAL", "p4-run-1",
                    DEVICE_ID, input, scriptKey);
            return null;
        });

        String idempotentKey = text("select idempotent_key from sw_iot_process_trigger where script_id = ?", SCRIPT_ID);
        assertThat(idempotentKey).isEqualTo(scriptKey);
        assertThat(text("select process_template_key from sw_iot_process_trigger where script_id = ?", SCRIPT_ID))
                .as("脚本路径必须登记与规则路径同强度的恢复身份").isEqualTo(IOT_FLOW_KEY);
        assertThat(text("select status from sw_iot_process_trigger where script_id = ?", SCRIPT_ID))
                .as("脚本路径无事务，意图即事实源，恢复调度负责续跑").isEqualTo("PENDING");

        long before = count("select count(*) from sw_iot_process_trigger where script_id = ?", SCRIPT_ID);
        asTenant(TENANT_A, USER_A, () -> {
            scriptEngineService.dryRun(script, scriptVersion(), TENANT_A, USER_A, input);
            return null;
        });
        assertThat(count("select count(*) from sw_iot_process_trigger where script_id = ?", SCRIPT_ID))
                .as("试运行不得登记意图").isEqualTo(before);

        triggerRecoveryJob.recoverDue();
        await("iot-script-trigger-succeeded", () -> "SUCCESS".equals(
                text("select status from sw_iot_process_trigger where script_id = ?", SCRIPT_ID)), 30_000);
        String instanceId = text("select process_instance_id from sw_iot_process_trigger where script_id = ?", SCRIPT_ID);
        assertThat(instanceId).isNotBlank();
        System.out.println("[P4-EV] g2.iot-script scriptKey=" + scriptKey + " durableIntent=PENDING"
                + " dryRunIntentRows=" + before + " recovered=SUCCESS engineInstance=1");
    }

    // ==================== 接缝 4 · 表单提交（兜底退役 + 权威命令路径） ====================

    @Test
    @Order(8)
    @DisplayName("表单提交：权威持久命令路径受理，且不再发布无监听者的内存兜底事件")
    void formSubmitUsesDurableCommandPathWithoutInMemoryFallback() {
        int capturedBefore = inMemoryFormSubmittedEvents.get();
        String recordId = asTenant(TENANT_A, USER_A, () ->
                formSubmitService.submitForm(FORM_KEY, Map.of("name", "p4-record"), null, null, null));
        assertThat(recordId).isNotBlank();
        assertThat(count("select count(*) from sw_bpm_command where command_key = ?",
                "FLOW_START:" + recordId)).isEqualTo(1L);
        assertThat(inMemoryFormSubmittedEvents.get())
                .as("FormSubmittedEvent 兜底发布已退役，提交期间不得产生该内存事件")
                .isEqualTo(capturedBefore);

        // 无绑定的表单：权威路径给出合法 no-op，仍不得退回内存事件
        String plainRecordId = asTenant(TENANT_A, USER_A, () ->
                formSubmitService.submitForm(PLAIN_FORM_KEY, Map.of("name", "p4-plain"), null, null, null));
        assertThat(count("select count(*) from sw_bpm_command where command_key = ?",
                "FLOW_START:" + plainRecordId)).isZero();
        assertThat(inMemoryFormSubmittedEvents.get()).isEqualTo(capturedBefore);
        System.out.println("[P4-EV] g2.form-submit recordId=" + recordId
                + " durableCommand=1 inMemoryFallbackEvents=" + (inMemoryFormSubmittedEvents.get() - capturedBefore)
                + " unboundFormIntentRows=0");
    }

    // ==================== 接缝 5 · 通知意图（审批事件） ====================

    @Test
    @Order(9)
    @DisplayName("通知意图：审批通知在业务事务内登记可投递意图，重复发布只产生一条")
    void notifyIntentIsRecordedInBusinessTransaction() {
        String bizId = "p4-notify-biz-1";
        BpmNotifyEvent event = new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, TENANT_A, USER_A, bizId);
        int recorded = asTenant(TENANT_A, USER_A, () -> {
            inTransaction(true, () -> {
                notifyRecorder.record(event);
                return null;
            });
            return notifyRecorder.record(event);
        });
        assertThat(recorded).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from sw_notify_message where biz_id = ? and event_type = ?",
                bizId, "PROCESS_APPROVED")).isEqualTo(1L);

        String channel = text("select channel from sw_notify_message where biz_id = ?", bizId);
        System.out.println("[P4-EV] g2.notify-intent bizId=" + bizId + " rows=1 channel=" + channel
                + " duplicatePublishRows=1");

        // 回滚：审批事务未提交时不得留下可投递意图
        String rolledBackBizId = "p4-notify-biz-rollback";
        asTenant(TENANT_A, USER_A, () -> {
            inTransaction(false, () -> {
                notifyRecorder.record(new BpmNotifyEvent(BpmNotifyTrigger.PROCESS_APPROVED, USER_A, TENANT_A,
                        USER_A, rolledBackBizId));
                return null;
            });
            return null;
        });
        assertThat(count("select count(*) from sw_notify_message where biz_id = ?", rolledBackBizId)).isZero();
        System.out.println("[P4-EV] g2.notify-intent-rollback bizId=" + rolledBackBizId + " intentRowsAfterRollback=0");

        // 站内信加速路径：提交后加速投递，不产生第二条业务通知
        NotifySendRequest request = NotifySendRequest.builder()
                .channel(NotifyChannel.IN_APP).recipientId(USER_A).title("审批通过").content("已通过")
                .bizId(bizId).tenantId(TENANT_A).eventType("PROCESS_APPROVED").occurrenceNo(1L)
                .build();
        asTenant(TENANT_A, USER_A, () -> notifyFacade.send(request));
        assertThat(count("select count(*) from sw_notify_message where biz_id = ?", bizId)).isEqualTo(1L);
        System.out.println("[P4-EV] g2.notify-accelerate bizId=" + bizId + " rowsAfterAccelerate=1");
    }

    // ==================== 场景搭建 ====================

    private void deployAndRegister(String processKey, String formKey, boolean iotAccess, String assignee) {
        org.flowable.engine.RepositoryService repositoryService =
                app.getBean(org.flowable.engine.RepositoryService.class);
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p4.evidence">
                  <process id="%s" name="P4 %s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="approve" flowable:assignee="%s"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, processKey, assignee);
        var deployment = repositoryService.createDeployment()
                .name("p4-" + processKey)
                .tenantId(String.valueOf(TENANT_A))
                .addString(processKey + ".bpmn20.xml", bpmn)
                .deploy();
        String definitionId = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult().getId();
        jdbc.update("insert into sw_bpm_process_def (id, create_time, update_time, deleted, tenant_id, version,"
                        + " process_key, name, form_key, def_version, status, deployment_id, process_definition_id,"
                        + " published_version, iot_access_enabled)"
                        + " values (?, now(), now(), 0, ?, 0, ?, ?, ?, 1, 'PUBLISHED', ?, ?, 1, ?)",
                9430L + Math.abs(processKey.hashCode() % 1000), TENANT_A, processKey, "P4 " + processKey, formKey,
                deployment.getId(), definitionId, iotAccess);
        System.out.println("[P4-EV] g2.bpmn-deployed processKey=" + processKey
                + " definitionIdPresent=true iotAccess=" + iotAccess + " tenant=" + TENANT_A);
    }

    private void seedFlowJob(long jobId, String flowDefKey, String name) {
        jdbc.update("insert into sw_job_info (id, create_time, update_time, deleted, tenant_id, version, job_name,"
                        + " job_group, job_type, cron_expression, status, concurrent, misfire_policy, bean_name,"
                        + " bean_params, flow_def_key, form_data, create_by)"
                        + " values (?, now(), now(), 0, ?, 0, ?, 'DEFAULT', 'FLOW', '0 0 0 * * ?', 'NORMAL', 1, 0,"
                        + " null, null, ?, ?, ?)",
                jobId, TENANT_A, name, flowDefKey, "{\"name\":\"定时发起\"}", USER_A);
    }

    private void seedDeviceAndRule() {
        jdbc.update("insert into sw_iot_device (id, create_time, update_time, deleted, tenant_id, version, device_key,"
                        + " name, product_id, device_name, status, manage_status, process_access_enabled)"
                        + " values (?, now(), now(), 0, ?, 0, 'p4-device', 'P4 设备', 'p4-prod', 'p4-device', 'ONLINE',"
                        + " 'MANAGED', 1) on conflict (id) do nothing",
                DEVICE_ID, TENANT_A);
        jdbc.update("insert into sw_iot_event_rule (id, create_time, update_time, deleted, tenant_id, version, code,"
                        + " name, device_id, rule_type, condition_json, debounce_ms, cooldown_ms, continuous_count,"
                        + " process_template_key, process_enabled, status, rule_version)"
                        + " values (?, now(), now(), 0, ?, 0, 'p4-rule', 'P4 规则', ?, 'THRESHOLD',"
                        + " '{\"propertyId\":\"temp\",\"op\":\"GT\",\"threshold\":30}', 0, 0, 1, ?, 1, 'PUBLISHED', 1)",
                RULE_ID, TENANT_A, DEVICE_ID, IOT_FLOW_KEY);
    }

    private IotScript script() {
        IotScript script = new IotScript();
        script.setId(SCRIPT_ID);
        script.setCode("p4-script");
        script.setName("P4 脚本");
        script.setLanguage("JS");
        script.setTimeoutMs(5000);
        return script;
    }

    private IotScriptVersion scriptVersion() {
        IotScriptVersion version = new IotScriptVersion();
        version.setScriptId(SCRIPT_ID);
        version.setScriptVersion(1);
        version.setSourceCode("function handler(input){ return fun_startProcess('" + IOT_FLOW_KEY
                + "', {threshold: input.threshold}, {idempotentKey: 'p4-script-key-1'}); }");
        return version;
    }

    private JobExecutionContext jobContext(long jobId) throws Exception {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put("jobId", jobId);
        dataMap.put("triggerType", "AUTO");
        when(context.getMergedJobDataMap()).thenReturn(dataMap);
        when(context.getNextFireTime()).thenReturn(null);
        // Spring 的 QuartzJobBean 入口会读取 SchedulerContext（真实调度器里恒存在）
        org.quartz.Scheduler scheduler = mock(org.quartz.Scheduler.class);
        when(scheduler.getContext()).thenReturn(new org.quartz.SchedulerContext());
        when(context.getScheduler()).thenReturn(scheduler);
        return context;
    }
}
