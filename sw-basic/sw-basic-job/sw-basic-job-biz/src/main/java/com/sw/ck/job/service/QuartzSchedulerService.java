package com.sw.ck.job.service;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.job.entity.JobInfo;
import com.sw.ck.job.enums.JobStatus;
import com.sw.ck.job.enums.TriggerType;
import com.sw.ck.job.scheduler.SwJobBean;
import org.quartz.*;
import org.springframework.stereotype.Service;

import java.util.TimeZone;

import lombok.extern.log4j.Log4j2;
/**
 * Quartz 调度器封装。
 * <p>
 * 不直接暴露 {@link Scheduler} API，统一经本 Service 管理任务调度生命周期。
 * 所有方法内部捕获 {@link SchedulerException} 并转为运行期异常。
 * </p>
 */
@Service
@Log4j2
public class QuartzSchedulerService {

    @Autowired
    private Scheduler scheduler;

    /**
     * 将任务注册到 Quartz 调度器并启动。
     *
     * @param jobInfo 任务定义
     */
    public void addJob(JobInfo jobInfo) {
        JobDetail jobDetail = buildJobDetail(jobInfo);
        CronTrigger trigger = buildCronTrigger(jobInfo);
        try {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("定时任务已注册: jobId={}, jobName={}, cron={}",
                    jobInfo.getId(), jobInfo.getJobName(), jobInfo.getCronExpression());
        } catch (SchedulerException e) {
            throw new RuntimeException("注册定时任务失败: " + jobInfo.getJobName(), e);
        }
    }

    /**
     * 从 Quartz 调度器中移除任务。
     *
     * @param jobInfo 任务定义
     */
    public void removeJob(JobInfo jobInfo) {
        JobKey jobKey = toJobKey(jobInfo);
        try {
            scheduler.deleteJob(jobKey);
            log.info("定时任务已移除: jobId={}, jobName={}", jobInfo.getId(), jobInfo.getJobName());
        } catch (SchedulerException e) {
            throw new RuntimeException("移除定时任务失败: " + jobInfo.getJobName(), e);
        }
    }

    /**
     * 暂停任务（状态变为 PAUSED，不再触发）。
     *
     * @param jobInfo 任务定义
     */
    public void pauseJob(JobInfo jobInfo) {
        JobKey jobKey = toJobKey(jobInfo);
        try {
            scheduler.pauseJob(jobKey);
            log.info("定时任务已暂停: jobId={}, jobName={}", jobInfo.getId(), jobInfo.getJobName());
        } catch (SchedulerException e) {
            throw new RuntimeException("暂停定时任务失败: " + jobInfo.getJobName(), e);
        }
    }

    /**
     * 恢复已暂停的任务。
     *
     * @param jobInfo 任务定义
     */
    public void resumeJob(JobInfo jobInfo) {
        JobKey jobKey = toJobKey(jobInfo);
        try {
            scheduler.resumeJob(jobKey);
            log.info("定时任务已恢复: jobId={}, jobName={}", jobInfo.getId(), jobInfo.getJobName());
        } catch (SchedulerException e) {
            throw new RuntimeException("恢复定时任务失败: " + jobInfo.getJobName(), e);
        }
    }

    /**
     * 立即触发一次任务执行（手动触发）。
     *
     * @param jobInfo 任务定义
     */
    public void triggerOnce(JobInfo jobInfo) {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(SwJobBean.JOB_ID_KEY, jobInfo.getId());
        dataMap.put(SwJobBean.TRIGGER_TYPE_KEY, TriggerType.MANUAL.name());
        JobKey jobKey = toJobKey(jobInfo);
        try {
            // 检查任务是否已在 Quartz 中注册
            if (!scheduler.checkExists(jobKey)) {
                // 手动触发时如果任务未注册，先临时注册
                JobDetail jobDetail = buildJobDetail(jobInfo);
                scheduler.addJob(jobDetail, true);
            }
            scheduler.triggerJob(jobKey, dataMap);
            log.info("手动触发定时任务: jobId={}, jobName={}", jobInfo.getId(), jobInfo.getJobName());
        } catch (SchedulerException e) {
            throw new RuntimeException("手动触发定时任务失败: " + jobInfo.getJobName(), e);
        }
    }

    /**
     * 检查任务是否已在 Quartz 调度器中注册。
     *
     * @param jobInfo 任务定义
     * @return true=已注册
     */
    public boolean exists(JobInfo jobInfo) {
        try {
            return scheduler.checkExists(toJobKey(jobInfo));
        } catch (SchedulerException e) {
            return false;
        }
    }

    // ─── 内部方法 ───

    private JobDetail buildJobDetail(JobInfo jobInfo) {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put(SwJobBean.JOB_ID_KEY, jobInfo.getId());
        dataMap.put(SwJobBean.TRIGGER_TYPE_KEY, TriggerType.AUTO.name());

        return JobBuilder.newJob(SwJobBean.class)
                .withIdentity(toJobKey(jobInfo))
                .withDescription(jobInfo.getDescription())
                .usingJobData(dataMap)
                .storeDurably()
                .build();
    }

    private CronTrigger buildCronTrigger(JobInfo jobInfo) {
        CronScheduleBuilder scheduleBuilder = CronScheduleBuilder
                .cronSchedule(jobInfo.getCronExpression())
                .inTimeZone(TimeZone.getDefault());

        // misfire 策略映射
        if (jobInfo.getMisfirePolicy() != null) {
            switch (jobInfo.getMisfirePolicy()) {
                case 0 -> scheduleBuilder.withMisfireHandlingInstructionIgnoreMisfires();
                case 1 -> scheduleBuilder.withMisfireHandlingInstructionFireAndProceed();
                case 2 -> scheduleBuilder.withMisfireHandlingInstructionDoNothing();
            }
        }

        return TriggerBuilder.newTrigger()
                .withIdentity(toTriggerKey(jobInfo))
                .withSchedule(scheduleBuilder)
                .build();
    }

    private JobKey toJobKey(JobInfo jobInfo) {
        return new JobKey(jobInfo.getJobName(), jobInfo.getJobGroup());
    }

    private TriggerKey toTriggerKey(JobInfo jobInfo) {
        return new TriggerKey(jobInfo.getJobName() + "_trigger", jobInfo.getJobGroup());
    }
}
