package com.sw.ck.job.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.job.entity.JobLog;
import com.sw.ck.job.mapper.JobLogMapper;
import com.sw.ck.job.service.JobLogService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 定时任务执行日志 Service 实现。
 */
@Service
public class JobLogServiceImpl
        extends BaseServiceImpl<JobLogMapper, JobLog>
        implements JobLogService {

    @Autowired
    private LoginContextProvider loginContextProvider;
    @Autowired
    private DeptScopeProvider deptScopeProvider;

    @Override
    public List<JobLog> listByJobId(Long jobId) {
        return lambdaQuery()
                .eq(JobLog::getJobId, jobId)
                .orderByDesc(JobLog::getCreateTime)
                .list();
    }

    @Override
    public JobLog getLatestByJobId(Long jobId) {
        return lambdaQuery()
                .eq(JobLog::getJobId, jobId)
                .orderByDesc(JobLog::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public PageResult<JobLog> page(PageParam pageParam, Long jobId) {
        // 数据范围：sw_job_log 无 dept_id 列，等效条件在 selectJobLogPage 内实现
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);

        IPage<JobLog> result = baseMapper.selectJobLogPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                jobId, scope);
        return PageResult.of(result);
    }
}
