package com.sw.ck.system.orgfoundation;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.service.impl.UserFacadeImpl;
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
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I1 组织权威解析 Facade 集成测试（流程选人只消费服务端认可的组织数据）。
 * <p>
 * 部门负责人：仅正常状态部门 × 启用用户 × 同租户；
 * 岗位：仅启用岗位 × 有效任职行 × 启用用户 × 同租户；
 * 部门+岗位组合：任职部门精确匹配且部门须正常状态。
 * </p>
 * <p>
 * 固定种子（租户 1）：部门 901（正常，负责人 903）、902（停用，负责人 904）、
 * 906（正常，负责人 907 停用）；用户 903/904 正常、907 停用；
 * 岗位 910 MANAGER（启用）、911 OLDPOST（停用）；
 * 任职行：903×910@901、904×911@902、907×910@901（用户停用仍占行）；
 * 租户 2：部门 905（正常，负责人 908）、用户 908、岗位 912 MANAGER、任职 908×912@905。
 * </p>
 */
@SpringBootTest(
        classes = OrgAuthorityFacadeIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_i1_facade;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("I1 组织权威解析 Facade 集成测试")
class OrgAuthorityFacadeIntegrationTest {

    @Autowired
    private UserQueryFacade userQueryFacade;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sys_user_post");
        jdbc.update("DELETE FROM sys_post");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM sys_dept");
        // 租户 1
        seedDept(901L, 1L, 0, 903L);
        seedDept(902L, 1L, 1, 904L);
        seedDept(906L, 1L, 0, 907L);
        seedUser(903L, 1L, 0);
        seedUser(904L, 1L, 0);
        seedUser(907L, 1L, 1);
        seedPost(910L, "MANAGER", 1, 1L);
        seedPost(911L, "OLDPOST", 0, 1L);
        seedUserPost(903L, 910L, 901L, 1L);
        seedUserPost(904L, 911L, 902L, 1L);
        seedUserPost(907L, 910L, 901L, 1L);
        // 租户 2
        seedDept(905L, 2L, 0, 908L);
        seedUser(908L, 2L, 0);
        seedPost(912L, "MANAGER", 1, 2L);
        seedUserPost(908L, 912L, 905L, 2L);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("DEPT_LEADER：只解析正常状态部门的启用负责人；停用部门/停用负责人/跨租户排除")
    void deptLeaderResolution_shouldFilterByDeptAndUserState() {
        assertThat(userQueryFacade.findActiveUserIdsByDeptLeaders(List.of(901L), 1L))
                .containsExactly(903L);
        // 停用部门不产出负责人
        assertThat(userQueryFacade.findActiveUserIdsByDeptLeaders(List.of(902L), 1L)).isEmpty();
        // 负责人停用：部门正常也不产出
        assertThat(userQueryFacade.findActiveUserIdsByDeptLeaders(List.of(906L), 1L)).isEmpty();
        // 跨租户部门不可见
        assertThat(userQueryFacade.findActiveUserIdsByDeptLeaders(List.of(905L), 1L)).isEmpty();
        // 组合查询去重（同负责人合并）
        assertThat(userQueryFacade.findActiveUserIdsByDeptLeaders(List.of(901L, 902L), 1L))
                .containsExactly(903L);
    }

    @Test
    @DisplayName("POST：只解析启用岗位的有效任职用户；停用岗位/停用用户/跨租户排除")
    void postResolution_shouldFilterByPostAndUserState() {
        assertThat(userQueryFacade.findActiveUserIdsByPostCodes(List.of("MANAGER"), 1L))
                .containsExactly(903L);
        // 907 任职行存在但用户停用；908 的 MANAGER 属租户 2
        assertThat(userQueryFacade.findActiveUserIdsByPostCodes(List.of("OLDPOST"), 1L)).isEmpty();
    }

    @Test
    @DisplayName("DEPT_POST：任职部门精确匹配，部门/岗位/用户任一失效即空")
    void deptPostResolution_shouldRequireExactDeptMatch() {
        assertThat(userQueryFacade.findActiveUserIdsByDeptAndPost(901L, "MANAGER", 1L))
                .containsExactly(903L);
        // 停用部门上的组合解析为空
        assertThat(userQueryFacade.findActiveUserIdsByDeptAndPost(902L, "OLDPOST", 1L)).isEmpty();
        // 跨租户
        assertThat(userQueryFacade.findActiveUserIdsByDeptAndPost(905L, "MANAGER", 1L)).isEmpty();
        // 租户 2 自身解析正常
        assertThat(userQueryFacade.findActiveUserIdsByDeptAndPost(905L, "MANAGER", 2L))
                .containsExactly(908L);
    }

    private void seedDept(Long id, Long tenantId, int status, Long leaderId) {
        jdbc.update("INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id, deleted, leader_id) "
                        + "VALUES (?, 0, ?, ?, 10, ?, ?, 0, ?)",
                id, "部门" + id, "D" + id, status, tenantId, leaderId);
    }

    private void seedUser(Long id, Long tenantId, int status) {
        jdbc.update("INSERT INTO sys_user (id, username, password, status, tenant_id, deleted) "
                        + "VALUES (?, ?, 'x', ?, ?, 0)",
                id, "u" + id, status, tenantId);
    }

    private void seedPost(Long id, String code, int status, Long tenantId) {
        jdbc.update("INSERT INTO sys_post (id, code, name, sort, status, tenant_id, deleted) "
                        + "VALUES (?, ?, ?, 10, ?, ?, 0)",
                id, code, "岗位" + id, status, tenantId);
    }

    private void seedUserPost(Long userId, Long postId, Long deptId, Long tenantId) {
        jdbc.update("INSERT INTO sys_user_post (id, user_id, post_id, dept_id, tenant_id, deleted) "
                        + "VALUES (?, ?, ?, ?, ?, 0)",
                userId * 100 + postId, userId, postId, deptId, tenantId);
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
        public UserQueryFacade userQueryFacade(com.sw.ck.system.mapper.SysUserMapper sysUserMapper) {
            return new UserFacadeImpl(sysUserMapper);
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
