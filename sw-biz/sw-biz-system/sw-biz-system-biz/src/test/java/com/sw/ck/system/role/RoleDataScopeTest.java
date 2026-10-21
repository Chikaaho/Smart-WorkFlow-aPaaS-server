package com.sw.ck.system.role;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.service.SysRoleService;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色数据范围（dataScope + deptIds）持久化往返验证。
 * <p>
 * 覆盖：deptIds 写入/回读/更新覆盖、dataScope 存取、分页回填、去重、
 * null deptIds 视为清空。
 * </p>
 */
@SpringBootTest(
        classes = RoleDataScopeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_roleds;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql"
        }
)
@ActiveProfiles("test")
class RoleDataScopeTest {

    @Autowired
    private SysRoleService sysRoleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        LoginUserHolder.set(user);

        jdbcTemplate.update("DELETE FROM sys_role_dept");
        jdbcTemplate.update("DELETE FROM sys_role");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private SysRole newRole(String code, Integer dataScope, List<Long> deptIds) {
        SysRole role = new SysRole();
        role.setName("角色-" + code);
        role.setCode(code);
        role.setSort(10);
        role.setStatus(1);
        role.setDataScope(dataScope);
        role.setDeptIds(deptIds);
        return role;
    }

    @Test
    void create_withDeptIds_shouldPersistAndReadBack() {
        Long id = sysRoleService.create(newRole("r_custom_1", 4, List.of(1L, 2L, 3L)));

        SysRole loaded = sysRoleService.getById(id);

        assertThat(loaded).as("详情应返回角色").isNotNull();
        assertThat(loaded.getDataScope()).as("dataScope 应原样回读").isEqualTo(4);
        assertThat(loaded.getDeptIds())
                .as("deptIds 应回读为写入集合")
                .containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void create_withoutDeptIds_shouldHaveEmptyDeptIds() {
        Long id = sysRoleService.create(newRole("r_no_dept", 1, null));

        SysRole loaded = sysRoleService.getById(id);

        assertThat(loaded.getDeptIds()).as("未传 deptIds 应回读为空").isNullOrEmpty();
    }

    @Test
    void create_withDuplicateDeptIds_shouldDedupe() {
        Long id = sysRoleService.create(newRole("r_dup", 4, List.of(1L, 1L, 2L)));

        assertThat(sysRoleService.getById(id).getDeptIds())
                .as("重复 deptIds 应去重")
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void update_shouldOverwriteDeptIds() {
        Long id = sysRoleService.create(newRole("r_ow", 4, List.of(1L, 2L)));

        SysRole update = newRole("r_ow", 4, List.of(3L));
        update.setId(id);
        sysRoleService.update(update);

        SysRole loaded = sysRoleService.getById(id);
        assertThat(loaded.getDeptIds())
                .as("更新后 deptIds 应被整体覆盖")
                .containsExactlyInAnyOrder(3L);

        // MP delete 为逻辑删除（deleted=1），统计有效（未删）关联行
        Long rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_dept WHERE role_id = ? AND deleted = 0", Long.class, id);
        assertThat(rowCount).as("DB 中有效关联应只剩 1 条").isEqualTo(1);
    }

    @Test
    void update_withNullDeptIds_shouldClearAssociations() {
        Long id = sysRoleService.create(newRole("r_clr", 4, List.of(1L, 2L)));

        SysRole update = newRole("r_clr", 1, null);
        update.setId(id);
        sysRoleService.update(update);

        assertThat(sysRoleService.getById(id).getDeptIds())
                .as("更新为 null deptIds 应清空关联")
                .isNullOrEmpty();
    }

    @Test
    void update_shouldOverwriteDataScope() {
        Long id = sysRoleService.create(newRole("r_ds", 1, null));

        SysRole update = newRole("r_ds", 4, List.of(9L));
        update.setId(id);
        sysRoleService.update(update);

        assertThat(sysRoleService.getById(id).getDataScope())
                .as("dataScope 应更新为 4 (CUSTOM)")
                .isEqualTo(4);
    }

    @Test
    void page_shouldFillDeptIdsForRecords() {
        sysRoleService.create(newRole("r_p1", 4, List.of(1L, 2L)));
        sysRoleService.create(newRole("r_p2", 1, null));

        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(10);
        PageResult<SysRole> result = sysRoleService.page(pageParam, null);

        assertThat(result.getTotal()).as("应返回 2 条角色").isEqualTo(2);
        for (SysRole role : result.getRecords()) {
            if (role.getCode().equals("r_p1")) {
                assertThat(role.getDeptIds())
                        .as("CUSTOM 角色的 deptIds 应回填")
                        .containsExactlyInAnyOrder(1L, 2L);
            } else {
                assertThat(role.getDeptIds()).as("非 CUSTOM 角色的 deptIds 应为空").isNullOrEmpty();
            }
        }
    }

    @Test
    void page_withTenantId_shouldOnlyReturnRolesOfThatTenant() {
        sysRoleService.create(newRole("r_tenant_0", 1, null));
        SysRole otherTenant = newRole("r_tenant_9001", 1, null);
        otherTenant.setTenantId(9001L);
        sysRoleService.create(otherTenant);

        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(10);
        SysRole query = new SysRole();
        query.setTenantId(9001L);
        PageResult<SysRole> result = sysRoleService.page(pageParam, query);

        assertThat(result.getTotal()).as("只应返回目标租户的角色").isEqualTo(1);
        assertThat(result.getRecords()).extracting(SysRole::getCode)
                .containsExactly("r_tenant_9001");
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
