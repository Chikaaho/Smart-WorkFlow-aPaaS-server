package com.sw.ck.job.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.job.entity.JobInfo;
import com.sw.ck.job.enums.JobStatus;
import com.sw.ck.job.service.JobInfoService;
import com.sw.ck.job.service.QuartzSchedulerService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import lombok.extern.log4j.Log4j2;
import com.sw.ck.job.vo.JobInfoVO;
/**
 * 定时任务定义控制器。
 * <p>
 * 提供任务 CRUD 与调度控制（暂停/恢复/手动触发）接口。
 * 所有 Quartz 调度操作经 {@link QuartzSchedulerService} 封装，不直接接触 {@code Scheduler} API。
 * </p>
 */
@RestController
@RequestMapping("/job/info")
@Log4j2
public class JobInfoController {

    @Autowired
    private JobInfoService jobInfoService;
    @Autowired
    private QuartzSchedulerService quartzSchedulerService;

    /**
     * 分页查询任务定义。
     */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('job:list')")
    public R<PageResult<JobInfoVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestBody(required = false) JobInfo query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(PageResult.map(jobInfoService.page(pageParam, query), JobInfoVO::from));
    }

    /**
     * 按 ID 查询任务定义。
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('job:list')")
    public R<JobInfoVO> getById(@PathVariable Long id) {
        JobInfo jobInfo = jobInfoService.getById(id);
        if (jobInfo == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }
        return R.ok(JobInfoVO.from(jobInfo));
    }

    /**
     * 创建任务。
     * <p>
     * 保存到数据库后，若状态为 NORMAL 则立即注册到 Quartz 调度器。
     * 保存失败时回滚数据库，不会残留 Quartz 注册。
     * </p>
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('job:create')")
    public R<Long> create(@RequestBody JobInfo jobInfo) {
        // 参数校验
        if (jobInfo.getJobName() == null || jobInfo.getJobName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "任务名称不能为空");
        }
        if (jobInfo.getCronExpression() == null || jobInfo.getCronExpression().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "Cron 表达式不能为空");
        }
        // 默认值
        if (jobInfo.getJobGroup() == null || jobInfo.getJobGroup().isBlank()) {
            jobInfo.setJobGroup("DEFAULT");
        }
        if (jobInfo.getStatus() == null) {
            jobInfo.setStatus(JobStatus.NORMAL.name());
        }
        if (jobInfo.getJobType() == null) {
            jobInfo.setJobType("BEAN");
        }
        if (jobInfo.getConcurrent() == null) {
            jobInfo.setConcurrent(false);
        }
        if (jobInfo.getMisfirePolicy() == null) {
            jobInfo.setMisfirePolicy(0);
        }

        jobInfoService.save(jobInfo);
        log.info("任务已创建: id={}, jobName={}", jobInfo.getId(), jobInfo.getJobName());

        // 若状态为 NORMAL，注册到 Quartz
        if (JobStatus.NORMAL.name().equals(jobInfo.getStatus())) {
            try {
                quartzSchedulerService.addJob(jobInfo);
            } catch (Exception e) {
                log.error("任务注册到 Quartz 失败: jobId={}, jobName={}", jobInfo.getId(), jobInfo.getJobName(), e);
                // 不回滚数据库保存（任务已落库，可后续手动恢复）
            }
        }

        return R.ok(jobInfo.getId());
    }

    /**
     * 更新任务。
     * <p>
     * 更新数据库后，若之前在 Quartz 中已注册则先移除再重新注册（以应用新的 Cron 等配置）。
     * 若状态改为 PAUSED，则从 Quartz 移除。
     * </p>
     */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('job:update')")
    public R<Void> update(@RequestBody JobInfo jobInfo) {
        if (jobInfo.getId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "任务 ID 不能为空");
        }
        JobInfo existing = jobInfoService.getById(jobInfo.getId());
        if (existing == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }

        // 若之前已在 Quartz 中注册，先移除
        if (quartzSchedulerService.exists(existing)) {
            quartzSchedulerService.removeJob(existing);
        }

        // 更新数据库
        jobInfoService.updateById(jobInfo);

        // 若新状态为 NORMAL，重新注册
        JobInfo updated = jobInfoService.getById(jobInfo.getId());
        if (JobStatus.NORMAL.name().equals(updated.getStatus())) {
            try {
                quartzSchedulerService.addJob(updated);
            } catch (Exception e) {
                log.error("任务更新后重新注册到 Quartz 失败: jobId={}, jobName={}",
                        updated.getId(), updated.getJobName(), e);
            }
        }

        log.info("任务已更新: id={}, jobName={}", jobInfo.getId(), jobInfo.getJobName());
        return R.ok();
    }

    /**
     * 删除任务（软删除 + 从 Quartz 移除）。
     * <p>
     * 幂等：任务不存在时不报错，直接返回成功。
     * </p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('job:delete')")
    public R<Void> delete(@PathVariable Long id) {
        JobInfo jobInfo = jobInfoService.getById(id);
        if (jobInfo == null) {
            return R.ok();
        }

        // 从 Quartz 移除
        if (quartzSchedulerService.exists(jobInfo)) {
            quartzSchedulerService.removeJob(jobInfo);
        }

        // 软删除（BaseEntity 的 deleted 标记）
        jobInfoService.removeById(id);
        log.info("任务已删除: id={}, jobName={}", id, jobInfo.getJobName());
        return R.ok();
    }

    /**
     * 暂停任务。
     * <p>
     * 数据库状态改为 PAUSED，Quartz 调度器暂停该任务。
     * </p>
     */
    @PostMapping("/{id}/pause")
    @PreAuthorize("@ss.hasPermi('job:pause')")
    public R<Void> pause(@PathVariable Long id) {
        JobInfo jobInfo = jobInfoService.getById(id);
        if (jobInfo == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }
        if (JobStatus.PAUSED.name().equals(jobInfo.getStatus())) {
            return R.ok(); // 幂等
        }

        // 暂停 Quartz 调度
        if (quartzSchedulerService.exists(jobInfo)) {
            quartzSchedulerService.pauseJob(jobInfo);
        }

        // 更新数据库状态
        jobInfo.setStatus(JobStatus.PAUSED.name());
        jobInfoService.updateById(jobInfo);
        log.info("任务已暂停: id={}, jobName={}", id, jobInfo.getJobName());
        return R.ok();
    }

    /**
     * 恢复任务。
     * <p>
     * 数据库状态改为 NORMAL，Quartz 调度器恢复该任务。
     * 若任务未在 Quartz 中注册（如应用重启后），则重新注册。
     * </p>
     */
    @PostMapping("/{id}/resume")
    @PreAuthorize("@ss.hasPermi('job:resume')")
    public R<Void> resume(@PathVariable Long id) {
        JobInfo jobInfo = jobInfoService.getById(id);
        if (jobInfo == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }
        if (JobStatus.NORMAL.name().equals(jobInfo.getStatus())) {
            return R.ok(); // 幂等
        }

        // 更新数据库状态
        jobInfo.setStatus(JobStatus.NORMAL.name());
        jobInfoService.updateById(jobInfo);

        // 注册或恢复 Quartz 调度
        if (quartzSchedulerService.exists(jobInfo)) {
            quartzSchedulerService.resumeJob(jobInfo);
        } else {
            try {
                quartzSchedulerService.addJob(jobInfo);
            } catch (Exception e) {
                log.error("恢复任务时注册到 Quartz 失败: jobId={}, jobName={}",
                        id, jobInfo.getJobName(), e);
            }
        }

        log.info("任务已恢复: id={}, jobName={}", id, jobInfo.getJobName());
        return R.ok();
    }

    /**
     * 手动触发一次任务执行。
     * <p>
     * 不改变调度计划，仅触发一次立即执行。
     * 任务状态为 PAUSED 时也可手动触发。
     * </p>
     */
    @PostMapping("/{id}/trigger")
    @PreAuthorize("@ss.hasPermi('job:trigger')")
    public R<Void> trigger(@PathVariable Long id) {
        JobInfo jobInfo = jobInfoService.getById(id);
        if (jobInfo == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }
        quartzSchedulerService.triggerOnce(jobInfo);
        log.info("手动触发任务: id={}, jobName={}", id, jobInfo.getJobName());
        return R.ok();
    }
}
