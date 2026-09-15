package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.api.dept.DeptOptionDTO;
import com.sw.ck.system.api.dept.DeptQueryFacade;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.mapper.SysDeptMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * DeptQueryFacade 实现。
 * <p>
 * 其它模块通过 {@link DeptQueryFacade} 接口查询部门候选，
 * 禁止直接访问 sys_dept 表或 Mapper。仅返回正常状态（status=0）部门。
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
    public List<DeptOptionDTO> searchActiveDepts(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(limit, 1);
        List<SysDept> depts = sysDeptMapper.selectList(Wrappers.lambdaQuery(SysDept.class)
                .select(SysDept::getId, SysDept::getName, SysDept::getParentId, SysDept::getStatus)
                .eq(SysDept::getStatus, STATUS_ACTIVE)
                .like(!kw.isEmpty(), SysDept::getName, kw)
                .orderByAsc(SysDept::getId)
                .last("LIMIT " + safeLimit));
        return depts.stream()
                .map(d -> new DeptOptionDTO(d.getId(), d.getName(), d.getParentId(), d.getStatus()))
                .toList();
    }

    @Override
    public List<Long> findActiveDeptIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysDept> depts = sysDeptMapper.selectList(Wrappers.lambdaQuery(SysDept.class)
                .select(SysDept::getId)
                .in(SysDept::getId, ids)
                .eq(SysDept::getStatus, STATUS_ACTIVE));
        List<Long> found = new ArrayList<>(depts.size());
        for (SysDept d : depts) {
            found.add(d.getId());
        }
        return found;
    }
}
