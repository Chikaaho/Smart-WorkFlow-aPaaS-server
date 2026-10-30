package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserPost;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserPostMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 表单脚本组织目录快照。
 * <p>
 * 只返回当前登录租户内启用用户、正常部门、启用角色与岗位，以及有效成员关系；
 * 不含密码、权限码、菜单或跨租户对象。脚本宿主据此同步查询角色/岗位及关联，
 * 未授权或不存在对象不暴露。
 * </p>
 */
@Service
public class SystemOrgScriptService {

    private static final int MAX_USERS = 500;

    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final SysPostMapper postMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserPostMapper userPostMapper;

    public SystemOrgScriptService(SysUserMapper userMapper,
                                  SysDeptMapper deptMapper,
                                  SysRoleMapper roleMapper,
                                  SysPostMapper postMapper,
                                  SysUserRoleMapper userRoleMapper,
                                  SysUserPostMapper userPostMapper) {
        this.userMapper = userMapper;
        this.deptMapper = deptMapper;
        this.roleMapper = roleMapper;
        this.postMapper = postMapper;
        this.userRoleMapper = userRoleMapper;
        this.userPostMapper = userPostMapper;
    }

    public OrgScriptContext load() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null || loginUser.getUserId() == null
                || loginUser.getTenantId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        Long tenantId = loginUser.getTenantId();

        List<SysUser> users = userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getStatus, 0)
                .orderByAsc(SysUser::getId)
                .last("LIMIT " + MAX_USERS));
        List<SysDept> depts = deptMapper.selectList(Wrappers.<SysDept>lambdaQuery()
                .eq(SysDept::getTenantId, tenantId)
                .eq(SysDept::getStatus, 0)
                .orderByAsc(SysDept::getParentId)
                .orderByAsc(SysDept::getSort)
                .orderByAsc(SysDept::getId));
        List<SysRole> roles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getTenantId, tenantId)
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getSort)
                .orderByAsc(SysRole::getId));
        List<SysPost> posts = postMapper.selectList(Wrappers.<SysPost>lambdaQuery()
                .eq(SysPost::getTenantId, tenantId)
                .eq(SysPost::getStatus, 1)
                .orderByAsc(SysPost::getSort)
                .orderByAsc(SysPost::getId));

        Set<Long> activeRoleIds = roles.stream().map(SysRole::getId).collect(Collectors.toSet());
        Set<Long> activePostIds = posts.stream().map(SysPost::getId).collect(Collectors.toSet());
        Map<Long, List<String>> roleIdsByUser = new HashMap<>();
        Map<Long, List<String>> postIdsByUser = new HashMap<>();
        for (SysUserRole relation : userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getTenantId, tenantId))) {
            if (relation.getUserId() != null && activeRoleIds.contains(relation.getRoleId())) {
                roleIdsByUser.computeIfAbsent(relation.getUserId(), key -> new ArrayList<>())
                        .add(String.valueOf(relation.getRoleId()));
            }
        }
        for (SysUserPost relation : userPostMapper.selectList(Wrappers.<SysUserPost>lambdaQuery()
                .eq(SysUserPost::getTenantId, tenantId))) {
            if (relation.getUserId() != null && activePostIds.contains(relation.getPostId())) {
                postIdsByUser.computeIfAbsent(relation.getUserId(), key -> new ArrayList<>())
                        .add(String.valueOf(relation.getPostId()));
            }
        }

        List<OrgUser> userRows = users.stream()
                .map(user -> new OrgUser(
                        String.valueOf(user.getId()),
                        displayName(user),
                        user.getDeptId() == null ? null : String.valueOf(user.getDeptId()),
                        roleIdsByUser.getOrDefault(user.getId(), List.of()),
                        postIdsByUser.getOrDefault(user.getId(), List.of())))
                .toList();
        List<OrgDept> deptRows = depts.stream()
                .map(dept -> new OrgDept(
                        String.valueOf(dept.getId()),
                        dept.getName(),
                        dept.getParentId() == null || dept.getParentId() == 0L
                                ? null
                                : String.valueOf(dept.getParentId())))
                .toList();
        List<OrgRole> roleRows = roles.stream()
                .map(role -> new OrgRole(String.valueOf(role.getId()), role.getCode(), role.getName()))
                .toList();
        List<OrgPost> postRows = posts.stream()
                .map(post -> new OrgPost(String.valueOf(post.getId()), post.getCode(), post.getName()))
                .toList();
        return new OrgScriptContext(userRows, deptRows, roleRows, postRows);
    }

    private String displayName(SysUser user) {
        return user.getRealName() == null || user.getRealName().isBlank()
                ? user.getUsername()
                : user.getRealName();
    }

    public record OrgScriptContext(List<OrgUser> users,
                                   List<OrgDept> depts,
                                   List<OrgRole> roles,
                                   List<OrgPost> posts) {
    }

    public record OrgUser(String id, String name, String deptId,
                          List<String> roleIds, List<String> postIds) {
    }

    public record OrgDept(String id, String name, String parentId) {
    }

    public record OrgRole(String id, String code, String name) {
    }

    public record OrgPost(String id, String code, String name) {
    }
}
