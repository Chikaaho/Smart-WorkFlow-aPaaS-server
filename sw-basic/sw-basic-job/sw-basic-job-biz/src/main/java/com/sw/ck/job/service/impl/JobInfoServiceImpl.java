package com.sw.ck.job.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.job.entity.JobInfo;
import com.sw.ck.job.mapper.JobInfoMapper;
import com.sw.ck.job.service.JobInfoService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 定时任务定义 Service 实现。
 */
@Service
public class JobInfoServiceImpl
        extends BaseServiceImpl<JobInfoMapper, JobInfo>
        implements JobInfoService {

    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    public JobInfoServiceImpl(LoginContextProvider loginContextProvider,
                              DeptScopeProvider deptScopeProvider) {
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    @Override
    public JobInfo getByJobName(String jobName) {
        return lambdaQuery()
                .eq(JobInfo::getJobName, jobName)
                .one();
    }

    @Override
    public List<JobInfo> listEnabled() {
        // 调度线程无登录态：挂起租户过滤（作业注册表为系统级对象）
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            return lambdaQuery()
                    .eq(JobInfo::getStatus, "NORMAL")
                    .list();
        }
    }

    @Override
    public PageResult<JobInfo> page(PageParam pageParam, JobInfo query) {
        // 业务过滤条件（空串/空白归一化为 null，保持与原 wrapper 判空的等价语义）
        String jobName = query != null && query.getJobName() != null && !query.getJobName().isBlank()
                ? query.getJobName() : null;
        String jobType = query != null && query.getJobType() != null && !query.getJobType().isBlank()
                ? query.getJobType() : null;
        String status = query != null && query.getStatus() != null && !query.getStatus().isBlank()
                ? query.getStatus() : null;

        // 数据范围：sw_job_info 无 dept_id 列，等效条件在 selectJobInfoPage 内实现
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);

        IPage<JobInfo> result = baseMapper.selectJobInfoPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                jobName, jobType, status, scope);
        return PageResult.of(result);
    }
}
