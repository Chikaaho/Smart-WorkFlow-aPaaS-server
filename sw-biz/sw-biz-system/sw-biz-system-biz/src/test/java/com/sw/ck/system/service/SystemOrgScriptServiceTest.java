package com.sw.ck.system.service;

import com.sw.ck.common.exception.BaseException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 表单脚本组织目录快照：只暴露启用角色/岗位与有效关联。
 */
class SystemOrgScriptServiceTest {

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("快照只包含启用角色/岗位和有效成员关系")
    void snapshotFiltersInactiveDirectoryAndRelations() {
        LoginUser user = new LoginUser();
        user.setUserId(7L);
        user.setTenantId(100L);
        LoginUserHolder.set(user);

        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysDeptMapper deptMapper = mock(SysDeptMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysPostMapper postMapper = mock(SysPostMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        SysUserPostMapper userPostMapper = mock(SysUserPostMapper.class);

        SysUser row = new SysUser();
        row.setId(7L);
        row.setTenantId(100L);
        row.setUsername("t100admin");
        row.setRealName("租户100管理员");
        row.setStatus(0);
        row.setDeptId(1001L);
        when(userMapper.selectList(any())).thenReturn(List.of(row));

        SysDept dept = new SysDept();
        dept.setId(1001L);
        dept.setTenantId(100L);
        dept.setName("总部");
        dept.setParentId(0L);
        dept.setStatus(0);
        when(deptMapper.selectList(any())).thenReturn(List.of(dept));

        SysRole activeRole = new SysRole();
        activeRole.setId(10L);
        activeRole.setTenantId(100L);
        activeRole.setCode("t100_admin");
        activeRole.setName("租户100管理员");
        activeRole.setStatus(1);
        when(roleMapper.selectList(any())).thenReturn(List.of(activeRole));

        SysPost activePost = new SysPost();
        activePost.setId(20L);
        activePost.setTenantId(100L);
        activePost.setCode("post_lead");
        activePost.setName("负责人");
        activePost.setStatus(1);
        when(postMapper.selectList(any())).thenReturn(List.of(activePost));

        SysUserRole activeRelation = new SysUserRole();
        activeRelation.setUserId(7L);
        activeRelation.setRoleId(10L);
        activeRelation.setTenantId(100L);
        SysUserRole disabledRoleRelation = new SysUserRole();
        disabledRoleRelation.setUserId(7L);
        disabledRoleRelation.setRoleId(11L);
        disabledRoleRelation.setTenantId(100L);
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(activeRelation, disabledRoleRelation));

        SysUserPost activePostRelation = new SysUserPost();
        activePostRelation.setUserId(7L);
        activePostRelation.setPostId(20L);
        activePostRelation.setTenantId(100L);
        SysUserPost disabledPostRelation = new SysUserPost();
        disabledPostRelation.setUserId(7L);
        disabledPostRelation.setPostId(21L);
        disabledPostRelation.setTenantId(100L);
        when(userPostMapper.selectList(any()))
                .thenReturn(List.of(activePostRelation, disabledPostRelation));

        SystemOrgScriptService service = new SystemOrgScriptService(userMapper, deptMapper,
                roleMapper, postMapper, userRoleMapper, userPostMapper);

        SystemOrgScriptService.OrgScriptContext context = service.load();

        assertEquals(1, context.roles().size());
        assertEquals("t100_admin", context.roles().get(0).code());
        assertEquals(1, context.posts().size());
        assertEquals("post_lead", context.posts().get(0).code());
        assertEquals(List.of("10"), context.users().get(0).roleIds());
        assertEquals(List.of("20"), context.users().get(0).postIds());
        assertEquals("1001", context.users().get(0).deptId());
        assertEquals(null, context.depts().get(0).parentId());
    }

    @Test
    @DisplayName("无登录租户上下文时拒绝")
    void rejectsMissingLoginContext() {
        SystemOrgScriptService service = new SystemOrgScriptService(
                mock(SysUserMapper.class), mock(SysDeptMapper.class), mock(SysRoleMapper.class),
                mock(SysPostMapper.class), mock(SysUserRoleMapper.class), mock(SysUserPostMapper.class));

        BaseException error = assertThrows(BaseException.class, service::load);

        assertEquals(401, error.getCode());
    }
}
