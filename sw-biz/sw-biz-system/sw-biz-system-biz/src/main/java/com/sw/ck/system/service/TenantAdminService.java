package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
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
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 租户管理服务（T020 / DR02）。
 * <p>
 * sys_tenant 是全局表（无租户隔离语义），所有读取都在 {@link TenantLineSuspension} 下完成；
 * 新建租户时租户、根部门、首任管理员、角色模板复制与关联在同一事务内完成，失败零半成品。
 * 租户 0 是保留对象：名称不可改、状态不可改。
 * </p>
 */
@Service
public class TenantAdminService {

    /** 超级租户：系统超管与保留对象所在租户。 */
    private static final long ROOT_TENANT_ID = 0L;
    /** 管理员角色模板编码（从租户 0 复制规则，不复制人员与业务数据）。 */
    private static final String ADMIN_ROLE_CODE = "admin";

    private final SysTenantMapper tenantMapper;
    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public TenantAdminService(SysTenantMapper tenantMapper,
                              SysDeptMapper deptMapper,
                              SysUserMapper userMapper,
                              SysRoleMapper roleMapper,
                              SysRoleMenuMapper roleMenuMapper,
                              SysUserRoleMapper userRoleMapper,
                              PasswordEncoder passwordEncoder) {
        this.tenantMapper = tenantMapper;
        this.deptMapper = deptMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /** 新建租户命令。 */
    public record CreateTenantCommand(String name, String adminUsername, String adminPassword) {}

    /** 列表行。adminUsername 为空表示未配置首任管理员。 */
    public record TenantView(Long id, String code, String name, Long adminUserId, String adminUsername,
                             Integer status, java.time.LocalDateTime createTime) {}

    /** 分页查询（按名称模糊、状态精确）。 */
    public PageResult<TenantView> page(long pageNum, long pageSize, String name, Integer status) {
        assertSuperAdmin();
        long current = Math.max(pageNum, 1);
        long size = Math.min(Math.max(pageSize, 1), 200);
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            var wrapper = Wrappers.<SysTenant>lambdaQuery()
                    .like(name != null && !name.isBlank(), SysTenant::getName, name == null ? null : name.trim())
                    .eq(status != null, SysTenant::getStatus, status)
                    .orderByAsc(SysTenant::getId);
            Page<SysTenant> page = tenantMapper.selectPage(new Page<>(current, size), wrapper);
            List<TenantView> rows = new ArrayList<>(page.getRecords().size());
            for (SysTenant tenant : page.getRecords()) {
                rows.add(toView(tenant));
            }
            PageResult<TenantView> result = new PageResult<>();
            result.setRecords(rows);
            result.setTotal(page.getTotal());
            result.setPageNum(page.getCurrent());
            result.setPageSize(page.getSize());
            return result;
        }
    }

    /** 详情。 */
    public TenantView detail(Long id) {
        assertSuperAdmin();
        return toView(requireTenant(id));
    }

    /** 创建租户 + 首任管理员 + 根部门 + 管理员角色模板，一次事务完成。 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(CreateTenantCommand command) {
        assertSuperAdmin();
        if (command == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户信息不能为空");
        }
        String name = trim(command.name());
        String username = trim(command.adminUsername());
        String password = command.adminPassword();
        if (name.isEmpty() || name.length() > 64) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户名称不能为空且不超过 64 个字符");
        }
        if (username.isEmpty() || username.length() > 50 || username.matches(".*\\s.*")) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员账号不能为空、不能含空白且不超过 50 个字符");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "初始密码长度需为 6-64 个字符");
        }
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            Long existing = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getUsername, username));
            if (existing != null && existing > 0) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "管理员账号已存在");
            }
        }

        SysTenant tenant = new SysTenant();
        tenant.setName(name);
        tenant.setCode("t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        tenant.setStatus(0);
        tenant.setDescription("0.2.0 新建租户");
        tenantMapper.insert(tenant);
        Long tenantId = tenant.getId();

        SysDept rootDept = new SysDept();
        rootDept.setTenantId(tenantId);
        rootDept.setParentId(0L);
        rootDept.setName(name);
        rootDept.setCode("root");
        rootDept.setSort(0);
        rootDept.setStatus(0);
        deptMapper.insert(rootDept);

        SysUser admin = new SysUser();
        admin.setTenantId(tenantId);
        admin.setUsername(username);
        admin.setRealName(username);
        admin.setDeptId(rootDept.getId());
        admin.setStatus(0);
        admin.setIsAdmin(0);
        admin.setPassword(passwordEncoder.encode(password));
        userMapper.insert(admin);

        SysRole template = findAdminRoleTemplate();
        SysRole role = new SysRole();
        role.setTenantId(tenantId);
        role.setName(template.getName());
        role.setCode(template.getCode());
        role.setSort(template.getSort());
        role.setStatus(template.getStatus());
        role.setDataScope(template.getDataScope());
        role.setBuiltIn(false);
        role.setDescription("由租户 0 管理员角色模板复制");
        roleMapper.insert(role);

        List<SysRoleMenu> templateMenus;
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            templateMenus = roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                    .eq(SysRoleMenu::getRoleId, template.getId()));
        }
        for (SysRoleMenu templateMenu : templateMenus) {
            SysRoleMenu copy = new SysRoleMenu();
            copy.setTenantId(tenantId);
            copy.setRoleId(role.getId());
            copy.setMenuId(templateMenu.getMenuId());
            roleMenuMapper.insert(copy);
        }

        SysUserRole userRole = new SysUserRole();
        userRole.setTenantId(tenantId);
        userRole.setUserId(admin.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        return tenantId;
    }

    /** 修改租户名称（租户 0 只读）。 */
    @Transactional(rollbackFor = Exception.class)
    public void rename(Long id, String name) {
        assertSuperAdmin();
        SysTenant tenant = requireTenant(id);
        if (ROOT_TENANT_ID == tenant.getId()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "超管租户为保留对象，不能编辑");
        }
        String trimmed = trim(name);
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户名称不能为空且不超过 64 个字符");
        }
        tenant.setName(trimmed);
        tenantMapper.updateById(tenant);
    }

    /** 启用/停用（0=启用 1=停用；租户 0 只读）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, Integer status) {
        assertSuperAdmin();
        if (status == null || (status != 0 && status != 1)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户状态只能为启用或停用");
        }
        SysTenant tenant = requireTenant(id);
        if (ROOT_TENANT_ID == tenant.getId()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "超管租户为保留对象，不能停用");
        }
        tenant.setStatus(status);
        tenantMapper.updateById(tenant);
    }

    private SysTenant requireTenant(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户标识不能为空");
        }
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            SysTenant tenant = tenantMapper.selectGlobalById(id);
            if (tenant == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "租户不存在或已删除");
            }
            return tenant;
        }
    }

    private TenantView toView(SysTenant tenant) {
        return new TenantView(tenant.getId(), tenant.getCode(), tenant.getName(), null,
                resolveAdminUsername(tenant.getId()), tenant.getStatus(), tenant.getCreateTime());
    }

    /**
     * 从已有明确授权关系唯一确认首任管理员：同一租户内 code=admin 的角色恰有一个持有者时返回其账号。
     * 零个或多个候选一律返回 null（页面显示「未配置」），不随机取人，也不按账号文本猜测。
     */
    private String resolveAdminUsername(Long tenantId) {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            List<SysRole> adminRoles = roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, tenantId)
                    .eq(SysRole::getCode, ADMIN_ROLE_CODE));
            if (adminRoles.size() != 1) {
                return null;
            }
            List<SysUserRole> bindings = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                    .eq(SysUserRole::getTenantId, tenantId)
                    .eq(SysUserRole::getRoleId, adminRoles.get(0).getId()));
            if (bindings.size() != 1) {
                return null;
            }
            SysUser admin = userMapper.selectById(bindings.get(0).getUserId());
            return admin == null ? null : admin.getUsername();
        }
    }

    private SysRole findAdminRoleTemplate() {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            SysRole role = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, ROOT_TENANT_ID)
                    .eq(SysRole::getCode, ADMIN_ROLE_CODE)
                    .last("LIMIT 1"));
            if (role == null) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "缺少管理员角色模板，无法初始化租户");
            }
            return role;
        }
    }

    /** 只有租户 0 的系统超级管理员可以维护租户。 */
    private void assertSuperAdmin() {
        LoginUser login = LoginUserHolder.get();
        if (login == null || !login.isSuperAdmin() || !Long.valueOf(ROOT_TENANT_ID).equals(login.getTenantId())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "仅系统超级管理员可以维护租户");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

