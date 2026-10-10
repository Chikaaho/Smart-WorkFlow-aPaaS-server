package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysPostDelegate;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysPostDelegateMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.service.PositionDelegateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * P64 阶段Ⅱ（A07）岗位委托配置服务实现。
 * <p>
 * 校验口径（主方向 §3.4）：自委托/循环/跨租户/越权范围/受托岗位无效/同优先级重叠
 * 在配置与生效时拒绝；委托链超过 4 跳的配置同样拒绝（避免不可部署配置）。
 * 每个源岗位/适用范围最多一条 ENABLED 关系：精确部门（DEPT）与组织默认（ORG）可并存，
 * 同优先级重复拒绝（服务层事务内校验；并发窗口由唯一业务语义复核兜底）。
 * </p>
 */
@Service
public class PositionDelegateServiceImpl
        extends BaseServiceImpl<SysPostDelegateMapper, SysPostDelegate>
        implements PositionDelegateService {

    /** 委托最大跳数（逐跳检查有效期、授权范围、循环和最终任职；超过阻止解析）。 */
    public static final int MAX_HOPS = 4;

    private static final int POST_STATUS_ENABLED = 1;

    private final SysPostMapper sysPostMapper;
    private final SysDeptMapper sysDeptMapper;

    public PositionDelegateServiceImpl() {
        this(null, null);
    }

    @Autowired
    public PositionDelegateServiceImpl(SysPostMapper sysPostMapper, SysDeptMapper sysDeptMapper) {
        this.sysPostMapper = sysPostMapper;
        this.sysDeptMapper = sysDeptMapper;
    }

    @Override
    public PageResult<SysPostDelegate> page(PageParam pageParam, SysPostDelegate query) {
        LambdaQueryWrapper<SysPostDelegate> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            if (query.getSourcePostId() != null) {
                wrapper.eq(SysPostDelegate::getSourcePostId, query.getSourcePostId());
            }
            if (query.getTargetPostId() != null) {
                wrapper.eq(SysPostDelegate::getTargetPostId, query.getTargetPostId());
            }
            if (StringUtils.isNotBlank(query.getScopeType())) {
                wrapper.eq(SysPostDelegate::getScopeType, query.getScopeType().toUpperCase());
            }
            if (query.getDeptId() != null) {
                wrapper.eq(SysPostDelegate::getDeptId, query.getDeptId());
            }
            if (StringUtils.isNotBlank(query.getStatus())) {
                wrapper.eq(SysPostDelegate::getStatus, query.getStatus().toUpperCase());
            }
        }
        wrapper.orderByAsc(SysPostDelegate::getSourcePostId)
                .orderByAsc(SysPostDelegate::getCreateTime);
        return baseMapper.selectPage(pageParam, wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysPostDelegate delegate) {
        validateShape(delegate);
        requirePostsValid(delegate);
        requireDeptValid(delegate);
        requireNoOverlap(delegate, null);
        requireNoCycle(delegate, null);
        delegate.setStatus(normalizeStatus(delegate.getStatus()));
        save(delegate);
        return delegate.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysPostDelegate delegate) {
        SysPostDelegate existing = getById(delegate.getId());
        if (existing == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位委托关系不存在或已删除");
        }
        validateShape(delegate);
        requirePostsValid(delegate);
        requireDeptValid(delegate);
        requireNoOverlap(delegate, delegate.getId());
        requireNoCycle(delegate, delegate.getId());
        delegate.setStatus(normalizeStatus(delegate.getStatus()));
        updateById(delegate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, String status) {
        SysPostDelegate existing = getById(id);
        if (existing == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位委托关系不存在或已删除");
        }
        String normalized = normalizeStatus(status);
        if (SysPostDelegate.STATUS_ENABLED.equals(normalized)) {
            // 启用 = 生效动作：重新执行同优先级重叠与循环校验（自委托/越权在形状校验已锁）
            SysPostDelegate probe = new SysPostDelegate();
            probe.setSourcePostId(existing.getSourcePostId());
            probe.setTargetPostId(existing.getTargetPostId());
            probe.setScopeType(existing.getScopeType());
            probe.setDeptId(existing.getDeptId());
            probe.setStatus(SysPostDelegate.STATUS_ENABLED);
            requirePostsValid(probe);
            requireDeptValid(probe);
            requireNoOverlap(probe, id);
            requireNoCycle(probe, id);
        }
        existing.setStatus(normalized);
        updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        removeById(id);
    }

    // ==================== 校验 ====================

    private void validateShape(SysPostDelegate delegate) {
        if (delegate.getSourcePostId() == null || delegate.getTargetPostId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位委托必须配置源岗位与受托岗位");
        }
        if (Objects.equals(delegate.getSourcePostId(), delegate.getTargetPostId())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "岗位委托不能自委托（源岗位=受托岗位）");
        }
        String scope = delegate.getScopeType() == null ? "" : delegate.getScopeType().toUpperCase();
        if (!SysPostDelegate.SCOPE_ORG.equals(scope) && !SysPostDelegate.SCOPE_DEPT.equals(scope)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "岗位委托范围只能是 ORG（组织默认）或 DEPT（精确部门）: " + delegate.getScopeType());
        }
        delegate.setScopeType(scope);
        if (SysPostDelegate.SCOPE_DEPT.equals(scope) && delegate.getDeptId() == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "精确部门范围委托必须指定部门");
        }
        if (SysPostDelegate.SCOPE_ORG.equals(scope)) {
            delegate.setDeptId(null);
        }
    }

    private void requirePostsValid(SysPostDelegate delegate) {
        SysPost source = sysPostMapper.selectById(delegate.getSourcePostId());
        if (source == null || source.getStatus() == null || source.getStatus() != POST_STATUS_ENABLED) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "源岗位不存在或已停用");
        }
        SysPost target = sysPostMapper.selectById(delegate.getTargetPostId());
        if (target == null || target.getStatus() == null || target.getStatus() != POST_STATUS_ENABLED) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "受托岗位不存在或已停用");
        }
    }

    private void requireDeptValid(SysPostDelegate delegate) {
        if (!SysPostDelegate.SCOPE_DEPT.equals(delegate.getScopeType())) {
            return;
        }
        SysDept dept = sysDeptMapper.selectById(delegate.getDeptId());
        if (dept == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "委托范围部门不存在");
        }
    }

    /** 同优先级重叠拒绝：同源岗位 ORG 唯一；同源岗位+同部门 DEPT 唯一（跨优先级并存合法）。 */
    private void requireNoOverlap(SysPostDelegate delegate, Long excludeId) {
        if (!SysPostDelegate.STATUS_ENABLED.equals(normalizeStatus(delegate.getStatus()))) {
            return;
        }
        LambdaQueryWrapper<SysPostDelegate> wrapper = new LambdaQueryWrapper<SysPostDelegate>()
                .eq(SysPostDelegate::getSourcePostId, delegate.getSourcePostId())
                .eq(SysPostDelegate::getScopeType, delegate.getScopeType())
                .eq(SysPostDelegate::getStatus, SysPostDelegate.STATUS_ENABLED);
        if (SysPostDelegate.SCOPE_DEPT.equals(delegate.getScopeType())) {
            wrapper.eq(SysPostDelegate::getDeptId, delegate.getDeptId());
        }
        if (excludeId != null) {
            wrapper.ne(SysPostDelegate::getId, excludeId);
        }
        Long overlapped = baseMapper.selectCount(wrapper);
        if (overlapped != null && overlapped > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "该源岗位在相同适用范围已存在有效委托关系（同优先级重叠拒绝）");
        }
    }

    /**
     * 循环拒绝与跳数预检：从受托岗位沿 ENABLED 关系向前走，
     * 回到源岗位=循环拒绝；超过 4 跳的链同样拒绝（不可部署配置）。
     *
     * @param excludeId 更新场景排除自身行
     */
    private void requireNoCycle(SysPostDelegate delegate, Long excludeId) {
        if (!SysPostDelegate.STATUS_ENABLED.equals(normalizeStatus(delegate.getStatus()))) {
            return;
        }
        Long sourcePostId = delegate.getSourcePostId();
        Long cursor = delegate.getTargetPostId();
        Set<Long> visited = new HashSet<>();
        visited.add(sourcePostId);
        for (int hop = 1; hop <= MAX_HOPS; hop++) {
            if (!visited.add(cursor)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "岗位委托链存在循环（自委托/循环拒绝）");
            }
            if (cursor.equals(sourcePostId)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "岗位委托链存在循环（回到源岗位拒绝）");
            }
            List<SysPostDelegate> next = baseMapper.selectList(
                    new LambdaQueryWrapper<SysPostDelegate>()
                            .eq(SysPostDelegate::getSourcePostId, cursor)
                            .eq(SysPostDelegate::getStatus, SysPostDelegate.STATUS_ENABLED));
            if (next.isEmpty()) {
                return;
            }
            boolean reachesSource = next.stream().anyMatch(rule ->
                    (excludeId == null || !excludeId.equals(rule.getId()))
                            && sourcePostId.equals(rule.getTargetPostId()));
            if (reachesSource) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "岗位委托链存在循环（回到源岗位拒绝）");
            }
            if (hop == MAX_HOPS) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "岗位委托链超过最大 " + MAX_HOPS + " 跳，已拒绝保存");
            }
            cursor = next.get(0).getTargetPostId();
        }
    }

    private String normalizeStatus(String status) {
        String value = status == null ? SysPostDelegate.STATUS_ENABLED : status.toUpperCase();
        if (!SysPostDelegate.STATUS_ENABLED.equals(value)
                && !SysPostDelegate.STATUS_DISABLED.equals(value)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "岗位委托状态只能是 ENABLED/DISABLED: " + status);
        }
        return value;
    }
}
