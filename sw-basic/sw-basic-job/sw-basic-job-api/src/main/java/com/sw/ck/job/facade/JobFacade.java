package com.sw.ck.job.facade;

import com.sw.ck.job.dto.JobInfoDTO;

import java.util.Optional;

/**
 * 定时任务门面接口。
 * <p>
 * 定义于 {@code -api} 模块，实现于 {@code -biz} 模块。
 * 供其他模块（如 BPM）通过 Facade 模式查询任务信息。
 * </p>
 * <p>
 * 模块内部调用边界统一以非空 {@link Optional} 表达结果，调用方必须显式处理
 * present/empty，不得依赖可空返回。
 * </p>
 */
public interface JobFacade {

    /**
     * 按 ID 查询任务定义。
     *
     * @param jobId 任务 ID
     * @return present = 任务定义；empty = 该 ID 不存在任务定义（查询目标缺失）
     */
    Optional<JobInfoDTO> getById(Long jobId);

    /**
     * 按名称查询任务定义。
     *
     * @param jobName 任务名称
     * @return present = 任务定义；empty = 该名称不存在任务定义（查询目标缺失）
     */
    Optional<JobInfoDTO> getByJobName(String jobName);
}
