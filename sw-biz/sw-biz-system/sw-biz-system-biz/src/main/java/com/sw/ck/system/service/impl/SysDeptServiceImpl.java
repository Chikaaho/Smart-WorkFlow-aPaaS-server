package com.sw.ck.system.service.impl;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.service.DeptQuery;
import com.sw.ck.system.service.SysDeptService;
import com.sw.ck.system.service.SysUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部门 Service 实现。
 */
@Service
public class SysDeptServiceImpl
        extends BaseServiceImpl<SysDeptMapper, SysDept>
        implements SysDeptService {

    /** 部门状态：正常 */
    private static final int STATUS_NORMAL = 0;

    /** 部门状态：停用 */
    private static final int STATUS_DISABLED = 1;

    private final SysUserService sysUserService;

    public SysDeptServiceImpl(SysUserService sysUserService) {
        this.sysUserService = sysUserService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysDept dept) {
        validateParent(dept.getParentId(), null);
        validateLeader(dept.getLeaderId());
        if (dept.getStatus() == null) {
            dept.setStatus(STATUS_NORMAL);
        } else {
            validateStatus(dept.getStatus());
        }
        save(dept);
        return dept.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(SysDept dept) {
        SysDept existing = getById(dept.getId());
        if (existing == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "部门不存在或已删除");
        }
        validateParent(dept.getParentId(), dept.getId());
        validateLeader(dept.getLeaderId());
        if (dept.getStatus() != null) {
            validateStatus(dept.getStatus());
        }
        updateById(dept);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 校验：是否有子部门
        Long childCount = lambdaQuery().eq(SysDept::getParentId, id).count();
        if (childCount != null && childCount > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该部门存在子部门，无法删除");
        }
        // 校验：是否有在职用户
        Long userCount = sysUserService.lambdaQuery()
                .eq(SysUser::getDeptId, id)
                .eq(SysUser::getStatus, 0)
                .count();
        if (userCount != null && userCount > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "该部门下存在在职用户，无法删除");
        }
        removeById(id);
    }

    @Override
    public List<SysDept> listTree() {
        return lambdaQuery()
                .orderByAsc(SysDept::getSort)
                .list();
    }

    @Override
    public List<SysDept> listTree(DeptQuery query) {
        String name = query != null ? query.getName() : null;
        Integer status = query != null ? query.getStatus() : null;
        boolean hasName = name != null && !name.trim().isEmpty();
        boolean hasStatus = status != null;
        if (!hasName && !hasStatus) {
            // 无条件：与现状完全一致（同一查询路径、同一 SQL、同一排序），
            // 部门选择器等既有调用方零感知。
            return listTree();
        }
        if (hasStatus && status != STATUS_NORMAL && status != STATUS_DISABLED) {
            // 非法状态必须显式报错，禁止静默退化为全量查询
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "非法部门状态值：" + status + "，仅支持 0（正常）/1（停用）");
        }
        String trimmedName = hasName ? name.trim() : null;

        // 1) 直接命中集合：名称（包含匹配）+ 状态组合 AND。
        //    走标准 lambdaQuery 通道，租户 / 逻辑删除 / 数据范围拦截器自然生效。
        List<SysDept> hits = lambdaQuery()
                .like(hasName, SysDept::getName, trimmedName)
                .eq(hasStatus, SysDept::getStatus, status)
                .orderByAsc(SysDept::getSort)
                .list();

        // 2) 祖先补全：为每个命中节点沿 parentId 逐级上溯，直到 parentId=0 或链断；
        //    祖先同样通过 lambdaQuery（in 批量）获取——与直接命中同一授权查询通道，
        //    跨租户 / 已逻辑删除 / 不可见祖先查询不到即自然断链，不借机越权暴露。
        Map<Long, SysDept> byId = new LinkedHashMap<>();
        for (SysDept hit : hits) {
            byId.put(hit.getId(), hit);
        }
        Deque<Long> pendingParents = new ArrayDeque<>();
        for (SysDept hit : hits) {
            enqueueParentIfMissing(hit.getParentId(), byId, pendingParents);
        }
        while (!pendingParents.isEmpty()) {
            // 同一层级的祖先合并为一次 IN 查询；环保护：已收集的 id 不再重复入队
            Set<Long> batch = new HashSet<>();
            while (!pendingParents.isEmpty()) {
                Long parentId = pendingParents.pollFirst();
                if (parentId != null && !byId.containsKey(parentId)) {
                    batch.add(parentId);
                }
            }
            if (batch.isEmpty()) {
                continue;
            }
            List<SysDept> ancestors = lambdaQuery().in(SysDept::getId, batch).list();
            for (SysDept ancestor : ancestors) {
                if (byId.containsKey(ancestor.getId())) {
                    continue;
                }
                byId.put(ancestor.getId(), ancestor);
                enqueueParentIfMissing(ancestor.getParentId(), byId, pendingParents);
            }
        }

        // 3) 结果：去重（LinkedHashMap 按插入序），sort 升序稳定排序（与现状一致），
        //    sort 相同按 id 升序，保证排序确定性。
        return byId.values().stream()
                .sorted(Comparator.comparing(SysDept::getSort, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(SysDept::getId))
                .toList();
    }

    /**
     * 父 id 合法（非 null、非 0 根哨兵）且尚未收集时入队待查。
     */
    private void enqueueParentIfMissing(Long parentId, Map<Long, SysDept> byId, Deque<Long> pendingParents) {
        if (parentId != null && parentId != 0L && !byId.containsKey(parentId)) {
            pendingParents.addLast(parentId);
        }
    }

    /**
     * 父部门合法性：0/null = 根；否则必须为本租户内未删除部门；
     * 移动时禁止指向自身或自身子树（成环）。
     */
    private void validateParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "上级部门不能是自身");
        }
        SysDept parent = getById(parentId);
        if (parent == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "上级部门不存在或已删除");
        }
        if (selfId != null && isDescendant(selfId, parentId)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "上级部门不能是自身的子部门");
        }
    }

    /** 沿 parent 链向上走：从 candidateParent 出发若能回到 selfId，则它是 self 的子孙。 */
    private boolean isDescendant(Long selfId, Long candidateParent) {
        Set<Long> visited = new HashSet<>();
        Long cursor = candidateParent;
        while (cursor != null && cursor != 0L && visited.add(cursor)) {
            if (cursor.equals(selfId)) {
                return true;
            }
            SysDept node = getById(cursor);
            cursor = node == null ? null : node.getParentId();
        }
        return false;
    }

    /**
     * 负责人合法性：必须为本租户内未删除且正常状态（status=0）的用户
     *（跨租户 ID 经租户拦截器查询不到，自然拒绝）。
     */
    private void validateLeader(Long leaderId) {
        if (leaderId == null) {
            return;
        }
        SysUser leader = sysUserService.getById(leaderId);
        if (leader == null || leader.getStatus() == null || leader.getStatus() != STATUS_NORMAL) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "部门负责人不存在、已停用或无效");
        }
    }

    private void validateStatus(Integer status) {
        if (status == null || (status != STATUS_NORMAL && status != STATUS_DISABLED)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "非法部门状态值：" + status + "，仅支持 0（正常）/1（停用）");
        }
    }
}
