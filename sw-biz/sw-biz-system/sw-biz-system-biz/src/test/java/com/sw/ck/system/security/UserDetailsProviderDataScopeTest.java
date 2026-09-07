package com.sw.ck.system.security;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.mapper.SysMenuMapper;
import com.sw.ck.system.mapper.SysRoleDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.service.SysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserDetailsProviderImpl} 数据范围装配逻辑验证。
 * <p>
 * 覆盖：多角色取最宽（ALL &gt; DEPT_AND_CHILD &gt; CUSTOM &gt; DEPT &gt; SELF）、
 * CUSTOM 并集、单角色、无角色默认 ALL、停用角色排除、dataScope=null 兜底、超管判定。
 * </p>
 * <p>
 * 与真实登录流程一致：调用 loadByUsername 时 {@link LoginUserHolder} 为空，
 * 租户拦截器降级为 tenant_id=0（超级租户），种子数据均落在 tenant 0。
 * </p>
 */
@SpringBootTest(
        classes = UserDetailsProviderDataScopeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_udpds;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                // sys_menu 为全局表（无 tenant_id 列），需排除租户拦截器
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
class UserDetailsProviderDataScopeTest {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;

    @Autowired
    private SysMenuMapper sysMenuMapper;

    @Autowired
    private SysRoleDeptMapper sysRoleDeptMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserDetailsProvider provider;

    private final AtomicLong seq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        provider = new UserDetailsProviderImpl(sysUserService, sysUserRoleMapper, sysRoleMapper,
                sysRoleMenuMapper, sysMenuMapper, sysRoleDeptMapper);

        // 物理清空，避免跨用例干扰（逻辑删除行会占用主键）
        jdbcTemplate.update("DELETE FROM sys_role_dept");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    // ==================== 种子数据 ====================

    private Long seedUser(String username) {
        long id = seq.incrementAndGet();
        jdbcTemplate.update("""
                        INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id) VALUES (?, ?, 'x', 0, 1, 0)
                        """,
                id, username);
        return id;
    }

    private Long seedRole(String code, Integer dataScope, int status) {
        long id = seq.incrementAndGet();
        jdbcTemplate.update("""
                        INSERT INTO sys_role (id, name, code, status, data_scope, tenant_id) VALUES (?, ?, ?, ?, ?, 0)
                        """,
                id, code, code, status, dataScope);
        return id;
    }

    private void grantRole(Long userId, Long roleId) {
        jdbcTemplate.update("""
                        INSERT INTO sys_user_role (id, user_id, role_id, tenant_id) VALUES (?, ?, ?, 0)
                        """,
                seq.incrementAndGet(), userId, roleId);
    }

    private void grantRoleDepts(Long roleId, Long... deptIds) {
        for (Long deptId : deptIds) {
            jdbcTemplate.update("""
                            INSERT INTO sys_role_dept (id, role_id, dept_id, tenant_id) VALUES (?, ?, ?, 0)
                            """,
                    seq.incrementAndGet(), roleId, deptId);
        }
    }

    // ==================== 多角色取最宽 ====================

    @Test
    void multipleRoles_shouldPickWidest_selfDeptCustom() {
        Long userId = seedUser("u_mix1");
        Long selfRole = seedRole("r_self", 3, 1);   // SELF
        Long deptRole = seedRole("r_dept", 1, 1);   // DEPT
        Long customRole = seedRole("r_custom", 4, 1); // CUSTOM
        grantRole(userId, selfRole);
        grantRole(userId, deptRole);
        grantRole(userId, customRole);
        grantRoleDepts(customRole, 10L, 20L);

        LoginUser loginUser = provider.loadByUsername("u_mix1");

        assertThat(loginUser.getDataScope())
                .as("SELF/DEPT/CUSTOM 三档应取 CUSTOM")
                .isEqualTo(DataScope.CUSTOM);
        assertThat(loginUser.getCustomDeptIds())
                .as("CUSTOM 并集应包含 {10, 20}")
                .containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    void multipleRoles_shouldPickWidest_allBeatsDeptAndChild() {
        Long userId = seedUser("u_mix2");
        Long allRole = seedRole("r_all", 0, 1);         // ALL
        Long childRole = seedRole("r_child", 2, 1);     // DEPT_AND_CHILD
        grantRole(userId, allRole);
        grantRole(userId, childRole);

        LoginUser loginUser = provider.loadByUsername("u_mix2");

        assertThat(loginUser.getDataScope())
                .as("ALL 与 DEPT_AND_CHILD 并存应取 ALL")
                .isEqualTo(DataScope.ALL);
        assertThat(loginUser.getCustomDeptIds()).as("非 CUSTOM 档 customDeptIds 应为空").isEmpty();
    }

    @Test
    void multipleRoles_deptAndChildBeatsCustom() {
        Long userId = seedUser("u_mix3");
        Long childRole = seedRole("r_child", 2, 1); // DEPT_AND_CHILD
        Long customRole = seedRole("r_custom", 4, 1); // CUSTOM
        grantRole(userId, childRole);
        grantRole(userId, customRole);
        grantRoleDepts(customRole, 30L);

        LoginUser loginUser = provider.loadByUsername("u_mix3");

        assertThat(loginUser.getDataScope())
                .as("DEPT_AND_CHILD 应宽于 CUSTOM")
                .isEqualTo(DataScope.DEPT_AND_CHILD);
        assertThat(loginUser.getCustomDeptIds()).as("有效档非 CUSTOM 时 customDeptIds 应为空").isEmpty();
    }

    @Test
    void multipleCustomRoles_shouldUnionDeptIds() {
        Long userId = seedUser("u_mix4");
        Long customA = seedRole("r_custom_a", 4, 1);
        Long customB = seedRole("r_custom_b", 4, 1);
        grantRole(userId, customA);
        grantRole(userId, customB);
        grantRoleDepts(customA, 1L, 2L);
        grantRoleDepts(customB, 2L, 3L);

        LoginUser loginUser = provider.loadByUsername("u_mix4");

        assertThat(loginUser.getDataScope()).isEqualTo(DataScope.CUSTOM);
        assertThat(loginUser.getCustomDeptIds())
                .as("两个 CUSTOM 角色的部门应取并集")
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    // ==================== 单角色 ====================

    @Test
    void singleRole_custom_shouldCarryDeptIds() {
        Long userId = seedUser("u_single_custom");
        Long customRole = seedRole("r_custom", 4, 1);
        grantRole(userId, customRole);
        grantRoleDepts(customRole, 7L, 8L);

        LoginUser loginUser = provider.loadByUsername("u_single_custom");

        assertThat(loginUser.getDataScope()).isEqualTo(DataScope.CUSTOM);
        assertThat(loginUser.getCustomDeptIds()).containsExactlyInAnyOrder(7L, 8L);
    }

    @Test
    void singleRole_dept_shouldHaveEmptyCustomDeptIds() {
        Long userId = seedUser("u_single_dept");
        grantRole(userId, seedRole("r_dept", 1, 1));

        LoginUser loginUser = provider.loadByUsername("u_single_dept");

        assertThat(loginUser.getDataScope()).isEqualTo(DataScope.DEPT);
        assertThat(loginUser.getCustomDeptIds()).isEmpty();
    }

    @Test
    void singleRole_self_shouldMapOrdinal3() {
        Long userId = seedUser("u_single_self");
        grantRole(userId, seedRole("r_self", 3, 1));

        assertThat(provider.loadByUsername("u_single_self").getDataScope())
                .isEqualTo(DataScope.SELF);
    }

    // ==================== 无角色 / 边界 ====================

    @Test
    void noRole_shouldDefaultToAll() {
        seedUser("u_norole");

        LoginUser loginUser = provider.loadByUsername("u_norole");

        assertThat(loginUser.getDataScope())
                .as("无角色用户应默认 ALL（与历史硬编码行为一致）")
                .isEqualTo(DataScope.ALL);
        assertThat(loginUser.getCustomDeptIds()).isEmpty();
        assertThat(loginUser.isSuperAdmin()).isFalse();
    }

    @Test
    void disabledRole_shouldBeExcludedFromScope() {
        Long userId = seedUser("u_disabled");
        grantRole(userId, seedRole("r_all_disabled", 0, 0)); // 停用角色：ALL 但不参与
        grantRole(userId, seedRole("r_dept", 1, 1));         // 启用角色：DEPT

        LoginUser loginUser = provider.loadByUsername("u_disabled");

        assertThat(loginUser.getDataScope())
                .as("停用角色的 ALL 不应参与取最宽，应为 DEPT")
                .isEqualTo(DataScope.DEPT);
        assertThat(loginUser.getRoles()).doesNotContain("r_all_disabled");
    }

    @Test
    void nullDataScope_shouldFallbackToAll() {
        Long userId = seedUser("u_nullscope");
        grantRole(userId, seedRole("r_nullscope", null, 1));

        assertThat(provider.loadByUsername("u_nullscope").getDataScope())
                .as("dataScope=null（旧库/脏数据）应按 DB 默认 0 处理为 ALL")
                .isEqualTo(DataScope.ALL);
    }

    @Test
    void superAdminRole_shouldStillShortCircuit() {
        Long userId = seedUser("u_super");
        grantRole(userId, seedRole("superadmin", null, 1));

        LoginUser loginUser = provider.loadByUsername("u_super");

        assertThat(loginUser.isSuperAdmin()).as("超管判定不应受 dataScope 装配影响").isTrue();
        assertThat(loginUser.getPermissions()).isEmpty();
    }

    @Test
    void unknownUser_shouldReturnNull() {
        assertThat(provider.loadByUsername("u_not_exist")).isNull();
    }

    @Test
    void inactiveUser_shouldNotEnterAuthenticationContext() {
        Long userId = seedUser("u_inactive");
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = ?", userId);

        assertThat(provider.loadByUserId(userId)).isNull();
        assertThat(provider.loadByUsername("u_inactive")).isNull();
    }

    // ==================== 测试上下文配置 ====================

    @Configuration
    @EnableAutoConfiguration
    @ComponentScan("com.sw.ck.system.service.impl")
    static class TestConfig {

        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.system.mapper");
            return configurer;
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
                    return DataScopeType.ALL;
                }

                @Override
                public Set<Long> getCustomDeptIds() {
                    return Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    return true;
                }
            };
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }
    }
}
