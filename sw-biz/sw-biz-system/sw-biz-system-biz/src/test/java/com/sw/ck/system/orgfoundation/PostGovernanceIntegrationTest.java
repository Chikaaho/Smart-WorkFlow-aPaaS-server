package com.sw.ck.system.orgfoundation;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.service.SysPostService;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.service.UserPostAssociation;
import com.sw.ck.system.service.impl.SysPostServiceImpl;
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

/**
 * I1 岗位治理集成测试（方向 §3.1：岗位维度补齐，岗位不是角色）。
 * <p>
 * 覆盖：创建默认启用（否则无法被用户绑定）、编码本租户唯一、
 * 删除岗位解除用户任职（逻辑删保留轨迹）、任职部门维度写入。
 * </p>
 */
@SpringBootTest(
        classes = PostGovernanceIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_i1_postgov;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("I1 岗位治理集成测试")
class PostGovernanceIntegrationTest {

    @Autowired
    private SysPostService postService;

    @Autowired
    private SysUserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sys_post");
        jdbc.update("DELETE FROM sys_user_post");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("DELETE FROM sys_dept");
        login(1L);
        // 用户 810（主部门 601）；部门 601/602
        jdbc.update("INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id, deleted) "
                + "VALUES (810, 'u810', 'x', 0, 601, 1, 0)");
        jdbc.update("INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id, deleted) "
                + "VALUES (601, 0, '总部', 'D601', 10, 0, 1, 0)");
        jdbc.update("INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id, deleted) "
                + "VALUES (602, 601, '研发部', 'D602', 20, 0, 1, 0)");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    @DisplayName("创建岗位缺省 status=1（启用），可被用户绑定并带任职部门")
    void createDefaultEnabled_andBindableWithDept() {
        SysPost post = new SysPost();
        post.setCode("PM");
        post.setName("项目经理");
        Long postId = postService.create(post);
        assertThat(postService.getById(postId).getStatus()).isEqualTo(1);

        // 缺省任职部门回落用户主部门 601
        userService.updatePosts(810L, List.of(new UserPostAssociation(postId, null)));
        List<UserPostAssociation> posts = userService.listPosts(810L);
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).getPostId()).isEqualTo(postId);
        assertThat(posts.get(0).getDeptId()).isEqualTo(601L);

        // 显式指定任职部门
        userService.updatePosts(810L, List.of(new UserPostAssociation(postId, 602L)));
        assertThat(userService.listPosts(810L).get(0).getDeptId()).isEqualTo(602L);
    }

    @Test
    @DisplayName("编码本租户唯一：创建重复拒绝，更新保留自身放行、撞他人拒绝")
    void codeUniqueness_shouldEnforce() {
        SysPost first = new SysPost();
        first.setCode("DEV");
        first.setName("开发工程师");
        postService.create(first);

        SysPost dup = new SysPost();
        dup.setCode("DEV");
        dup.setName("重复编码");
        assertThatThrownBy(() -> postService.create(dup)).isInstanceOf(BaseException.class);

        SysPost rename = new SysPost();
        rename.setId(first.getId());
        rename.setCode("DEV");
        rename.setName("开发工程师（改）");
        postService.update(rename);

        SysPost second = new SysPost();
        second.setCode("QA");
        second.setName("测试工程师");
        Long secondId = postService.create(second);

        SysPost clash = new SysPost();
        clash.setId(secondId);
        clash.setCode("DEV");
        clash.setName("测试改撞");
        assertThatThrownBy(() -> postService.update(clash)).isInstanceOf(BaseException.class);
    }

    @Test
    @DisplayName("删除岗位解除用户任职（逻辑删保留轨迹）")
    void deletePost_shouldReleaseUserAssociations() {
        SysPost post = new SysPost();
        post.setCode("CLERK");
        post.setName("科员");
        Long postId = postService.create(post);
        userService.updatePosts(810L, List.of(new UserPostAssociation(postId, 601L)));
        assertThat(userService.listPosts(810L)).hasSize(1);

        postService.delete(postId);
        assertThat(userService.listPosts(810L)).isEmpty();
        // 轨迹保留：软删行仍存在
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_post WHERE post_id = ? AND deleted = 1",
                Integer.class, postId);
        assertThat(rows).isEqualTo(1);
        assertThat(postService.getById(postId)).isNull();
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
        public SysPostService sysPostService(com.sw.ck.system.mapper.SysUserPostMapper userPostMapper) {
            return new SysPostServiceImpl(userPostMapper);
        }

        @Bean
        public SysUserService sysUserService(PasswordEncoder passwordEncoder, com.sw.ck.system.mapper.SysUserRoleMapper userRoleMapper,
                                             com.sw.ck.system.mapper.SysUserPostMapper userPostMapper, com.sw.ck.system.mapper.SysRoleMapper roleMapper,
                                             com.sw.ck.system.mapper.SysPostMapper postMapper) {
            return new SysUserServiceImpl(passwordEncoder, userRoleMapper, userPostMapper, roleMapper, postMapper);
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
