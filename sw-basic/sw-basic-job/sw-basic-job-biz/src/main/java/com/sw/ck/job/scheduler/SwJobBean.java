package com.sw.ck.job.scheduler;

import com.sw.ck.job.entity.JobInfo;
import com.sw.ck.job.entity.JobLog;
import com.sw.ck.job.enums.ExecStatus;
import com.sw.ck.job.enums.TriggerType;
import com.sw.ck.job.event.ScheduledFlowTriggerEvent;
import com.sw.ck.job.handler.JobExecutionOutcome;
import com.sw.ck.job.handler.JobHandler;
import com.sw.ck.job.port.ScheduledFlowStartPort;
import com.sw.ck.job.service.JobInfoService;
import com.sw.ck.job.service.JobLogService;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

/**
 * Quartz 任务执行 Bean。
 * <p>
 * 每当 Quartz 调度器触发时，由 {@link org.springframework.scheduling.quartz.SpringBeanJobFactory}
 * 实例化本类（通过 {@code @Component} 注册为 Spring Bean），并从 {@link JobExecutionContext}
 * 获取 {@code jobId}，加载 {@link JobInfo} 后按类型分发执行。
 * </p>
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>从 JobDataMap 获取 jobId</li>
 *   <li>查询 JobInfo（不存在/已删除则跳过）</li>
 *   <li>检查是否允许并发（不允许并发且有 RUNNING 日志时跳过）</li>
 *   <li>创建 RUNNING 状态的 JobLog</li>
 *   <li>按 job_type 分支执行（BEAN→调用 JobHandler，FLOW→发布事件）</li>
 *   <li>更新 JobLog 为 SUCCESS 或 FAILED</li>
 *   <li>更新 JobInfo.lastFireTime / nextFireTime</li>
 * </ol>
 */
@Component
public class SwJobBean extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(SwJobBean.class);

    /** Quartz JobDataMap 中存储 jobId 的 key */
    public static final String JOB_ID_KEY = "jobId";

    /** Quartz JobDataMap 中存储触发方式的 key */
    public static final String TRIGGER_TYPE_KEY = "triggerType";

    @Autowired
    private JobInfoService jobInfoService;

    @Autowired
    private JobLogService jobLogService;

    /** FLOW 类型任务的可靠受理端口（bpm 模块实现）；未装配时 FLOW 任务必须明确失败。 */
    @Autowired(required = false)
    private ScheduledFlowStartPort scheduledFlowStartPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** FLOW 任务的阶段真实结论（意图已持久化 ≠ 流程已启动），供日志如实落库。 */
    private String flowStageMessage;

    @Autowired(required = false)
    private java.util.Map<String, JobHandler> handlerMap;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        Long jobId = context.getMergedJobDataMap().getLong(JOB_ID_KEY);
        String triggerType = context.getMergedJobDataMap().getString(TRIGGER_TYPE_KEY);
        if (jobId == null) {
            log.warn("Quartz 任务触发但 JobDataMap 中缺少 jobId，跳过执行");
            return;
        }

        // 1. 查询任务定义（响应删除即时生效：删后这里查不到，跳过）
        // Quartz 线程没有任何登录身份，而租户拦截器在无身份时 fail-closed；任务定义按主键读取
        // 属调度基础设施读取，显式挂起租户行过滤，随后用任务自身的租户/配置人建立登录上下文，
        // 其后的业务读写（任务日志、流程意图受理）一律回到受租户约束的 fail-closed 边界。
        JobInfo jobInfo;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            jobInfo = jobInfoService.getById(jobId);
        }
        if (jobInfo == null) {
            log.info("定时任务 {} 已被删除，跳过执行", jobId);
            return;
        }
        if (jobInfo.getTenantId() == null) {
            log.error("定时任务 {}（{}）缺少租户归属，拒绝执行（无法建立受租户约束的执行边界）",
                    jobId, jobInfo.getJobName());
            return;
        }

        LoginUser previous = LoginUserHolder.get();
        LoginUserHolder.set(schedulerIdentity(jobInfo));
        try {
            runJob(context, jobInfo, jobId, triggerType);
        } finally {
            if (previous == null) {
                LoginUserHolder.clear();
            } else {
                LoginUserHolder.set(previous);
            }
        }
    }

    /** 调度身份：任务归属租户 + 任务配置人（不伪装自然人操作，用户名显式标注 scheduler）。 */
    private LoginUser schedulerIdentity(JobInfo jobInfo) {
        LoginUser identity = new LoginUser();
        identity.setTenantId(jobInfo.getTenantId());
        identity.setUserId(jobInfo.getCreateBy());
        identity.setUsername("scheduler");
        return identity;
    }

    private void runJob(JobExecutionContext context, JobInfo jobInfo, Long jobId, String triggerType) {
        // 2. 并发检查（不允许并发时，检查是否有 RUNNING 日志）
        if (!Boolean.TRUE.equals(jobInfo.getConcurrent())) {
            JobLog running = jobLogService.lambdaQuery()
                    .eq(JobLog::getJobId, jobId)
                    .eq(JobLog::getExecStatus, ExecStatus.RUNNING.name())
                    .one();
            if (running != null) {
                log.warn("任务 {}（{}）上次执行尚未完成，跳过本次触发（concurrent=false）",
                        jobId, jobInfo.getJobName());
                return;
            }
        }

        // 3. 创建 RUNNING 日志
        JobLog jobLog = new JobLog();
        jobLog.setJobId(jobId);
        jobLog.setJobName(jobInfo.getJobName());
        jobLog.setJobGroup(jobInfo.getJobGroup());
        jobLog.setTriggerType(triggerType != null ? triggerType : TriggerType.AUTO.name());
        jobLog.setJobParams(jobInfo.getBeanParams());
        jobLog.setExecStatus(ExecStatus.RUNNING.name());
        jobLog.setStartTime(LocalDateTime.now());
        jobLogService.save(jobLog);

        // 4. 按类型分支执行
        try {
            if ("BEAN".equals(jobInfo.getJobType())) {
                executeBean(jobInfo);
            } else if ("FLOW".equals(jobInfo.getJobType())) {
                executeFlow(jobInfo);
            } else {
                throw new IllegalStateException("任务类型未配置正确，请联系管理员处理");
            }

            // 成功：FLOW 任务报告“受理/意图已持久化”这一真实阶段，不冒充下游动作已完成
            jobLog.setExecStatus(ExecStatus.SUCCESS.name());
            jobLog.setResultMsg(flowStageMessage != null ? flowStageMessage : "执行成功");
        } catch (Exception e) {
            // P61：完整栈只进日志并与事件引用绑定；sw_job_log 会被任务日志接口原样返回，
            // 因此只落安全结论与引用，不落栈、类名、SQL、路径或第三方原文。
            String eventRef = "job-" + jobId + "-" + jobLog.getStartTime()
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            log.error("定时任务 {}（{}）执行失败: eventRef={}", jobId, jobInfo.getJobName(), eventRef, e);
            jobLog.setExecStatus(ExecStatus.FAILED.name());
            jobLog.setResultMsg("任务执行失败，请稍后重试；若持续出现请提供事件引用联系管理员");
            jobLog.setExceptionStack("ref=" + eventRef + " category="
                    + com.sw.ck.common.exception.FailureCategory.SYSTEM_FAULT.name());
        } finally {
            // 5. 更新日志
            jobLog.setEndTime(LocalDateTime.now());
            jobLog.setDuration(
                    Duration.between(jobLog.getStartTime(), jobLog.getEndTime()).toMillis());
            jobLogService.updateById(jobLog);

            // 6. 更新 JobInfo 执行时间
            jobInfo.setLastFireTime(jobLog.getStartTime());
            jobInfo.setNextFireTime(context.getNextFireTime() != null
                    ? context.getNextFireTime().toInstant()
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDateTime()
                    : null);
            jobInfoService.updateById(jobInfo);
        }
    }

    private void executeBean(JobInfo jobInfo) throws Exception {
        if (jobInfo.getBeanName() == null || jobInfo.getBeanName().isBlank()) {
            throw new IllegalStateException("任务未配置执行处理器，请先完成配置");
        }
        if (handlerMap == null || !handlerMap.containsKey(jobInfo.getBeanName())) {
            throw new IllegalStateException("任务配置的执行处理器当前不可用，请联系管理员处理");
        }
        JobHandler handler = handlerMap.get(jobInfo.getBeanName());
        Optional<JobExecutionOutcome> outcome = handler.execute(jobInfo.getBeanParams());
        if (outcome.isEmpty()) {
            // 契约：empty = 无适用执行目标，不是失败；按合法零变更继续走成功路径。
            log.info("定时任务 {}（{}）本次无适用执行目标，按零变更处理",
                    jobInfo.getId(), jobInfo.getJobName());
            return;
        }
        if (outcome.orElseThrow() == JobExecutionOutcome.NO_CHANGE) {
            log.info("定时任务 {}（{}）执行完成，本次无变更", jobInfo.getId(), jobInfo.getJobName());
        }
    }

    /**
     * FLOW 类型任务：在显式事务内受理一次持久的流程启动意图。
     *
     * <p>Phase 4 可靠业务事件：Quartz 线程既无 Spring 事务也无登录态，
     * 因此这里显式建立事务与租户上下文，把意图写入持久命令队列（{@code sw_bpm_command}）：
     * 事务提交后即使进程退出，命令仍可被领取；事务回滚则不留任何可执行孤儿任务。
     * 未装配受理端口或该流程键无可用绑定时必须抛出，让 Job 日志如实记为 FAILED，
     * 不得再以“事件已发布”伪装成功。</p>
     */
    private void executeFlow(JobInfo jobInfo) {
        if (jobInfo.getFlowDefKey() == null || jobInfo.getFlowDefKey().isBlank()) {
            throw new IllegalStateException("任务未配置要发起的流程，请先完成配置");
        }
        if (scheduledFlowStartPort == null) {
            throw new IllegalStateException("流程启动受理端口未装配，定时流程任务无法受理（请检查 bpm 模块部署）");
        }
        if (jobInfo.getTenantId() == null) {
            throw new IllegalStateException("任务缺少租户归属，无法受理流程启动");
        }
        ScheduledFlowTriggerEvent event = new ScheduledFlowTriggerEvent(
                jobInfo.getId(),
                jobInfo.getFlowDefKey(),
                jobInfo.getFormData(),
                LocalDateTime.now(),
                jobInfo.getTenantId(),
                jobInfo.getCreateBy()
        );

        LoginUser previous = LoginUserHolder.get();
        LoginUser schedulerIdentity = new LoginUser();
        schedulerIdentity.setTenantId(jobInfo.getTenantId());
        schedulerIdentity.setUserId(jobInfo.getCreateBy());
        schedulerIdentity.setUsername("scheduler");
        try {
            LoginUserHolder.set(schedulerIdentity);
            Long commandId = new TransactionTemplate(transactionManager).execute(status -> {
                Optional<Long> accepted = scheduledFlowStartPort.acceptScheduledFlowStart(event);
                return accepted.orElseThrow(() -> new IllegalStateException(
                        "流程键 " + jobInfo.getFlowDefKey() + " 无可用启用绑定，未形成启动意图"));
            });
            flowStageMessage = "已受理流程启动意图（commandId=" + commandId + "，待命令调度消费）";
            log.info("FLOW 定时任务启动意图已持久化: jobId={}, flowDefKey={}, commandId={}",
                    jobInfo.getId(), jobInfo.getFlowDefKey(), commandId);
        } finally {
            if (previous == null) {
                LoginUserHolder.clear();
            } else {
                LoginUserHolder.set(previous);
            }
        }
    }
}
