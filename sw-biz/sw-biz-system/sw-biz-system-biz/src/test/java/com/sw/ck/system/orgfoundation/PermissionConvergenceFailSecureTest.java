package com.sw.ck.system.orgfoundation;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * I1 权限收敛 fail-secure 语义（规划验收 01 · G6）。
 * <p>
 * 登录缓存驱逐基础设施失败（如 Redis 不可用）时，权限/身份变更必须<b>整体失败并回滚</b>，
 * 不允许出现“变更成功但旧会话仍持旧权限”的窗口；仅装配缺失
 * （{@link org.springframework.beans.factory.NoSuchBeanDefinitionException}，非生产手工上下文）
 * 容忍跳过驱逐。
 * </p>
 */
@SpringBootTest(
        classes = PermissionConvergenceFailSecureTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_i1_failsecure;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("I6 权限收敛 fail-secure：驱逐失败必须回滚授权变更")
class PermissionConvergenceFailSecureTest {

    enum LoaderMode { INFRA_FAILURE, BEAN_ABSENT, OK }

    /** 测试内切换的 loader 行为模式。 */
    static volatile LoaderMode loaderMode = LoaderMode.INFRA_FAILURE;

    @Autowired
    private SysUserService userService;

    @Autowired
    private SysRoleService roleService;

    @Autowired
    private SysDeptService deptService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        loaderMode = LoaderMode.INFRA_FAILURE;
        for (String table : List.of("sys_user_role", "sys_role_menu", "sys_role_dept",
                "sys_user_post", "sys_role", "sys_user", "sys_dept")) {
            jdbc.update("DELETE FROM " + table);
        }
        login(1L);
        jdbc.update("INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id, deleted) "
                + "VALUES (701, 0, '总部', 'D701', 10, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id, deleted) "
                + "VALUES (820, 'u820', 'x', 0, 701, 1, 0)");
        jdbc.update("INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id, deleted) "
                + "VALUES (821, 'u821', 'x', 0, 701, 1, 0)");
        jdbc.update("INSERT INTO sys_role (id, name, code, sort, status, data_scope, built_in, tenant_id, deleted) "
                + "VALUES (831, '普通角色', 'R_FS', 10, 1, 0, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_role_menu (id, role_id, menu_id, tenant_id, deleted) "
                + "VALUES (9401, 831, 11, 1, 0)");
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, deleted) "
                + "VALUES (9501, 820, 831, 1, 0)");
        jdbc.update("INSERT INTO sys_user_role (id, user_id, role_id, tenant_id, deleted) "
                + "VALUES (9502, 821, 831, 1, 0)");
    }

    @AfterEach
    void tearDown() {
        loaderMode = LoaderMode.OK;
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("停用用户时驱逐失败：变更抛出且用户状态回滚为启用")
    void disableUser_kickOutFails_shouldRollback() {
        SysUser user = userService.getById(820L);
        user.setStatus(1);
        assertThatThrownBy(() -> userService.update(user, (String) null))
                .isInstanceOf(RuntimeException.class);

        assertThat(userService.getById(820L).getStatus()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = 820 AND status = 0 AND deleted = 0",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("撤权时成员驱逐失败：角色关联回滚保留")
    void revokeRoles_kickOutFails_shouldRollback() {
        assertThatThrownBy(() -> userService.updateRoleIds(820L, List.of()))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = 820 AND role_id = 831 AND deleted = 0",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("角色菜单变更时成员驱逐失败：授权整体回滚，menuIds 不变")
    void updateRoleMenus_kickOutFails_shouldRollback() {
        // 820 在成员列表首位被踢出即失败；断言 821 也未被驱逐后提交，授权表保持原样
        assertThatThrownBy(() -> roleService.updateMenuIds(831L, List.of(12L)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 831 AND deleted = 0", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 831 AND menu_id = 12 AND deleted = 0",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("删除角色时成员驱逐失败：角色及其关联全部回滚")
    void deleteRole_kickOutFails_shouldRollback() {
        assertThatThrownBy(() -> roleService.delete(831L))
                .isInstanceOf(RuntimeException.class);

        assertThat(roleService.getById(831L)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE role_id = 831 AND deleted = 0", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("装配缺失（NoSuchBeanDefinition）容忍跳过驱逐：变更正常提交")
    void kickOutBeanAbsent_shouldSucceed() {
        loaderMode = LoaderMode.BEAN_ABSENT;

        SysUser user = userService.getById(820L);
        user.setStatus(1);
        userService.update(user, (String) null);

        assertThat(userService.getById(820L).getStatus()).isEqualTo(1);
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
            LoginUserLoader loader = mock(LoginUserLoader.class);
            doAnswer(inv -> {
                switch (loaderMode) {
                    case INFRA_FAILURE -> throw new IllegalStateException("模拟缓存基础设施故障（Redis 不可达）");
                    case BEAN_ABSENT -> throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(
                            "LoginUserLoader");
                    default -> {
                        return null;
                    }
                }
            }).when(loader).kickOut(anyLong());
            return loader;
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
