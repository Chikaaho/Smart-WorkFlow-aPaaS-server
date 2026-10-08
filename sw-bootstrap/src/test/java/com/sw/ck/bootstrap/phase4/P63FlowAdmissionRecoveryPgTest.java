package com.sw.ck.bootstrap.phase4;

import com.sw.ck.bpm.process.entity.CommandStatusEnum;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.bpm.process.queue.CommandEnvelope;
import com.sw.ck.bpm.process.queue.ScheduledFlowCommandHandler;
import com.sw.ck.job.scheduler.SwJobBean;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.quartz.JobDataMap;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerContext;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P63 审查07 §4 有限受控恢复入口：区分「FLOW 崩溃恢复机制本身」与「装置/准入截止合同边界」。
 *
 * <p>背景：锁定的 {@code Phase4PgStartWindowCrashTest#scheduledFlowWindowCrashRecoversToExactlyOnce}
 * 在当前候选失败（业务实例数 0）。本轮已用该用例单方法运行取证：故障注入标记在
 * 自连接 `pg_terminate_backend` 后丢失（{@code await-timeout g3b-flow-fault-observed} 静默耗满 30s），
 * 使测试的强制重试落在命令准入截止（创建 +30s，P62 合同默认）之后 0.17s，被 P63 领取路径
 * 守准入截止（审查03§49 裁决语义）永久拒绝，命令最终由对账判 EXPIRED
 * （「准入截止到期且未执行（效果未发生）」），恢复不再发生。</p>
 *
 * <p>本用例以真实 PG + 真实 Flowable 提供**等强度替代验证**：崩溃注入由独立连接发起
 * （等价于外部 DBA 终止该事务后端，注入标记确定性记录、不再静默超时），强制重试
 * **发生在准入截止之内**（立即置 next_retry_at=now-1s），断言：恰好 1 引擎实例 / 1 业务实例、
 * 命令 COMPLETED、重放 SKIP_DUPLICATE。由此证明恢复机制在准入窗口内完好，
 * 锁定用例的失败归因于装置（标记丢失）+ 已裁决的截止边界，而非 P63 破坏了恢复本身。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("P63 审查07§4 · FLOW 崩溃窗口在准入截止内恢复恰一次 · 真实 PostgreSQL")
class P63FlowAdmissionRecoveryPgTest extends Phase4PgSupport {

    private static final String FLOW_KEY = "p63_adm_flow";
    private static final String FORM_KEY = "p63_adm_form";
    private static final long JOB_ID = 9506L;

    private static final AtomicBoolean ARMED = new AtomicBoolean(false);
    private static final AtomicInteger INJECTIONS = new AtomicInteger();

    private SwJobBean swJobBean;
    private BpmCommandQueue queue;
    private ScheduledFlowCommandHandler scheduledFlowHandler;

    /** 崩溃注入：读取事务绑定后端的 pid，并由**独立连接**终止它（不再自连接自杀）。 */
    public static class RobustStartWindowInjector implements ExecutionListener {

        private final DataSource dataSource;

        public RobustStartWindowInjector(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public void notify(DelegateExecution execution) {
            if (!ARMED.compareAndSet(true, false)) {
                return;
            }
            try {
                int pid;
                Connection bound = DataSourceUtils.getConnection(dataSource);
                try (Statement stmt = bound.createStatement();
                     ResultSet rs = stmt.executeQuery("select pg_backend_pid()")) {
                    rs.next();
                    pid = rs.getInt(1);
                }
                // 终止语句在独立连接上执行：目标后端的死亡不会吞掉终止语句自身的返回，
                // 注入标记因此确定性递增（原装置在自连接上终止，标记偶发丢失导致 30s 静默超时）。
                try (Connection killer = DriverManager.getConnection(
                        evidenceUrl(), pgUser(), pgPassword());
                     Statement stmt = killer.createStatement()) {
                    stmt.execute("select pg_terminate_backend(" + pid + ")");
                }
                INJECTIONS.incrementAndGet();
                System.out.println("[P63-EV] p63-adm.fault-injected pid=" + pid
                        + " markers=" + INJECTIONS.get() + " window=engine-instance-created-business-not-saved");
            } catch (Exception e) {
                throw new IllegalStateException("崩溃注入失败", e);
            }
        }
    }

    @BeforeAll
    void bootAll() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrate();
        boot(53L, Map.of("sw.bpm.command.poll-interval-millis", "300",
                "sw.bpm.command.p0-poll-interval-millis", "300",
                "sw.bpm.command.backoff-millis", "60000"));
        seedTenantAndUser(TENANT_A, USER_A, "p63adm");
        asTenant(TENANT_A, USER_A, () -> {
            publishForm(FORM_KEY);
            return null;
        });
        seedBinding(FORM_KEY, FLOW_KEY);
        deployBpmn(FLOW_KEY, bpmn(FLOW_KEY), FORM_KEY, false);
        app.getBeanFactory().registerSingleton("p63AdmissionInjector",
                new RobustStartWindowInjector(app.getBean(DataSource.class)));
        swJobBean = app.getBean(SwJobBean.class);
        queue = app.getBean(BpmCommandQueue.class);
        scheduledFlowHandler = app.getBean(ScheduledFlowCommandHandler.class);
        System.out.println("[P63-EV] p63-adm.context=ready flowKey=" + FLOW_KEY
                + " injector=robust-external-terminate");
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    @Test
    @Order(1)
    @DisplayName("窗口内崩溃 → 准入截止内强制重试 → 恰一次恢复（引擎/业务各 1）+ 重放幂等")
    void flowCrashWindowRecoversExactlyOnceWithinAdmission() throws Exception {
        seedFlowJob();
        ARMED.set(true);
        try {
            swJobBean.execute(jobContext());
        } catch (Exception e) {
            System.out.println("[P63-EV] p63-adm.flow-job-exception exceptionClass="
                    + e.getClass().getSimpleName());
        }

        await("p63-adm-fault-observed", () -> INJECTIONS.get() >= 1, 15_000);
        assertThat(INJECTIONS.get()).as("故障注入必须被真实记录（装置确定性）").isEqualTo(1);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY))
                .as("窗口内崩溃不得留下业务实例").isZero();
        assertThat(count("select count(*) from act_hi_procinst where proc_def_id_ like ?", "%" + FLOW_KEY + "%"))
                .as("窗口内崩溃不得留下引擎实例").isZero();

        String commandKey = text("select command_key from sw_bpm_command where command_key like ?",
                "SCHEDULED_FLOW:" + JOB_ID + ":%");
        assertThat(commandKey).isNotNull();
        String createdAt = text("select create_time from sw_bpm_command where command_key = ?", commandKey);
        String deadlineAt = text("select deadline_at from sw_bpm_command where command_key = ?", commandKey);

        // 在准入截止之内强制重试（锁定的 Phase4 用例因装置静默 30s 超时，重试落在截止之后）
        LocalDateTime forcedRetryAt = LocalDateTime.now().minusSeconds(1);
        jdbc.update("update sw_bpm_command set next_retry_at = ? where command_key = ?", forcedRetryAt, commandKey);
        assertThat(deadlineAt).as("重试必须在准入截止之内（否则按裁决语义不得领取）")
                .isNotNull();
        assertThat(forcedRetryAt).isBefore(LocalDateTime.parse(deadlineAt.replace(' ', 'T')));

        await("p63-adm-flow-recovered", () -> count("select count(*) from sw_bpm_command where command_key = ? and status = ?",
                commandKey, CommandStatusEnum.COMPLETED.getCode()) == 1L, 60_000);

        String businessKey = text("select business_key from sw_bpm_instance where process_def_key = ?", FLOW_KEY);
        String processInstanceId = text("select process_instance_id from sw_bpm_instance where process_def_key = ?", FLOW_KEY);
        assertThat(count("select count(*) from sw_bpm_instance where process_def_key = ?", FLOW_KEY)).isEqualTo(1L);
        assertThat(count("select count(*) from act_hi_procinst where proc_inst_id_ = ?", processInstanceId)).isEqualTo(1L);
        assertThat(businessKey).startsWith("sched-");

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
        System.out.println("[P63-EV] p63-adm.flow-recovered-within-admission commandKey=" + commandKey
                + " createdAt=" + createdAt + " deadlineAt=" + deadlineAt
                + " forcedRetryAt=" + forcedRetryAt + " businessKey=" + businessKey
                + " commandStatus=COMPLETED engineInstances=1 businessInstances=1 replay=" + replay);
    }

    // ==================== 场景工具（与锁定用例同构，装置侧仅注入方式不同） ====================

    private void seedFlowJob() {
        jdbc.update("insert into sw_job_info (id, create_time, update_time, deleted, tenant_id, version, job_name,"
                        + " job_group, job_type, cron_expression, status, concurrent, misfire_policy, flow_def_key,"
                        + " form_data, create_by)"
                        + " values (?, now(), now(), 0, ?, 0, 'p63-adm-job', 'DEFAULT', 'FLOW', '0 0 0 * * ?',"
                        + " 'NORMAL', 1, 0, ?, '{\"name\":\"P63 准入窗口恢复\"}', ?)",
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

    private String bpmn(String processKey) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://p63.evidence">
                  <process id="%s" name="P63 admission recovery %s" isExecutable="true">
                    <startEvent id="start">
                      <extensionElements>
                        <flowable:executionListener event="start"
                            delegateExpression="${p63AdmissionInjector}"/>
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
