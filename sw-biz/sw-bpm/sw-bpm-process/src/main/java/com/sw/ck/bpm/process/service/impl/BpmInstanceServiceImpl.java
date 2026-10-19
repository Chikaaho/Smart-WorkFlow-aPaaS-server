package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.InstanceFilterDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 流程实例记录 Service 实现。
 */
@Service
public class BpmInstanceServiceImpl
        extends BaseServiceImpl<BpmInstanceMapper, BpmInstance>
        implements BpmInstanceService {

    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    public BpmInstanceServiceImpl(LoginContextProvider loginContextProvider,
                                  DeptScopeProvider deptScopeProvider) {
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    @Override
    public Optional<BpmInstance> findByProcessInstanceId(String processInstanceId) {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(BpmInstance::getProcessInstanceId, processInstanceId)
                        .one()
        );
    }

    @Override
    public Optional<BpmInstance> findByBusinessKey(String businessKey) {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(BpmInstance::getBusinessKey, businessKey)
                        .one()
        );
    }

    @Override
    public void updateStatus(String processInstanceId, String status) {
        lambdaUpdate()
                .eq(BpmInstance::getProcessInstanceId, processInstanceId)
                .set(BpmInstance::getStatus, status)
                .update();
    }

    @Override
    public PageResult<BpmInstance> pageInstances(PageParam pageParam, InstanceFilterDTO filter) {
        // 业务过滤条件（空串/空白归一化为 null，保持与原 wrapper 判空的等价语义）
        String status = filter != null && filter.getStatus() != null && !filter.getStatus().isBlank()
                ? filter.getStatus() : null;
        String processDefKey = filter != null && filter.getProcessDefKey() != null && !filter.getProcessDefKey().isBlank()
                ? filter.getProcessDefKey() : null;
        Long initiatorId = filter != null ? filter.getInitiatorId() : null;
        String businessKey = filter != null && filter.getBusinessKey() != null && !filter.getBusinessKey().isBlank()
                ? filter.getBusinessKey() : null;

        // 数据范围：sw_bpm_instance 无 dept_id 列，等效条件在 selectInstanceCount/List 内实现
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);

        // 查询总数（不含 ORDER BY——H2 PostgreSQL 模式不允许 COUNT + ORDER BY）
        long total = getBaseMapper().selectInstanceCount(status, processDefKey, initiatorId, businessKey, scope);

        // 分页查询（加 ORDER BY + LIMIT/OFFSET）
        long offset = (pageParam.getPageNum() - 1) * pageParam.getPageSize();
        int limit = (int) pageParam.getPageSize();
        List<BpmInstance> records = getBaseMapper().selectInstanceList(
                status, processDefKey, initiatorId, businessKey, scope, limit, offset);

        // 组装 PageResult
        PageResult<BpmInstance> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPageNum(pageParam.getPageNum());
        result.setPageSize(pageParam.getPageSize());
        return result;
    }

    @Override
    public long countByFormKeyAndBusinessKeyAndInitiator(String formKey, String businessKey,
                                                         Long initiatorId) {
        if (formKey == null || formKey.isBlank() || businessKey == null || businessKey.isBlank()
                || initiatorId == null) {
            return 0L;
        }
        // 租户经 MyBatis-Plus 拦截器注入；发起人为强制条件，不接受跨用户统计
        return lambdaQuery()
                .eq(BpmInstance::getFormKey, formKey)
                .eq(BpmInstance::getBusinessKey, businessKey)
                .eq(BpmInstance::getInitiatorId, initiatorId)
                .count();
    }
}
