package com.sw.ck.system.orgfoundation;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.service.SysDeptService;
import com.sw.ck.system.service.SysRoleService;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.impl.SysDeptServiceImpl;
import com.sw.ck.system.service.impl.SysRoleServiceImpl;
import com.sw.ck.system.service.impl.SysUserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * I1 权限即时收敛集成测试（方向 §3.1：停用或撤权后权限一致收敛）。
 * <p>
 * 停用/编辑/删号、撤权/授权、角色启停/授权变更/删除都必须立即踢出受影响
 * 用户的登录缓存（{@link LoginUserLoader#kickOut}），下一次请求回查最新
 * 状态——停用用户被拒、撤权用户权限即时收缩，不等 TTL 自然过期。
 * </p>
 */
@SpringBootTest(
        classes = PermissionConvergenceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_i1_conv;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("I1 用户/角色变更权限即时收敛集成测试")
class PermissionConvergenceTest {

    @Autowired
    private SysUserService userService;

    @Autowired
    private SysRoleService roleService;

    @Autowired
    private SysDeptService deptService;

    @Autowired
    private LoginUserLoader loginUserLoader;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        reset(loginUserLoader);
        for (String table : List.of("sys_user_role", "sys_role_menu", "sys_role_dept",
                "sys_user_post", "sys_role", "sys_user", "sys_dept")) {
            jdbc.update("DELETE FROM " + table);
        }
        login(1L);
        jdbc.update("INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id, deleted) "
                + "VALUES (601, 0, '总部', 'D601', 10, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id, deleted) "
                + "VALUES (810, 'u810', 'x', 0, 601, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id, deleted) "
                + "VALUES (811, 'u811', 'x', 0, 601, 1, 0)");
        jdbc.update("INSERT INTO sys_role (id, name, code, sort, status, data_scope, built_in, tenant_id, deleted) "
                + "VALUES (801, '普通角色', 'R_COMMON', 10, 1, 0, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_role_menu (id, role_id, menu_id, tenant_id, deleted) "
                + "VALUES (9001, 801, 11, 1, 0)");
        jdbc.update("INSERT INTO sys_role_dept (id, role_id, dept_id, tenant_id, deleted) "
                + "VALUES (9100, 801, 601, 1, 0)");
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, deleted) "
                + "VALUES (9201, 810, 801, 1, 0)");
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, deleted) "
                + "VALUES (9202, 811, 801, 1, 0)");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("停用用户（编辑）立即踢出其登录缓存")
    void disableUser_shouldKickOut() {
        SysUser user = userService.getById(810L);
        user.setStatus(1);
        userService.update(user, (String) null);
        verify(loginUserLoader).kickOut(810L);
    }

    @Test
    @DisplayName("撤权（改角色）立即踢出该用户")
    void revokeRoles_shouldKickOut() {
        userService.updateRoleIds(810L, List.of());
        verify(loginUserLoader).kickOut(810L);
    }

    @Test
    @DisplayName("删除用户清理角色/岗位关联并踢出登录缓存")
    void deleteUser_shouldCleanupAndKickOut() {
        jdbc.update("INSERT INTO sys_user_post (id, user_id, post_id, dept_id, tenant_id, deleted) "
                + "VALUES (9301, 810, 0, 601, 1, 0)");
        userService.delete(810L);

        assertThat(userService.getById(810L)).isNull();
        Integer roleLinks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = 810 AND deleted = 0", Integer.class);
        Integer postLinks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_post WHERE user_id = 810 AND deleted = 0", Integer.class);
        assertThat(roleLinks).isZero();
        assertThat(postLinks).isZero();
        verify(loginUserLoader).kickOut(810L);
    }

    @Test
    @DisplayName("角色更新（含停用）立即踢出全部成员")
    void updateRole_shouldKickOutAllMembers() {
        SysRole role = roleService.getById(801L);
        role.setStatus(0);
        roleService.update(role);
        verify(loginUserLoader).kickOut(810L);
        verify(loginUserLoader).kickOut(811L);
    }

    @Test
    @DisplayName("角色菜单授权变更立即踢出全部成员")
    void updateRoleMenus_shouldKickOutAllMembers() {
        roleService.updateMenuIds(801L, List.of(12L));
        verify(loginUserLoader).kickOut(810L);
        verify(loginUserLoader).kickOut(811L);
    }

    @Test
    @DisplayName("删除角色清理菜单/部门/成员关联并踢出全部成员")
    void deleteRole_shouldCleanupAndKickOutMembers() {
        roleService.delete(801L);

        assertThat(roleService.getById(801L)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE role_id = 801 AND deleted = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 801 AND deleted = 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_dept WHERE role_id = 801 AND deleted = 0", Integer.class)).isZero();
        verify(loginUserLoader, atLeastOnce()).kickOut(810L);
        verify(loginUserLoader, atLeastOnce()).kickOut(811L);
    }

    @Test
    @DisplayName("成员视图：角色成员分页返回绑定用户")
    void pageMembers_shouldReturnBoundUsers() {
        var page = roleService.pageMembers(801L, new com.sw.ck.common.page.PageParam());
        assertThat(page.getRecords()).extracting(SysUser::getId)
                .containsExactlyInAnyOrder(810L, 811L);
    }

    @Test
    @DisplayName("superadmin 内置角色不可删除（负向护栏不回退）")
    void deleteBuiltinRole_shouldReject() {
        jdbc.update("INSERT INTO sys_role (id, name, code, sort, status, data_scope, built_in, tenant_id, deleted) "
                + "VALUES (802, '超管', 'superadmin', 0, 1, 0, 1, 1, 0)");
        assertThatThrownBy(() -> roleService.delete(802L)).isInstanceOf(BaseException.class);
        verify(loginUserLoader, never()).kickOut(802L);
    }

    @Test
    @DisplayName("用户名重复创建：业务异常拒绝，不触达唯一索引 500")
    void createUser_duplicateUsername_shouldReject() {
        SysUser dup = new SysUser();
        dup.setUsername("u810");
        dup.setStatus(0);
        dup.setDeptId(601L);
        assertThatThrownBy(() -> userService.create(dup, "x"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("部分更新（roleIds/posts 为 null）不得静默清空既有角色与任职")
    void updateWithAssociations_nullAssociations_shouldPreserve() {
        jdbc.update("INSERT INTO sys_user_post (id, user_id, post_id, dept_id, tenant_id, deleted) "
                + "VALUES (9302, 810, 0, 601, 1, 0)");
        SysUser patch = new SysUser();
        patch.setId(810L);
        patch.setUsername("u810");
        patch.setStatus(1);
        patch.setDeptId(601L);
        userService.updateWithAssociations(patch, null, null, null);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = 810 AND deleted = 0", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_post WHERE user_id = 810 AND deleted = 0", Integer.class))
                .isEqualTo(1);
    }

    private void login(Long tenantId) {
        LoginUser user = new LoginUser();
        user.setUserId(900L);
        user.setTenantId(tenantId);
        user.setDataScope(DataScope.ALL);
        LoginUserHolder.set(user);
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.system.mapper");
            return configurer;
        }

        @Bean
        public DeptScopeProvider deptScopeProvider(@Lazy SysDeptMapper sysDeptMapper) {
            return new DeptScopeProviderImpl(sysDeptMapper);
        }

        @Bean
        public SysUserService sysUserService(PasswordEncoder passwordEncoder, com.sw.ck.system.mapper.SysUserRoleMapper userRoleMapper,
                                             com.sw.ck.system.mapper.SysUserPostMapper userPostMapper, com.sw.ck.system.mapper.SysRoleMapper roleMapper,
                                             com.sw.ck.system.mapper.SysPostMapper postMapper, com.sw.ck.system.mapper.SysDeptMapper deptMapper,
                                             LoginUserLoader loader) {
            return new SysUserServiceImpl(passwordEncoder, userRoleMapper, userPostMapper, roleMapper, postMapper,
                    deptMapper, loader);
        }

        @Bean
        public SysRoleService sysRoleService(com.sw.ck.system.mapper.SysRoleDeptMapper roleDeptMapper,
                                             com.sw.ck.system.mapper.SysRoleMenuMapper roleMenuMapper,
                                             com.sw.ck.system.mapper.SysUserRoleMapper userRoleMapper,
                                             com.sw.ck.system.mapper.SysUserMapper userMapper,
                                             LoginUserLoader loader) {
            return new SysRoleServiceImpl(roleDeptMapper, roleMenuMapper, userRoleMapper, userMapper, loader);
        }

        @Bean
        public SysDeptService sysDeptService(SysUserService sysUserService) {
            return new SysDeptServiceImpl(sysUserService);
        }

        @Bean
        public LoginUserLoader loginUserLoader() {
            return mock(LoginUserLoader.class);
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }

        @Bean
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }

                @Override
                public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    LoginUser user = LoginUserHolder.get();
                    if (user == null || user.getDataScope() == null) {
                        return DataScopeType.ALL;
                    }
                    return DataScopeType.valueOf(user.getDataScope().name());
                }

                @Override
                public java.util.Set<Long> getCustomDeptIds() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.getCustomDeptIds() != null
                            ? user.getCustomDeptIds() : java.util.Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    return LoginUserHolder.get() != null && LoginUserHolder.get().isSuperAdmin();
                }
            };
        }
    }
}
