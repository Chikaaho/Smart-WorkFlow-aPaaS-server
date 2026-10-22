package com.sw.ck.system.service;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysRoleMenu;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysTenantMapper;
import com.sw.ck.system.mapper.SysTenantStatusAuditMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T020 租户管理行为证据：授权边界、租户 0 保留、原子初始化与启停入参校验。
 * 数据库层由真实迁移与实体映射承担，本测试锁定服务层可观察行为。
 */
@DisplayName("T020 租户管理服务行为")
class TenantAdminServiceTest {

    private final SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
    private final SysDeptMapper deptMapper = mock(SysDeptMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
    private final SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
    private final SysTenantStatusAuditMapper statusAuditMapper = mock(SysTenantStatusAuditMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final TenantAdminService service = new TenantAdminService(
            tenantMapper, deptMapper, userMapper, roleMapper, roleMenuMapper, userRoleMapper, statusAuditMapper, passwordEncoder,
            mock(PlatformTransactionManager.class));

    @BeforeEach
    void setUp() {
        LoginUser login = new LoginUser();
        login.setUserId(1L);
        login.setUsername("admin");
        login.setTenantId(0L);
        login.setSuperAdmin(true);
        LoginUserHolder.set(login);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    void nonSuperTenantCannotManageTenants() {
        LoginUser login = new LoginUser();
        login.setTenantId(7L);
        login.setSuperAdmin(false);
        LoginUserHolder.set(login);

        assertThatThrownBy(() -> service.page(1, 10, null, null))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("超级管理员");
    }

    @Test
    void createInitializesTenantAdminDeptAndRoleTemplateAtomically() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded");
        when(tenantMapper.updateById(any(SysTenant.class))).thenReturn(1);
        when(deptMapper.updateById(any(SysDept.class))).thenReturn(1);
        SysRole template = new SysRole();
        template.setId(2L);
        template.setName("管理员");
        template.setCode("admin");
        template.setSort(10);
        template.setStatus(1);
        template.setDataScope(0);
        when(roleMapper.selectOne(any())).thenReturn(template);
        SysRoleMenu menuA = new SysRoleMenu();
        menuA.setMenuId(10L);
        SysRoleMenu menuB = new SysRoleMenu();
        menuB.setMenuId(11L);
        when(roleMenuMapper.selectList(any())).thenReturn(List.of(menuA, menuB));

        doAnswer(invocation -> {
            ((SysTenant) invocation.getArgument(0)).setId(100L);
            return 1;
        }).when(tenantMapper).insert(any(SysTenant.class));
        doAnswer(invocation -> {
            ((SysDept) invocation.getArgument(0)).setId(200L);
            return 1;
        }).when(deptMapper).insert(any(SysDept.class));
        doAnswer(invocation -> {
            ((SysUser) invocation.getArgument(0)).setId(300L);
            return 1;
        }).when(userMapper).insert(any(SysUser.class));
        doAnswer(invocation -> {
            ((SysRole) invocation.getArgument(0)).setId(400L);
            return 1;
        }).when(roleMapper).insert(any(SysRole.class));

        Long tenantId = service.create(new TenantAdminService.CreateTenantCommand("深圳中心", "sz_admin", "secret123", "tenant-request-0001"));

        assertThat(tenantId).isEqualTo(100L);
        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        SysUser admin = userCaptor.getValue();
        assertThat(admin.getTenantId()).isEqualTo(100L);
        assertThat(admin.getUsername()).isEqualTo("sz_admin");
        assertThat(admin.getPassword()).isEqualTo("encoded");
        assertThat(admin.getDeptId()).isEqualTo(200L);
        ArgumentCaptor<SysRole> roleCaptor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getTenantId()).isEqualTo(100L);
        assertThat(roleCaptor.getValue().getCode()).isEqualTo("admin");
        verify(roleMenuMapper, times(2)).insert(any(SysRoleMenu.class));
        ArgumentCaptor<SysUserRole> bindingCaptor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(bindingCaptor.capture());
        assertThat(bindingCaptor.getValue().getTenantId()).isEqualTo(100L);
        assertThat(bindingCaptor.getValue().getUserId()).isEqualTo(300L);
        assertThat(bindingCaptor.getValue().getRoleId()).isEqualTo(400L);
        ArgumentCaptor<SysTenant> tenantCaptor = ArgumentCaptor.forClass(SysTenant.class);
        verify(tenantMapper).updateById(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getAdminUserId()).isEqualTo(300L);
        assertThat(tenantCaptor.getValue().getCreationRequestId()).isEqualTo("tenant-request-0001");
    }

    @Test
    void repeatedCreateReturnsOriginalTenantWithoutCreatingAnotherAdmin() {
        SysTenant existing = new SysTenant();
        existing.setId(91L);
        when(tenantMapper.selectOne(any())).thenReturn(existing);
        assertThat(service.create(new TenantAdminService.CreateTenantCommand(
                "深圳中心", "sz_admin", "secret123", "tenant-request-0001"))).isEqualTo(91L);
        verify(tenantMapper, never()).insert(any(SysTenant.class));
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void concurrentDuplicateReturnsCommittedTenantAfterSavepointRollback() {
        SysTenant existing = new SysTenant();
        existing.setId(91L);
        when(tenantMapper.selectOne(any())).thenReturn(null, existing);
        when(tenantMapper.insert(any(SysTenant.class))).thenThrow(new DuplicateKeyException("request"));
        assertThat(service.create(new TenantAdminService.CreateTenantCommand(
                "深圳中心", "sz_admin", "secret123", "tenant-request-0001"))).isEqualTo(91L);
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void missingRequestIdIsRejectedBeforeWriting() {
        assertThatThrownBy(() -> service.create(new TenantAdminService.CreateTenantCommand(
                "深圳中心", "sz_admin", "secret123", null))).isInstanceOf(BaseException.class)
                .hasMessageContaining("请求标识");
        verify(tenantMapper, never()).insert(any(SysTenant.class));
    }

    @Test
    void listUsesStableAdminIdentityAndFailsClosedOnCrossTenantLink() {
        SysTenant tenant = new SysTenant();
        tenant.setId(91L);
        tenant.setAdminUserId(301L);
        when(tenantMapper.selectGlobalById(91L)).thenReturn(tenant);
        SysUser admin = new SysUser();
        admin.setId(301L);
        admin.setTenantId(91L);
        admin.setStatus(0);
        admin.setUsername("renamed_admin");
        when(userMapper.selectById(301L)).thenReturn(admin);
        assertThat(service.detail(91L).adminUsername()).isEqualTo("renamed_admin");
        assertThat(service.detail(91L).adminUserId()).isEqualTo(301L);
        verify(roleMapper, never()).selectList(any());
        admin.setTenantId(92L);
        assertThat(service.detail(91L).adminUsername()).isNull();
    }

    @Test
    void rootTenantCannotBeRenamedOrDisabled() {
        SysTenant root = new SysTenant();
        root.setId(0L);
        root.setName("超管");
        root.setStatus(0);
        when(tenantMapper.selectGlobalById(0L)).thenReturn(root);

        assertThatThrownBy(() -> service.rename(0L, "新名字"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("保留对象");
        assertThatThrownBy(() -> service.updateStatus(0L, 1, "保留对象验证"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("保留对象");
        verify(tenantMapper, never()).updateById(any(SysTenant.class));
    }

    @Test
    void statusMustBeEnableOrDisable() {
        assertThatThrownBy(() -> service.updateStatus(1L, 2, "非法状态验证"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("启用或停用");
    }

    @Test
    void statusChangeRequiresReasonAndWritesAuditableResult() {
        SysTenant tenant = new SysTenant();
        tenant.setId(93L);
        tenant.setStatus(0);
        when(tenantMapper.selectGlobalById(93L)).thenReturn(tenant);
        when(tenantMapper.updateById(tenant)).thenReturn(1);

        assertThatThrownBy(() -> service.updateStatus(93L, 1, "  "))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("变更原因");
        service.updateStatus(93L, 1, "季度暂停");

        ArgumentCaptor<com.sw.ck.system.entity.SysTenantStatusAudit> audit =
                ArgumentCaptor.forClass(com.sw.ck.system.entity.SysTenantStatusAudit.class);
        verify(statusAuditMapper).insert(audit.capture());
        assertThat(audit.getValue().getTenantId()).isEqualTo(93L);
        assertThat(audit.getValue().getAction()).isEqualTo("DISABLE");
        assertThat(audit.getValue().getOldStatus()).isEqualTo(0);
        assertThat(audit.getValue().getNewStatus()).isEqualTo(1);
        assertThat(audit.getValue().getReason()).isEqualTo("季度暂停");
        assertThat(audit.getValue().getOperatorId()).isEqualTo(1L);
        assertThat(audit.getValue().getResult()).isEqualTo("已停用");
    }
}

