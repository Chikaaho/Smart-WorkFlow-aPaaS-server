package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.ScheduledFlowCommandHandler;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.job.ProcessTriggerRecoveryJob;
import com.sw.ck.job.scheduler.SwJobBean;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
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
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 G3b · "引擎实例已创建、业务实例尚未落库"崩溃窗口的真实恢复证据（真实 PostgreSQL + 真实 Flowable）。
 *
 * <p>故障点由流程 start 事件的执行监听器注入：此刻引擎已写入流程实例，业务实例记录尚未保存，
 * 正是规划要求的精确窗口。注入方式是终止承载该事务的 PostgreSQL 后端连接（真实崩溃等价物），
 * 随后按各自的生产恢复路径续跑，断言三方最终一致：恰好 1 个引擎实例、1 个业务实例、1 个可审计
 * 任务终态，且身份（租户 / 业务键 / 命令 / 触发行 / 实例 ID）关联一致。</p>
 *
 * <p>覆盖共享该窗口的 Phase 4 启动入口：Scheduled FLOW（命令队列重试）、IoT 规则（触发恢复调度）、
 * IoT 脚本（同一触发行 + 同一恢复调度）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Phase4 G3b · 引擎发起与业务实例记录崩溃窗口 · 真实 PostgreSQL")
class Phase4PgStartWindowCrashTest extends Phase4PgSupport {

    private static final String FLOW_KEY = "p4_g3b_flow";
    private static final String IOT_KEY = "p4_g3b_iot";
    private static final String FORM_KEY = "p4_g3b_form";
    private static final long JOB_ID = 9495L;

    /** 故障注入开关：仅在需要注入的那一次引擎发起上触发。 */
    private static final AtomicBoolean INJECTOR_ARMED = new AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger INJECTIONS =
            new java.util.concurrent.atomic.AtomicInteger();

    private SwJobBean swJobBean;
    private BpmCommandQueue queue;
    private ScheduledFlowCommandHandler scheduledFlowHandler;
    private ProcessTriggerRecoveryJob triggerRecoveryJob;
    private IotProcessTriggerFacade triggerFacade;

    /** 流程 start 事件监听器：引擎已创建实例、业务实例尚未落库时注入崩溃。 */
    public static class StartWindowInjector implements ExecutionListener {

        private final Phase4PgSupport support;

        public StartWindowInjector(Phase4PgSupport support) {
            this.support = support;
        }

        @Override
        public void notify(DelegateExecution execution) {
            boolean inject = INJECTOR_ARMED.compareAndSet(true, false);
            System.out.println("[P4-EV] g3b.start-window reached processKey=" + execution.getProcessDefinitionId()
                    + " inject=" + inject
                    + " businessInstanceSaved=false");
            if (inject) {
                int pid = support.killCurrentTransactionBackend();
                INJECTIONS.incrementAndGet();
                System.out.println("[P4-EV] g3b.fault-injected window=engine-instance-created-business-not-saved pid="
                        + pid);
            }
        }
    }

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrate();
        boot(49L, Map.of("sw.bpm.command.poll-interval-millis", "300",
                "sw.bpm.command.p0-poll-interval-millis", "300",
                "sw.bpm.command.backoff-millis", "60000"));
        seedTenantAndUser(TENANT_A, USER_A, "g3b");
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, FLOW_KEY);
        deployBpmn(FLOW_KEY, bpmn(FLOW_KEY), FORM_KEY, false);
        deployBpmn(IOT_KEY, bpmn(IOT_KEY), FORM_KEY, true);
        app.getBeanFactory().registerSingleton("phase4StartWindowInjector", new StartWindowInjector(this));
        swJobBean = app.getBean(SwJobBean.class);
        queue = app.getBean(BpmCommandQueue.class);
        scheduledFlowHandler = app.getBean(ScheduledFlowCommandHandler.class);
        triggerRecoveryJob = app.getBean(ProcessTriggerRecoveryJob.class);
        triggerFacade = app.getBean(IotProcessTriggerFacade.class);
        System.out.println("[P4-EV] g3b.context=ready flowKey=" + FLOW_KEY + " iotKey=" + IOT_KEY
                + " injector=start-event-execution-listener");
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    // ==================== 入口 1 · Scheduled FLOW ====================

    @Test
    @Order(1)
    @DisplayName("G3b-1 Scheduled FLOW：窗口内崩溃后命令重试，三方最终一致且零重复")
    void scheduledFlowWindowCrashRecoversToExactlyOnce() {
        seedFlowJob();
        swJobBeanInjectingFirstStart();
        String commandKey = text("select command_key from sw_bpm_command where command_key like ?",
                "SCHEDULED_FLOW:" + JOB_ID + ":%");
        assertThat(commandKey).isNotNull();

        // 故障后：无引擎实例、无业务实例（窗口内两侧同时消失）
        await("g3b-flow-fault-observed", () -> INJECTIONS.get() >= 1, 30_000);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY))
                .as("窗口内崩溃不得留下业务实例").isZero();
        assertThat(count("select count(*) from act_hi_procinst where proc_def_id_ like ?", "%" + FLOW_KEY + "%"))
                .as("窗口内崩溃不得留下引擎实例（engine-only 孤儿为零）").isZero();
        System.out.println("[P4-EV] g3b.flow-window-crash commandKey=" + commandKey
                + " engineInstances=0 businessInstances=0 engineOnlyOrphans=0");

        // 生产恢复路径：退避到点后由命令调度重试 → 最终恰好一次
        // （退避在测试里显式放长，使窗口状态可确定性断言；此处把到期时间提前以驱动重试）
        jdbc.update("update sw_bpm_command set next_retry_at = ? where command_key = ?",
                LocalDateTime.now().minusSeconds(1), commandKey);
        await("g3b-flow-recovered", () -> count("select count(*) from sw_bpm_command where command_key = ? and status = ?",
                commandKey, CommandStatusEnum.COMPLETED.getCode()) == 1L, 90_000);
        String businessKey = text("select business_key from sw_bpm_instance where process_def_key = ?", FLOW_KEY);
        String processInstanceId = text("select process_instance_id from sw_bpm_instance where process_def_key = ?",
                FLOW_KEY);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY)).isEqualTo(1L);
        assertThat(count("select count(*) from act_hi_procinst where proc_inst_id_ = ?", processInstanceId))
                .isEqualTo(1L);
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", businessKey))
                .isEqualTo(1L);
        assertThat(businessKey).startsWith("sched-");

        // 旧尝试不得覆盖新恢复结果：重放同一命令返回幂等跳过且实例数不变
        CommandEnvelope envelope = asTenant(TENANT_A, USER_A, () -> queue.findByKey(TENANT_A, commandKey))
                .orElseThrow();
        String replay = asTenant(TENANT_A, USER_A, () -> {
            try {
                return scheduledFlowHandler.handle(envelope);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(replay).contains("SKIP_DUPLICATE");
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY))
                .as("重放不得产生第二个业务实例").isEqualTo(1L);
        System.out.println("[P4-EV] g3b.flow-recovered businessKey=" + businessKey
                + " commandStatus=COMPLETED engineInstances=1 businessInstances=1 replay=" + replay
                + " retryCount=" + text("select retry_count from sw_bpm_command where command_key = ?", commandKey));
    }

    // ==================== 入口 2 · IoT 规则 ====================

    @Test
    @Order(2)
    @DisplayName("G3b-2 IoT 规则：窗口内崩溃后触发恢复调度续跑，engine/business/trigger 三方为 1 且身份一致")
    void iotRuleWindowCrashRecoversToExactlyOnce() {
        long triggerId = 949501L;
        String idempotentKey = "g3b-rule-key-1";
        insertTrigger(triggerId, idempotentKey, "RULE", null);

        INJECTOR_ARMED.set(true);
        triggerRecoveryJob.recoverDue();
        await("g3b-iot-fault-observed", () -> INJECTIONS.get() >= 2, 30_000);
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-" + triggerId))
                .as("窗口内崩溃不得留下业务实例").isZero();
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", idempotentKey))
                .as("窗口内崩溃不得留下引擎实例").isZero();
        assertThat(text("select status from sw_iot_process_trigger where id = ?", triggerId))
                .as("失败必须留下可审计终态而不是静默").isEqualTo("FAILED");
        System.out.println("[P4-EV] g3b.iot-rule-window-crash triggerId=" + triggerId
                + " triggerStatus=FAILED engineInstances=0 businessInstances=0 engineOnlyOrphans=0");

        // 生产恢复路径：到期后由恢复调度重新认领并重投
        jdbc.update("update sw_iot_process_trigger set next_retry_time = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), triggerId);
        triggerRecoveryJob.recoverDue();
        await("g3b-iot-recovered", () -> "SUCCESS".equals(
                text("select status from sw_iot_process_trigger where id = ?", triggerId)), 60_000);
        String processInstanceId = text("select process_instance_id from sw_iot_process_trigger where id = ?", triggerId);
        assertThat(processInstanceId).isNotBlank();
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", idempotentKey))
                .as("引擎实例恰好一个且业务键为稳定幂等键").isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-" + triggerId))
                .as("业务实例恰好一个").isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where process_instance_id = ?", processInstanceId))
                .isEqualTo(1L);

        // 第三轮恢复：终态不被重复发起；迟到的失败写回不得降级已成立的成功
        jdbc.update("update sw_iot_process_trigger set next_retry_time = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), triggerId);
        triggerRecoveryJob.recoverDue();
        asTenant(TENANT_A, USER_A, () -> {
            triggerFacade.markTriggerResult(idempotentKey, null, "迟到的失败写回");
            return null;
        });
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", idempotentKey))
                .as("终态后不得产生第二个引擎实例").isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-" + triggerId))
                .isEqualTo(1L);
        assertThat(text("select status from sw_iot_process_trigger where id = ?", triggerId))
                .as("迟到的失败写回不得把 SUCCESS 降级为 FAILED").isEqualTo("SUCCESS");
        System.out.println("[P4-EV] g3b.iot-rule-recovered triggerId=" + triggerId
                + " idempotentKey=" + idempotentKey + " processInstanceId=" + processInstanceId
                + " engineInstances=1 businessInstances=1 triggerStatus=SUCCESS staleWritebackRejected=true");
    }

    // ==================== 入口 3 · IoT 脚本（同一触发行 + 同一恢复调度） ====================

    @Test
    @Order(3)
    @DisplayName("G3b-3 IoT 脚本：同一窗口崩溃后同样收敛为 engine/business/trigger 各 1")
    void iotScriptWindowCrashRecoversToExactlyOnce() {
        long triggerId = 949502L;
        String idempotentKey = "g3b-script-key-1";
        insertTrigger(triggerId, idempotentKey, "SCRIPT", 9496L);

        INJECTOR_ARMED.set(true);
        triggerRecoveryJob.recoverDue();
        await("g3b-script-fault-observed", () -> INJECTIONS.get() >= 3, 30_000);
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", idempotentKey)).isZero();
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-" + triggerId)).isZero();
        System.out.println("[P4-EV] g3b.iot-script-window-crash triggerId=" + triggerId
                + " engineInstances=0 businessInstances=0 engineOnlyOrphans=0");

        jdbc.update("update sw_iot_process_trigger set next_retry_time = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), triggerId);
        triggerRecoveryJob.recoverDue();
        await("g3b-script-recovered", () -> "SUCCESS".equals(
                text("select status from sw_iot_process_trigger where id = ?", triggerId)), 60_000);
        assertThat(count("select count(*) from act_hi_procinst where business_key_ = ?", idempotentKey)).isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where business_key = ?", "iot-" + triggerId))
                .isEqualTo(1L);
        assertThat(text("select script_id from sw_iot_process_trigger where id = ?", triggerId)).isEqualTo("9496");
        System.out.println("[P4-EV] g3b.iot-script-recovered triggerId=" + triggerId
                + " scriptId=9496 engineInstances=1 businessInstances=1 triggerStatus=SUCCESS");
    }

    // ==================== 场景工具 ====================

    private void swJobBeanInjectingFirstStart() {
        INJECTOR_ARMED.set(true);
        try {
            swJobBean.execute(jobContext());
        } catch (Exception e) {
            System.out.println("[P4-EV] g3b.flow-job-exception exceptionClass=" + e.getClass().getSimpleName());
        }
    }

    private void seedFlowJob() {
        jdbc.update("insert into sw_job_info (id, create_time, update_time, deleted, tenant_id, version, job_name,"
                        + " job_group, job_type, cron_expression, status, concurrent, misfire_policy, flow_def_key,"
                        + " form_data, create_by)"
                        + " values (?, now(), now(), 0, ?, 0, 'p4-g3b-job', 'DEFAULT', 'FLOW', '0 0 0 * * ?',"
                        + " 'NORMAL', 1, 0, ?, '{\"name\":\"G3b 窗口\"}', ?)",
                JOB_ID, TENANT_A, FLOW_KEY, USER_A);
    }

    private void insertTrigger(long triggerId, String idempotentKey, String source, Long scriptId) {
        jdbc.update("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id, version,"
                        + " rule_id, script_id, device_id, idempotent_key, status, trigger_time, retry_count,"
                        + " process_template_key, trigger_source, configured_by, form_snapshot)"
                        + " values (?, now(), now(), 0, ?, 0, 9497, ?, 9498, ?, 'PENDING', now(), 0, ?, ?, ?, '{}')",
                triggerId, TENANT_A, scriptId, idempotentKey, IOT_KEY, source, USER_A);
    }

    private JobExecutionContext jobContext() throws Exception {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap();
        dataMap.put("jobId", JOB_ID);
        dataMap.put("triggerType", "AUTO");
        when(context.getMergedJobDataMap()).thenReturn(dataMap);
        when(context.getNextFireTime()).thenReturn(null);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getContext()).thenReturn(new SchedulerContext());
        when(context.getScheduler()).thenReturn(scheduler);
        return context;
    }

    private String bpmn(String processKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p4.evidence">
                  <process id="%s" name="P4 G3b %s" isExecutable="true">
                    <startEvent id="start">
                      <extensionElements>
                        <flowable:executionListener event="start"
                            delegateExpression="${phase4StartWindowInjector}"/>
                      </extensionElements>
                    </startEvent>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="approve" flowable:assignee="1"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, processKey);
    }
}
