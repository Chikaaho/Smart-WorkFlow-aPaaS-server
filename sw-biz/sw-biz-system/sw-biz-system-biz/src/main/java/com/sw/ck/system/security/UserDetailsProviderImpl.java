package com.sw.ck.system.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.entity.SysMenu;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysRoleDept;
import com.sw.ck.system.entity.SysRoleMenu;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.entity.SysUserRole;
import com.sw.ck.system.mapper.SysMenuMapper;
import com.sw.ck.system.mapper.SysRoleDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.service.SysUserService;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * sw-security SPI 实现：从组织架构 SysUser 表加载用户认证信息。
 * <p>
 * roles = 用户经 sys_user_role 关联的已启用 sys_role.code 集合；
 * superAdmin = roles 是否包含 'superadmin'；
 * permissions = superAdmin 时为空数组（旁路），否则经 sys_role_menu→sys_menu 取
 * 已授权菜单上 permission 非空白的标识（含页面节点与按钮节点）。
 * </p>
 * <p>
 * dataScope = 用户全部已启用角色 dataScope 的最宽档（多角色取最宽，排序按枚举名：
 * ALL &gt; DEPT_AND_CHILD &gt; CUSTOM &gt; DEPT &gt; SELF；任一角授予"本部门及以下"
 * 宽于单点自定义集合）。有效档为 CUSTOM 时，customDeptIds = 该用户全部 CUSTOM 角色
 * 经 sys_role_dept 关联部门的并集；其余档位 customDeptIds 置空。
 * </p>
 */
public class UserDetailsProviderImpl implements UserDetailsProvider {

    private static final String SUPER_ADMIN_ROLE_CODE = "superadmin";

    /**
     * 数据范围宽窄排序（按枚举名比较，不按 ordinal 猜测）：
     * ALL > DEPT_AND_CHILD > CUSTOM > DEPT > SELF。
     */
    private static final Map<DataScope, Integer> SCOPE_RANK = Map.of(
            DataScope.ALL, 5,
            DataScope.DEPT_AND_CHILD, 4,
            DataScope.CUSTOM, 3,
            DataScope.DEPT, 2,
            DataScope.SELF, 1);

    private final SysUserService sysUserService;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysMenuMapper sysMenuMapper;
    private final SysRoleDeptMapper sysRoleDeptMapper;

    public UserDetailsProviderImpl(SysUserService sysUserService,
                                   SysUserRoleMapper sysUserRoleMapper,
                                   SysRoleMapper sysRoleMapper,
                                   SysRoleMenuMapper sysRoleMenuMapper,
                                   SysMenuMapper sysMenuMapper,
                                   SysRoleDeptMapper sysRoleDeptMapper) {
        this.sysUserService = sysUserService;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysRoleMenuMapper = sysRoleMenuMapper;
        this.sysMenuMapper = sysMenuMapper;
        this.sysRoleDeptMapper = sysRoleDeptMapper;
    }

    @Override
    public LoginUser loadByUsername(String username) {
        SysUser user = sysUserService.getByUsername(username);
        if (!isActive(user)) {
            return null;
        }
        return toLoginUser(user);
    }

    @Override
    public LoginUser loadByUserId(Long userId) {
        SysUser user = sysUserService.getById(userId);
        if (!isActive(user)) {
            return null;
        }
        return toLoginUser(user);
    }

    /** 逻辑删除用户不会被 getById 返回；非 0 状态也不得进入认证上下文。 */
    private boolean isActive(SysUser user) {
        return user != null && (user.getStatus() == null || user.getStatus() == 0);
    }

    private LoginUser toLoginUser(SysUser user) {
        // 1. 查询用户关联的角色
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class)
                        .eq(SysUserRole::getUserId, user.getId()));

        List<String> roleCodes;
        boolean superAdmin;
        List<SysRole> roles = Collections.emptyList();

        if (userRoles.isEmpty()) {
            roleCodes = Collections.emptyList();
            superAdmin = false;
        } else {
            List<Long> roleIds = userRoles.stream()
                    .map(SysUserRole::getRoleId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // 2. 加载已启用角色的 code 与 dataScope
            roles = sysRoleMapper.selectList(
                    Wrappers.lambdaQuery(SysRole.class)
                            .in(SysRole::getId, roleIds)
                            .eq(SysRole::getStatus, 1));

            roleCodes = roles.stream()
                    .map(SysRole::getCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            superAdmin = roleCodes.contains(SUPER_ADMIN_ROLE_CODE);
        }

        // 3. 数据范围：多角色取最宽；无角色默认 ALL（与历史硬编码行为一致）
        DataScope dataScope = resolveWidestScope(roles);
        Set<Long> customDeptIds = dataScope == DataScope.CUSTOM
                ? loadCustomDeptIds(roles)
                : Collections.emptySet();

        // 4. 装配 LoginUser
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setUsername(user.getUsername());
        loginUser.setDeptId(user.getDeptId());
        loginUser.setTenantId(user.getTenantId());
        loginUser.setRoles(roleCodes);
        loginUser.setDataScope(dataScope);
        loginUser.setCustomDeptIds(customDeptIds);

        if (superAdmin) {
            loginUser.setSuperAdmin(true);
            // 超管旁路权限：前端 hasPerm = superAdmin || hasPermission，空数组即可
            loginUser.setPermissions(Collections.emptyList());
        } else {
            loginUser.setSuperAdmin(false);
            loginUser.setPermissions(loadPermissions(user));
        }

        return loginUser;
    }

    /**
     * 多角色取最宽档。空角色集合返回 {@link DataScope#ALL}（与历史硬编码默认一致）。
     */
    private DataScope resolveWidestScope(List<SysRole> roles) {
        DataScope widest = null;
        int widestRank = -1;
        for (SysRole role : roles) {
            DataScope scope = toDataScope(role.getDataScope());
            int rank = SCOPE_RANK.getOrDefault(scope, 0);
            if (rank > widestRank) {
                widestRank = rank;
                widest = scope;
            }
        }
        return widest != null ? widest : DataScope.ALL;
    }

    /**
     * 将 sys_role.data_scope 的 smallint 值映射为 {@link DataScope} 枚举（按 ordinal）。
     * null（未配置，DB 列 default 0）与越界值均按 ALL 处理，与 DB 默认值语义一致。
     */
    private DataScope toDataScope(Integer ordinal) {
        if (ordinal == null) {
            return DataScope.ALL;
        }
        DataScope[] values = DataScope.values();
        if (ordinal >= 0 && ordinal < values.length) {
            return values[ordinal];
        }
        return DataScope.ALL;
    }

    /**
     * 该用户全部 CUSTOM 角色（已启用）经 sys_role_dept 关联部门的并集。
     * 仅在有效档为 CUSTOM 时调用。
     */
    private Set<Long> loadCustomDeptIds(List<SysRole> roles) {
        List<Long> customRoleIds = roles.stream()
                .filter(role -> toDataScope(role.getDataScope()) == DataScope.CUSTOM)
                .map(SysRole::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (customRoleIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<SysRoleDept> roleDepts = sysRoleDeptMapper.selectList(
                Wrappers.lambdaQuery(SysRoleDept.class)
                        .in(SysRoleDept::getRoleId, customRoleIds));
        Set<Long> deptIds = new HashSet<>();
        for (SysRoleDept roleDept : roleDepts) {
            if (roleDept.getDeptId() != null) {
                deptIds.add(roleDept.getDeptId());
            }
        }
        return deptIds;
    }

    /**
     * 加载非超管用户的菜单权限标识。
     * 路径：sys_user_role → sys_role（仅启用）→ sys_role_menu → sys_menu（permission 非空，不限 menu_type）
     * <p>
     * 与 {@code SysMenuServiceImpl.loadMenuIdsByUserId} 的菜单过滤保持一致：停用角色
     * （status=0）不贡献按钮 permission，保证「角色停用 = 有效撤权」的授权语义对称。
     * </p>
     */
    private List<String> loadPermissions(SysUser user) {
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                Wrappers.lambdaQuery(SysUserRole.class)
                        .eq(SysUserRole::getUserId, user.getId()));
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> roleIds = userRoles.stream()
                .map(SysUserRole::getRoleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 仅保留启用角色（status=1）：停用角色不贡献按钮 permission（与 roles 装配同源过滤）
        List<SysRole> activeRoles = sysRoleMapper.selectList(
                Wrappers.lambdaQuery(SysRole.class)
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getStatus, 1));
        List<Long> activeRoleIds = activeRoles.stream()
                .map(SysRole::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (activeRoleIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                Wrappers.lambdaQuery(SysRoleMenu.class)
                        .in(SysRoleMenu::getRoleId, activeRoleIds));
        if (roleMenus.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> menuIds = roleMenus.stream()
                .map(SysRoleMenu::getMenuId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 取角色已授权菜单中所有 permission 非空白的记录。
        // 不按 menu_type 过滤：页面节点（menu_type=1，如 V15 的 system:user:list）
        // 与按钮节点（menu_type=2，如 job:create）都承载权限标识，
        // 只收按钮会让非超管永远拿不到页面级 list 权限（全部 403）。
        List<SysMenu> menus = sysMenuMapper.selectList(
                Wrappers.lambdaQuery(SysMenu.class)
                        .in(SysMenu::getId, menuIds)
                        .isNotNull(SysMenu::getPermission)
                        .ne(SysMenu::getPermission, ""));

        return menus.stream()
                .map(SysMenu::getPermission)
                .distinct()
                .collect(Collectors.toList());
    }
}
