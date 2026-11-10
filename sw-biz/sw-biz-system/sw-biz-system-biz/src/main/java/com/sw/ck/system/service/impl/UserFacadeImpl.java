package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.jdbc.JdbcMaps;
import com.sw.ck.system.api.user.UserOptionDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * UserQueryFacade 实现。
 * <p>
 * 其它模块通过 {@link UserQueryFacade} 接口查询用户候选，
 * 禁止直接访问 sys_user 表或 Mapper。仅返回正常状态（status=0）用户，
 * 且 DTO 不含密码等敏感字段。
 * </p>
 * <p>
 * 模块内部调用边界返回非空 {@link Optional}：查询对象或租户上下文缺失以 empty 表达，
 * 合法零匹配以 present 的空集合/空 Map 表达，两者不可互换。
 * </p>
 */
@Service
public class UserFacadeImpl implements UserQueryFacade {

    /** 用户状态：0=正常（对齐 sys_user_status 字典与 V4 种子口径） */
    private static final int STATUS_ACTIVE = 0;

    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;

    public UserFacadeImpl(SysUserMapper sysUserMapper, SysDeptMapper sysDeptMapper) {
        this.sysUserMapper = sysUserMapper;
        this.sysDeptMapper = sysDeptMapper;
    }

    @Override
    public Optional<List<UserOptionDTO>> searchActiveUsers(String keyword, int limit) {
        return searchActiveUsers(keyword, limit, false);
    }

    @Override
    public Optional<List<UserOptionDTO>> searchActiveUsers(String keyword, int limit,
                                                           boolean includeDept) {
        String kw = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(limit, 1);
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName,
                        SysUser::getDeptId)
                .eq(SysUser::getStatus, STATUS_ACTIVE)
                .and(!kw.isEmpty(), w -> w.like(SysUser::getUsername, kw)
                        .or().like(SysUser::getRealName, kw))
                .orderByAsc(SysUser::getId)
                .last("LIMIT " + safeLimit));
        // 关键字空白=列出全部；本契约恒 present，零匹配为空集合
        return Optional.of(users.stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getRealName(),
                        includeDept ? u.getDeptId() : null))
                .toList());
    }

    /**
     * 审批历史所属部门：按用户主部门批量解析（只读、脱敏；无主部门/停用用户不返回）。
     */
    @Override
    public Optional<Map<Long, Long>> getUserDeptIds(Collection<Long> ids) {
        if (ids == null) {
            return Optional.empty();
        }
        if (ids.isEmpty()) {
            return Optional.of(Map.of());
        }
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getDeptId)
                .in(SysUser::getId, ids)
                .eq(SysUser::getStatus, STATUS_ACTIVE));
        Map<Long, Long> result = new java.util.LinkedHashMap<>();
        for (SysUser user : users) {
            if (user.getId() != null && user.getDeptId() != null) {
                result.put(user.getId(), user.getDeptId());
            }
        }
        return Optional.of(result);
    }

    /**
     * B021-027：审批人候选按组织架构筛选——部门 + 全部子孙部门内的正常状态用户。
     * deptId 为空时退回全量关键字搜索（与原行为一致）。
     */
    @Override
    public Optional<List<UserOptionDTO>> searchActiveUsersInDept(String keyword, Long deptId,
                                                                 int limit) {
        if (deptId == null) {
            return searchActiveUsers(keyword, limit, false);
        }
        String kw = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(limit, 1);
        List<Long> deptIds = new ArrayList<>();
        deptIds.add(deptId);
        deptIds.addAll(listDescendantDeptIds(deptId));
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName,
                        SysUser::getDeptId)
                .eq(SysUser::getStatus, STATUS_ACTIVE)
                .in(SysUser::getDeptId, deptIds)
                .and(!kw.isEmpty(), w -> w.like(SysUser::getUsername, kw)
                        .or().like(SysUser::getRealName, kw))
                .orderByAsc(SysUser::getId)
                .last("LIMIT " + safeLimit));
        return Optional.of(users.stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getRealName(),
                        u.getDeptId()))
                .toList());
    }

    /** 子孙部门 ID（环保护）：与 DeptScopeProviderImpl 同口径，单次查全量部门后层级遍历。 */
    private List<Long> listDescendantDeptIds(Long deptId) {
        Map<Long, List<Long>> childrenByParent = sysDeptMapper.selectList(null).stream()
                .filter(dept -> dept.getId() != null && dept.getParentId() != null)
                .collect(Collectors.groupingBy(SysDept::getParentId,
                        Collectors.mapping(SysDept::getId, Collectors.toList())));
        List<Long> result = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>(
                childrenByParent.getOrDefault(deptId, List.of()));
        Set<Long> visited = new HashSet<>();
        visited.add(deptId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (!visited.add(current)) {
                continue;
            }
            result.add(current);
            List<Long> children = childrenByParent.get(current);
            if (children != null) {
                stack.addAll(children);
            }
        }
        return result;
    }

    @Override
    public Optional<Map<Long, String>> getUserDisplayNames(Collection<Long> ids) {
        if (ids == null) {
            // 缺少查询对象：查询未执行
            return Optional.empty();
        }
        if (ids.isEmpty()) {
            // 查询已执行且零匹配（合法零结果）
            return Optional.of(Map.of());
        }
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .in(SysUser::getId, ids));
        return Optional.of(users.stream()
                .collect(Collectors.toMap(SysUser::getId, u ->
                                u.getRealName() != null && !u.getRealName().isBlank()
                                        ? u.getRealName() : u.getUsername(),
                        (a, b) -> a, java.util.LinkedHashMap::new)));
    }

    @Override
    public Optional<List<Long>> findActiveUserIds(Collection<Long> ids) {
        if (ids == null) {
            return Optional.empty();
        }
        var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null) {
            // 无登录租户上下文：无法确定租户范围
            return Optional.empty();
        }
        return findActiveUserIds(ids, loginUser.getTenantId());
    }

    @Override
    public Optional<List<Long>> findActiveUserIds(Collection<Long> ids, Long tenantId) {
        if (ids == null || tenantId == null) {
            // 查询对象或租户上下文缺失，查询未执行
            return Optional.empty();
        }
        if (ids.isEmpty()) {
            return Optional.of(List.of());
        }
        return Optional.of(sysUserMapper.selectActiveUserIds(List.copyOf(ids), tenantId));
    }

    @Override
    public Optional<List<Long>> findActiveUserIdsByRoleCodes(Collection<String> roleCodes) {
        if (roleCodes == null) {
            return Optional.empty();
        }
        var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        if (loginUser == null || loginUser.getTenantId() == null) {
            // 无登录租户上下文：无法确定租户范围
            return Optional.empty();
        }
        return findActiveUserIdsByRoleCodes(roleCodes, loginUser.getTenantId());
    }

    @Override
    public Optional<List<Long>> findActiveUserIdsByRoleCodes(Collection<String> roleCodes, Long tenantId) {
        if (roleCodes == null || tenantId == null) {
            // 角色集合或租户上下文缺失，查询未执行
            return Optional.empty();
        }
        if (roleCodes.isEmpty()) {
            return Optional.of(List.of());
        }
        return Optional.of(sysUserMapper.selectActiveUserIdsByRoleCodes(List.copyOf(roleCodes), tenantId));
    }

    @Override
    public Optional<List<Long>> findActiveUserIdsByDeptLeaders(Collection<Long> deptIds, Long tenantId) {
        if (deptIds == null || tenantId == null) {
            // 部门集合或租户上下文缺失，查询未执行
            return Optional.empty();
        }
        List<Long> distinct = deptIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Optional.of(List.of());
        }
        return Optional.of(sysUserMapper.selectActiveUserIdsByDeptLeaders(distinct, tenantId));
    }

    @Override
    public Optional<java.util.Map<Long, Long>> findDeptLeaderMap(Collection<Long> deptIds, Long tenantId) {
        if (deptIds == null || tenantId == null) {
            // 部门集合或租户上下文缺失，查询未执行
            return Optional.empty();
        }
        List<Long> distinct = deptIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Optional.of(java.util.Map.of());
        }
        java.util.Map<Long, Long> leaderByDept = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row
                : JdbcMaps.caseInsensitiveKeys(sysUserMapper.selectDeptLeaderMap(distinct, tenantId))) {
            Object deptId = row.get("dept_id");
            Object leaderId = row.get("leader_id");
            if (deptId instanceof Number d && leaderId instanceof Number l) {
                leaderByDept.putIfAbsent(d.longValue(), l.longValue());
            }
        }
        return Optional.of(leaderByDept);
    }

    @Override
    public Optional<List<Long>> findActiveUserIdsByPostCodes(Collection<String> postCodes, Long tenantId) {
        if (postCodes == null || tenantId == null) {
            // 岗位编码集合或租户上下文缺失，查询未执行
            return Optional.empty();
        }
        List<String> distinct = postCodes.stream()
                .filter(code -> code != null && !code.isBlank()).distinct().toList();
        if (distinct.isEmpty()) {
            return Optional.of(List.of());
        }
        return Optional.of(sysUserMapper.selectActiveUserIdsByPostCodes(distinct, tenantId));
    }

    @Override
    public Optional<List<Long>> findActiveUserIdsByDeptAndPost(Long deptId, String postCode, Long tenantId) {
        if (deptId == null || deptId <= 0 || postCode == null || postCode.isBlank() || tenantId == null) {
            // 部门/岗位/租户上下文缺失，查询未执行
            return Optional.empty();
        }
        return Optional.of(sysUserMapper.selectActiveUserIdsByDeptAndPost(deptId, postCode, tenantId));
    }
}
