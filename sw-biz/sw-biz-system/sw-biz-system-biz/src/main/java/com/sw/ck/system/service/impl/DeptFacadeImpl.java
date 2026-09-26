package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.mapper.SysDeptMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * DeptQueryFacade 实现。
 * <p>
 * 其它模块通过 {@link DeptQueryFacade} 接口查询部门候选，
 * 禁止直接访问 sys_dept 表或 Mapper。仅返回正常状态（status=0）部门。
 * </p>
 * <p>
 * 模块内部调用边界返回非空 {@link Optional}：查询对象缺失（{@code ids == null}）以 empty 表达，
 * 合法零匹配以 present 的空集合表达。
 * </p>
 */
@Service
public class DeptFacadeImpl implements DeptQueryFacade {

    /** 部门状态：0=正常 1=停用（对齐 sys_dept.status 口径） */
    private static final int STATUS_ACTIVE = 0;

    private final SysDeptMapper sysDeptMapper;

    public DeptFacadeImpl(SysDeptMapper sysDeptMapper) {
        this.sysDeptMapper = sysDeptMapper;
    }

    @Override
    public Optional<List<Long>> findActiveDeptIds(Collection<Long> ids) {
        if (ids == null) {
            // 缺少查询对象：查询未执行
            return Optional.empty();
        }
        if (ids.isEmpty()) {
            // 查询已执行且零匹配（合法零结果）
            return Optional.of(List.of());
        }
        List<SysDept> depts = sysDeptMapper.selectList(Wrappers.lambdaQuery(SysDept.class)
                .select(SysDept::getId)
                .in(SysDept::getId, ids)
                .eq(SysDept::getStatus, STATUS_ACTIVE));
        List<Long> found = new ArrayList<>(depts.size());
        for (SysDept d : depts) {
            found.add(d.getId());
        }
        return Optional.of(found);
    }
}
