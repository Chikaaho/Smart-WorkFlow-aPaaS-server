package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.api.user.UserOptionDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * UserQueryFacade 实现。
 * <p>
 * 其它模块通过 {@link UserQueryFacade} 接口查询用户候选，
 * 禁止直接访问 sys_user 表或 Mapper。仅返回正常状态（status=0）用户，
 * 且 DTO 不含密码等敏感字段。
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
    public List<UserOptionDTO> searchActiveUsers(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        int safeLimit = Math.max(limit, 1);
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .eq(SysUser::getStatus, STATUS_ACTIVE)
                .and(!kw.isEmpty(), w -> w.like(SysUser::getUsername, kw)
                        .or().like(SysUser::getRealName, kw))
                .orderByAsc(SysUser::getId)
                .last("LIMIT " + safeLimit));
        return users.stream()
                .map(u -> new UserOptionDTO(u.getId(), u.getUsername(), u.getRealName()))
                .toList();
    }

    @Override
    public Map<Long, String> getUserDisplayNames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysUser> users = sysUserMapper.selectList(Wrappers.lambdaQuery(SysUser.class)
                .select(SysUser::getId, SysUser::getUsername, SysUser::getRealName)
                .in(SysUser::getId, ids));
        return users.stream()
                .collect(Collectors.toMap(SysUser::getId, u ->
                                u.getRealName() != null && !u.getRealName().isBlank()
                                        ? u.getRealName() : u.getUsername(),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @Override
    public List<Long> findActiveUserIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        return findActiveUserIds(ids, loginUser == null ? null : loginUser.getTenantId());
    }

    @Override
    public List<Long> findActiveUserIds(Collection<Long> ids, Long tenantId) {
        if (ids == null || ids.isEmpty() || tenantId == null) return List.of();
        return sysUserMapper.selectActiveUserIds(List.copyOf(ids), tenantId);
    }

    @Override
    public List<Long> findActiveUserIdsByRoleCodes(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return List.of();
        var loginUser = com.sw.ck.security.holder.LoginUserHolder.get();
        return findActiveUserIdsByRoleCodes(roleCodes, loginUser == null ? null : loginUser.getTenantId());
    }

    @Override
    public List<Long> findActiveUserIdsByRoleCodes(Collection<String> roleCodes, Long tenantId) {
        if (roleCodes == null || roleCodes.isEmpty() || tenantId == null) return List.of();
        return sysUserMapper.selectActiveUserIdsByRoleCodes(List.copyOf(roleCodes), tenantId);
    }
}
