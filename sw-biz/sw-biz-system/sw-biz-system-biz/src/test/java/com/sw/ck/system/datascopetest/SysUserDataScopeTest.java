package com.sw.ck.system.datascopetest;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.datascope.DeptScopeProviderImpl;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.mapper.SysUserPostMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysPostMapper;
import com.sw.ck.system.service.SysUserService;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sys_user 分页数据范围五档真过滤测试（验收标准 2）。
 * <p>
 * 走真实拦截器链（租户 → 数据范围 → 乐观锁 → 分页）与 {@code SysUserMapper#selectUserPage}
 * 上的 {@code @DataScope} 标注：ALL 全量 / DEPT 仅本部门 / DEPT_AND_CHILD 含子部门 /
 * SELF 仅本人 / CUSTOM 仅关联部门 + 空关联恒假，另覆盖超管短路与 DEPT 取不到部门恒假。
 * </p>
 * <p>
 * 部门树：总部(1) → 研发部(11) → 后端组(111)、前端组(112)；总部 → 市场部(12)。
 * 用户：u11a/u11b ∈ 11，u111 ∈ 111，u112 ∈ 112，u12 ∈ 12，u1 ∈ 1。
 * </p>
 */
@SpringBootTest(
        classes = SysUserDataScopeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_sysuserds;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                // sys_menu 为全局表（无 tenant_id 列），排除租户拦截器
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("sys_user 分页数据范围五档过滤测试")
class SysUserDataScopeTest {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong seq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        // 物理清空，避免跨用例干扰（逻辑删除行会占用主键）
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_dept");
        seedDept(1L, 0L);
        seedDept(11L, 1L);
        seedDept(111L, 11L);
        seedDept(112L, 11L);
        seedDept(12L, 1L);
        seedUser("u11a", 11L, 2001L);
        seedUser("u11b", 11L, 2002L);
        seedUser("u111", 111L, 2001L);
        seedUser("u112", 112L, 2003L);
        seedUser("u12", 12L, 2004L);
        seedUser("u1", 1L, 2005L);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 种子数据 ====================

    private void seedDept(Long id, Long parentId) {
        jdbcTemplate.update("""
                        INSERT INTO sys_dept (id, parent_id, name, code, sort, status, tenant_id)
                        VALUES (?, ?, ?, ?, 0, 0, 0)
                        """,
                id, parentId, "dept-" + id, "D" + id);
    }

    private void seedUser(String username, Long deptId, Long createBy) {
        long id = seq.incrementAndGet();
        jdbcTemplate.update("""
                        INSERT INTO sys_user (id, username, password, status, dept_id, create_by, tenant_id)
                        VALUES (?, ?, 'x', 0, ?, ?, 0)
                        """,
                id, username, deptId, createBy);
    }

    // ==================== 登录上下文 ====================

    private void loginAs(DataScopeType scopeType, Long userId, Long deptId, Set<Long> customDeptIds, boolean superAdmin) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(0L);
        user.setDeptId(deptId);
        user.setDataScope(DataScope.valueOf(scopeType.name()));
        user.setCustomDeptIds(customDeptIds);
        user.setSuperAdmin(superAdmin);
        LoginUserHolder.set(user);
    }

    private PageResult<SysUser> pageAll() {
        PageParam param = new PageParam();
        param.setPageNum(1);
        param.setPageSize(50);
        return sysUserService.page(param);
    }

    private List<String> usernames(PageResult<SysUser> result) {
        return result.getRecords().stream().map(SysUser::getUsername).toList();
    }

    // ==================== ALL / 超管 ====================

    @Test
    @DisplayName("ALL：返回全量 6 用户")
    void all_shouldReturnAllUsers() {
        loginAs(DataScopeType.ALL, 2001L, 11L, Set.of(), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isEqualTo(6L);
        assertThat(usernames(result)).containsExactlyInAnyOrder("u11a", "u11b", "u111", "u112", "u12", "u1");
    }

    @Test
    @DisplayName("超管：短路全部范围限制，返回全量")
    void superAdmin_shouldReturnAllUsers() {
        loginAs(DataScopeType.SELF, 2001L, 11L, Set.of(), true);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isEqualTo(6L);
    }

    // ==================== DEPT ====================

    @Test
    @DisplayName("DEPT：仅本部门（11）用户 u11a/u11b")
    void dept_shouldReturnOnlySameDeptUsers() {
        loginAs(DataScopeType.DEPT, 2001L, 11L, Set.of(), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(usernames(result)).containsExactlyInAnyOrder("u11a", "u11b");
    }

    @Test
    @DisplayName("DEPT：取不到部门 → 恒假返回 0 行")
    void dept_withoutDeptId_shouldReturnEmpty() {
        loginAs(DataScopeType.DEPT, 2001L, null, Set.of(), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
    }

    // ==================== DEPT_AND_CHILD ====================

    @Test
    @DisplayName("DEPT_AND_CHILD：本部门及子部门（11+111+112）共 4 用户")
    void deptAndChild_shouldIncludeChildDeptUsers() {
        loginAs(DataScopeType.DEPT_AND_CHILD, 2001L, 11L, Set.of(), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isEqualTo(4L);
        assertThat(usernames(result)).containsExactlyInAnyOrder("u11a", "u11b", "u111", "u112");
    }

    // ==================== SELF ====================

    @Test
    @DisplayName("SELF：仅 create_by=本人（2001）的用户 u11a/u111")
    void self_shouldReturnOnlySelfCreatedUsers() {
        loginAs(DataScopeType.SELF, 2001L, 11L, Set.of(), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(usernames(result)).containsExactlyInAnyOrder("u11a", "u111");
    }

    // ==================== CUSTOM ====================

    @Test
    @DisplayName("CUSTOM：仅关联部门（111+12）的用户 u111/u12")
    void custom_shouldReturnOnlyCustomDeptUsers() {
        loginAs(DataScopeType.CUSTOM, 2001L, 11L, Set.of(111L, 12L), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(usernames(result)).containsExactlyInAnyOrder("u111", "u12");
    }

    @Test
    @DisplayName("CUSTOM：未配置任何部门 → 恒假返回 0 行")
    void custom_withEmptyDeptIds_shouldReturnEmpty() {
        loginAs(DataScopeType.CUSTOM, 2001L, 11L, Set.of(), false);

        PageResult<SysUser> result = pageAll();

        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
    }

    // ==================== 测试上下文配置 ====================
    // 说明：本测试类放在独立包（datascopetest），且不做 @ComponentScan——DeptScopeProviderTest
    // 的 @ComponentScan("com.sw.ck.system.datascope") 会递归扫描其子包内的嵌套 TestConfig，
    // 导致 mapperScannerConfigurer 等 Bean 定义重复注册（BeanDefinitionOverrideException）。
    // 所需 Bean 全部显式声明。

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
        public SysUserService sysUserService(PasswordEncoder passwordEncoder, SysUserRoleMapper userRoleMapper,
                                             SysUserPostMapper userPostMapper, SysRoleMapper roleMapper,
                                             SysPostMapper postMapper) {
            return new SysUserServiceImpl();
        }

        @Bean
        public DeptScopeProvider deptScopeProvider(@Lazy SysDeptMapper sysDeptMapper) {
            // @Lazy 对齐生产装配：DeptScopeProviderImpl 处于拦截器链依赖图中，
            // 急切初始化 SysDeptMapper 会触发 sqlSessionFactory 循环依赖
            return new DeptScopeProviderImpl();
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

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }
    }
}
