package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.job.scheduler.SwJobBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4 G1（提交后崩溃 / 重启恢复）· 真实 PostgreSQL 双进程装载行为证据。
 *
 * <p>场景与方向 §5.A.4 一致："进程在'业务提交后、异步监听前'退出，重启后仍能发现并继续任务"。
 * 这里用两个真实应用上下文模拟进程重启：上下文 A 以真实 Quartz 任务受理流程启动意图并提交，
 * 随后关闭（进程退出，命令仍为 PENDING）；上下文 B 在**同一数据库**上启动并消费该遗留命令，
 * 最终形成真实流程实例与实例记录。命令、租户、任务与业务幂等身份在两次装载间保持不变。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase4 G1b · 提交后崩溃与重启恢复 · 真实 PostgreSQL 双装载")
class Phase4PgRestartRecoveryTest extends Phase4PgSupport {

    private static final String FLOW_KEY = "p4_restart_flow";
    private static final String FORM_KEY = "p4_restart_form";
    private static final long JOB_ID = 9470L;

    @Test
    @DisplayName("提交后进程退出：重启后的新进程消费遗留意图，真实发起且不重复")
    void leftoverIntentIsRecoveredAfterRestart() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrate();

        // ---- 进程 A：受理并提交意图后退出（调度器空闲，保证"提交后、消费前"退出） ----
        boot(45L, Map.of("sw.bpm.command.poll-interval-millis", "3600000",
                "sw.bpm.command.p0-poll-interval-millis", "3600000"));
        seedTenantAndUser(TENANT_A, USER_A, "restart");
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, FLOW_KEY);
        deployBpmn(FLOW_KEY, bpmn(), FORM_KEY, false);
        seedFlowJob();
        SwJobBean swJobBean = app.getBean(SwJobBean.class);
        swJobBean.execute(jobContext());

        long pendingAfterCommit = count("select count(*) from sw_bpm_command where command_key like ? and status = ?",
                "SCHEDULED_FLOW:" + JOB_ID + ":%", CommandStatusEnum.PENDING.getCode());
        assertThat(pendingAfterCommit).as("业务提交后必须留下可恢复的持久意图").isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY))
                .as("进程退出前尚未消费，不应有实例").isZero();
        String commandKey = text("select command_key from sw_bpm_command where command_key like ?",
                "SCHEDULED_FLOW:" + JOB_ID + ":%");
        String tenantOfCommand = text("select tenant_id from sw_bpm_command where command_key = ?", commandKey);
        System.out.println("[P4-EV] g1b.process-a-exit commandKey=" + commandKey
                + " commandStatus=PENDING tenant=" + tenantOfCommand + " instances=0");

        shutdown();
        System.out.println("[P4-EV] g1b.process-a-closed simulatedCrashAfterCommit=true");

        // ---- 进程 B：在同一数据库上重启，消费遗留意图 ----
        boot(46L, Map.of("sw.bpm.command.poll-interval-millis", "300",
                "sw.bpm.command.p0-poll-interval-millis", "300"));
        await("restart-consumes-leftover-command", () -> count(
                "select count(*) from sw_bpm_command where command_key = ? and status = ?",
                commandKey, CommandStatusEnum.COMPLETED.getCode()) == 1L, 60_000);

        String processInstanceId = text("select process_instance_id from sw_bpm_instance where process_def_key = ?",
                FLOW_KEY);
        assertThat(text("select tenant_id from sw_bpm_command where command_key = ?", commandKey))
                .as("重启恢复不得改变命令的租户归属").isEqualTo(tenantOfCommand);
        assertThat(processInstanceId).isNotBlank();
        assertThat(count("select count(*) from act_hi_procinst where proc_inst_id_ = ?", processInstanceId))
                .isEqualTo(1L);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY)).isEqualTo(1L);
        System.out.println("[P4-EV] g1b.process-b-recovered commandKey=" + commandKey
                + " commandStatus=COMPLETED businessKey="
                + text("select business_key from sw_bpm_instance where process_def_key = ?", FLOW_KEY)
                + " engineInstance=1 swBpmInstances=1");
        shutdown();
    }

    private void seedFlowJob() {
        jdbc.update("insert into sw_job_info (id, create_time, update_time, deleted, tenant_id, version, job_name,"
                        + " job_group, job_type, cron_expression, status, concurrent, misfire_policy, flow_def_key,"
                        + " form_data, create_by)"
                        + " values (?, now(), now(), 0, ?, 0, 'p4-restart-job', 'DEFAULT', 'FLOW', '0 0 0 * * ?',"
                        + " 'NORMAL', 1, 0, ?, '{\"name\":\"重启恢复\"}', ?)",
                JOB_ID, TENANT_A, FLOW_KEY, USER_A);
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

    private String bpmn() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p4.evidence">
                  <process id="%s" name="P4 Restart Flow" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approve"/>
                    <userTask id="approve" name="approve" flowable:assignee="1"/>
                    <sequenceFlow id="f2" sourceRef="approve" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(FLOW_KEY);
    }
}
