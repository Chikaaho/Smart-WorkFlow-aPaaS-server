package com.sw.ck.system.usergroup;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.mapper.SysUserGroupMemberMapper;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.service.SysUserGroupService;
import com.sw.ck.system.service.impl.SysUserGroupServiceImpl;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户组分页可见范围真过滤测试（0.2.0 空组修复受影响断言）。
 * <p>
 * 种子：租户 0 部门 11/12；用户 2001（创建者，部门 11）、2002（部门 11）、2003（部门 12）；
 * 组 A=租户 0 空成员、create_by=2001；组 B=租户 0、成员 2002；组 C=租户 0、成员 2003；
 * 组 D=租户 100 空成员。验证四类：自建空组可见、非成员/范围外组不泄露、跨租户隔离、超管全量。
 * </p>
 */
@SpringBootTest(
        classes = SysUserGroupDataScopeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_usergroupds;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("用户组分页可见范围四类过滤测试")
class SysUserGroupDataScopeTest {

    @Autowired
    private SysUserGroupService sysUserGroupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sys_user_group_member");
        jdbcTemplate.update("DELETE FROM sys_user_group");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_dept");

        seedDept(11L, 0L);
        seedDept(12L, 0L);
        seedUser(2001L, "creator", 11L, 2001L);
        seedUser(2002L, "member11", 11L, 2001L);
        seedUser(2003L, "member12", 12L, 2003L);

        seedGroup(5001L, 0L, "g-empty", "自建空组", 2001L);
        seedGroup(5002L, 0L, "g-member11", "部门11成员组", 9999L);
        seedGroup(5003L, 0L, "g-member12", "部门12成员组", 9999L);
        seedGroup(5004L, 100L, "g-tenant100", "跨租户组", 2001L);

        seedMember(6001L, 0L, 5002L, 2002L);
        seedMember(6002L, 0L, 5003L, 2003L);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void seedDept(Long id, Long parentId) {
        jdbcTemplate.update("""
                        INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id)
                        VALUES (?, ?, ?, ?, 0, 0, 0)
                        """,
                id, parentId, "dept-" + id, "D" + id);
    }

    private void seedUser(Long id, String username, Long deptId, Long createBy) {
        jdbcTemplate.update("""
                        INSERT INTO sys_user (id, username, password, status, dept_id, create_by, tenant_id)
                        VALUES (?, ?, 'x', 0, ?, ?, 0)
                        """,
                id, username, deptId, createBy);
    }

    private void seedGroup(Long id, Long tenantId, String code, String name, Long createBy) {
        jdbcTemplate.update("""
                        INSERT INTO sys_user_group (id, tenant_id, group_code, group_name, status, create_by)
                        VALUES (?, ?, ?, ?, 0, ?)
                        """,
                id, tenantId, code, name, createBy);
    }

    private void seedMember(Long id, Long tenantId, Long groupId, Long userId) {
        jdbcTemplate.update("""
                        INSERT INTO sys_user_group_member (id, tenant_id, group_id, user_id)
                        VALUES (?, ?, ?, ?)
                        """,
                id, tenantId, groupId, userId);
    }

    private void loginAs(DataScopeType scopeType, Long userId, Long deptId, boolean superAdmin) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(0L);
        user.setDeptId(deptId);
        user.setDataScope(DataScope.valueOf(scopeType.name()));
        user.setCustomDeptIds(Set.of());
        user.setSuperAdmin(superAdmin);
        LoginUserHolder.set(user);
    }

    private List<String> pageCodes() {
        PageParam param = new PageParam();
        param.setPageNum(1);
        param.setPageSize(50);
        PageResult<SysUserGroup> result = sysUserGroupService.page(param, new SysUserGroup());
        return result.getRecords().stream().map(SysUserGroup::getGroupCode).toList();
    }

    @Test
    @DisplayName("自建空组：创建者即使无成员也可见")
    void selfCreatedEmptyGroup_shouldBeVisible() {
        loginAs(DataScopeType.SELF, 2001L, 11L, false);

        List<String> codes = pageCodes();

        assertThat(codes).contains("g-empty");
    }

    @Test
    @DisplayName("非成员范围外组：成员不在当前数据范围时不泄露")
    void groupOutsideScope_shouldNotLeak() {
        loginAs(DataScopeType.DEPT, 2001L, 11L, false);

        List<String> codes = pageCodes();

        assertThat(codes).contains("g-empty", "g-member11");
        assertThat(codes).doesNotContain("g-member12");
    }

    @Test
    @DisplayName("跨租户隔离：租户 0 登录看不到租户 100 的组")
    void crossTenantGroup_shouldNotLeak() {
        loginAs(DataScopeType.DEPT, 2001L, 11L, false);

        assertThat(pageCodes()).doesNotContain("g-tenant100");
    }

    @Test
    @DisplayName("超管：本租户全部组可见（含空组），仍受租户隔离")
    void superAdmin_shouldSeeAllTenantGroups() {
        loginAs(DataScopeType.SELF, 2001L, 11L, true);

        List<String> codes = pageCodes();

        assertThat(codes).containsExactlyInAnyOrder("g-empty", "g-member11", "g-member12");
        assertThat(codes).doesNotContain("g-tenant100");
    }

    // ==================== 测试上下文 ====================

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
        public SysUserGroupService sysUserGroupService(SysUserGroupMemberMapper memberMapper,
                                                       SysUserMapper userMapper,
                                                       LoginContextProvider loginContextProvider) {
            return new SysUserGroupServiceImpl(memberMapper, userMapper, loginContextProvider);
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
                public Set<Long> getCustomDeptIds() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.getCustomDeptIds() != null
                            ? user.getCustomDeptIds() : Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }
    }
}
