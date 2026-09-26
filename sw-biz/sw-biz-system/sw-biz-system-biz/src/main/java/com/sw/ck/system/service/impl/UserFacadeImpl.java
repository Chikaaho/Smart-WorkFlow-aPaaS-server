package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.api.user.UserOptionDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public UserFacadeImpl(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    @Override
    public Optional<List<UserOptionDTO>> searchActiveUsers(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(limit, 1);
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .eq(SysUser::getStatus, STATUS_ACTIVE)
                .and(!kw.isEmpty(), w -> w.like(SysUser::getUsername, kw)
                        .or().like(SysUser::getRealName, kw))
                .orderByAsc(SysUser::getId)
                .last("LIMIT " + safeLimit));
        // 关键字空白=列出全部；本契约恒 present，零匹配为空集合
        return Optional.of(users.stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getRealName()))
                .toList());
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
