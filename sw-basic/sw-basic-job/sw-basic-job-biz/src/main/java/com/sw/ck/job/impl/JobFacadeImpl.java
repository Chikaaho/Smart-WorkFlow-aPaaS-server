package com.sw.ck.job.impl;

import com.sw.ck.job.dto.JobInfoDTO;
import com.sw.ck.job.entity.JobInfo;
import com.sw.ck.job.facade.JobFacade;
import com.sw.ck.job.service.JobInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 定时任务门面实现。
 * <p>
 * 薄封装 {@link JobInfoService}，将 Entity 转换为 DTO 后对外暴露。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class JobFacadeImpl implements JobFacade {

    private final JobInfoService jobInfoService;

    @Override
    public Optional<JobInfoDTO> getById(Long jobId) {
        return toDTO(jobInfoService.getById(jobId));
    }

    @Override
    public Optional<JobInfoDTO> getByJobName(String jobName) {
        return toDTO(jobInfoService.getByJobName(jobName));
    }

    /**
     * 将 Entity 转换为 DTO。
     * <p>
     * 只暴露对外有意义的字段，不暴露 deleted / tenant_id / version 等系统列。
     * 契约语义：Entity 缺失（null，即查询目标不存在）→ empty。
     * </p>
     */
    private Optional<JobInfoDTO> toDTO(JobInfo entity) {
        if (entity == null) {
            return Optional.empty();
        }
        JobInfoDTO dto = new JobInfoDTO();
        dto.setId(entity.getId());
        dto.setJobName(entity.getJobName());
        dto.setJobGroup(entity.getJobGroup());
        dto.setJobType(entity.getJobType());
        dto.setCronExpression(entity.getCronExpression());
        dto.setStatus(entity.getStatus());
        dto.setConcurrent(entity.getConcurrent());
        dto.setMisfirePolicy(entity.getMisfirePolicy());
        dto.setDescription(entity.getDescription());
        dto.setBeanName(entity.getBeanName());
        dto.setFlowDefKey(entity.getFlowDefKey());
        dto.setLastFireTime(entity.getLastFireTime());
        dto.setNextFireTime(entity.getNextFireTime());
        dto.setCreateTime(entity.getCreateTime());
        return Optional.of(dto);
    }
}
