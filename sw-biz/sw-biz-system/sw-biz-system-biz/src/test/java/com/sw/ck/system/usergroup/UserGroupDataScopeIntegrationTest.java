package com.sw.ck.system.usergroup;

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
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.mapper.SysDeptMapper;
import com.sw.ck.system.mapper.SysUserGroupMapper;
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
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户组数据范围 + 零隐式授权集成测试（D112 验收标准 3/4/5）。
 * <p>
 * 走真实拦截器链（租户 → 数据范围 → 乐观锁 → 分页）与 {@code SysUserGroupMapper} 上的
 * {@code @DataScope} 标注：
 * <ul>
 *   <li>用户组列表/候选按数据范围过滤（组内成员部门命中）；CUSTOM 空关联恒假；超管短路；</li>
 *   <li>成员绑定校验：跨租户/不可见/停用用户一律拒绝且不产生部分写入；</li>
 *   <li>零隐式授权：加入/移出用户组不改变用户角色、菜单、按钮、数据范围与 superadmin 判定
 *       （登录上下文与 sys_user_role/sys_role_menu/sys_role_dept 完全不被触碰）。</li>
 * </ul>
 */
@SpringBootTest(
        classes = UserGroupDataScopeIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:testdb_ugds3;MODE=PostgreSQL",
                "spring.sql.init.schema-locations=classpath:db/schema-datascope-h2.sql",
                "spring.sql.init.data-locations=classpath:db/data-datascope-h2.sql",
                "sw.tenant.ignore-tables[0]=sys_menu"
        }
)
@ActiveProfiles("test")
@DisplayName("用户组数据范围与零隐式授权集成测试")
class UserGroupDataScopeIntegrationTest {

    @Autowired
    private SysUserGroupService sysUserGroupService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicLong seq = new AtomicLong(10000);

    @BeforeEach
    void setUp() {
        // 物理清空，避免跨用例干扰
        jdbcTemplate.update("DELETE FROM sys_user_group_member");
        jdbcTemplate.update("DELETE FROM sys_user_group");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_dept");
        seedDept(1L, 0L);
        seedDept(11L, 1L);
        seedDept(12L, 1L);
        seedUser("u11", 11L);
        seedUser("u12", 12L);
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

    private long seedUser(String username, Long deptId) {
        long id = seq.incrementAndGet();
        jdbcTemplate.update("""
                        INSERT INTO sys_user (id, username, password, status, dept_id, create_by, tenant_id)
                        VALUES (?, ?, 'x', 0, ?, ?, 0)
                        """,
                id, username, deptId, 0L);
        return id;
    }

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

    private Long createGroup(String code, String name, Long... memberIds) {
        // I5 fail-closed：种子/建组动作发生在 loginAs 之前，显式建立租户 0 上下文
        //（原实现依赖无上下文回落租户 0 的隐式填充）
        if (com.sw.ck.security.holder.LoginUserHolder.get() == null) {
            com.sw.ck.security.holder.LoginUser bootstrap = new com.sw.ck.security.holder.LoginUser();
            bootstrap.setUserId(0L);
            bootstrap.setTenantId(0L);
            com.sw.ck.security.holder.LoginUserHolder.set(bootstrap);
        }
        SysUserGroup withMembers = new SysUserGroup();
        withMembers.setGroupCode(code);
        withMembers.setGroupName(name);
        withMembers.setStatus(0);
        if (memberIds.length > 0) {
            withMembers.setMemberIds(List.of(memberIds));
        }
        return sysUserGroupService.create(withMembers);
    }

    private PageResult<SysUserGroup> pageGroups(String name) {
        SysUserGroup query = new SysUserGroup();
        query.setGroupName(name);
        PageParam param = new PageParam();
        param.setPageNum(1);
        param.setPageSize(50);
        return sysUserGroupService.page(param, query);
    }

    // ─── 数据范围过滤 ──────────────────────────────────────

    @Test
    @DisplayName("组列表：DEPT 数据范围仅返回成员部门命中的组")
    void page_deptScope_shouldFilterByMemberDept() {
        long u11 = seedUser("u11x", 11L);
        long u12 = seedUser("u12x", 12L);
        createGroup("G-ALL", "全员组", u11, u12);
        createGroup("G-D11", "研发组", u11);
        createGroup("G-D12", "市场组", u12);

        loginAs(DataScopeType.DEPT, u11, 11L, Set.of(), false);
        PageResult<SysUserGroup> result = pageGroups(null);

        // DEPT(11) 只可见成员含部门 11 用户的组（全员组 + 研发组），市场组不可见
        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords()).extracting(SysUserGroup::getGroupCode)
                .containsExactlyInAnyOrder("G-ALL", "G-D11");
    }

    @Test
    @DisplayName("组列表：超管短路全部范围限制，返回全量")
    void page_superAdmin_shouldSeeAll() {
        long u11 = seedUser("u11x", 11L);
        long u12 = seedUser("u12x", 12L);
        createGroup("G-ALL", "全员组", u11, u12);

        loginAs(DataScopeType.SELF, u11, 11L, Set.of(), true);
        PageResult<SysUserGroup> result = pageGroups(null);

        assertThat(result.getTotal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("CUSTOM 空关联 → 恒假返回 0 行")
    void page_customEmpty_shouldReturnEmpty() {
        long u11 = seedUser("u11x", 11L);
        createGroup("G-D11", "研发组", u11);

        loginAs(DataScopeType.CUSTOM, u11, 11L, Set.of(), false);
        PageResult<SysUserGroup> result = pageGroups(null);

        assertThat(result.getTotal()).isZero();
    }

    @Test
    @DisplayName("成员候选：DEPT 数据范围仅返回本部门启用用户")
    void candidates_deptScope_shouldReturnOnlySameDept() {
        long u11 = seedUser("u11x", 11L);
        long u12 = seedUser("u12x", 12L);

        loginAs(DataScopeType.DEPT, u11, 11L, Set.of(), false);
        PageResult<SysUser> result = sysUserGroupService.memberCandidates(new PageParam(), null);

        assertThat(result.getRecords()).extracting(SysUser::getUsername).containsExactlyInAnyOrder("u11", "u11x");
    }

    // ─── 成员绑定校验与原子性 ──────────────────────────────────

    @Test
    @DisplayName("绑定：数据范围外用户（跨部门不可见）→ 拒绝且不产生部分写入")
    void bind_crossScopeUser_shouldRejectAtomically() {
        long u11 = seedUser("u11x", 11L);
        long u12 = seedUser("u12x", 12L);
        loginAs(DataScopeType.DEPT, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        // u12 不在 DEPT(11) 数据范围内 → 绑定被拒绝（先删后插整体回滚）
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sysUserGroupService.updateMemberIds(groupId, List.of(u11, u12)))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);

        // 原子性：拒绝后组成员仍为原样，不残留部分写入
        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
    }

    @Test
    @DisplayName("绑定：停用用户 → 拒绝且不产生部分写入")
    void bind_disabledUser_shouldReject() {
        long u11 = seedUser("u11x", 11L);
        long disabled = seedUser("u11y", 11L);
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = ?", disabled);
        loginAs(DataScopeType.ALL, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sysUserGroupService.updateMemberIds(groupId, List.of(u11, disabled)))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);

        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
    }

    @Test
    @DisplayName("绑定：锁定用户（status=2）→ 拒绝且不产生部分写入")
    void bind_lockedUser_shouldReject() {
        long u11 = seedUser("u11x", 11L);
        long locked = seedUser("u11y", 11L);
        jdbcTemplate.update("UPDATE sys_user SET status = 2 WHERE id = ?", locked);
        loginAs(DataScopeType.ALL, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sysUserGroupService.updateMemberIds(groupId, List.of(u11, locked)))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);

        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
    }

    @Test
    @DisplayName("绑定：逻辑删除用户（deleted=1）→ 拒绝且不产生部分写入")
    void bind_deletedUser_shouldReject() {
        long u11 = seedUser("u11x", 11L);
        long deleted = seedUser("u11y", 11L);
        jdbcTemplate.update("UPDATE sys_user SET deleted = 1 WHERE id = ?", deleted);
        loginAs(DataScopeType.ALL, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sysUserGroupService.updateMemberIds(groupId, List.of(u11, deleted)))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);

        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
    }

    @Test
    @DisplayName("绑定：不存在 ID → 拒绝且不产生部分写入")
    void bind_nonexistentUser_shouldReject() {
        long u11 = seedUser("u11x", 11L);
        loginAs(DataScopeType.ALL, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sysUserGroupService.updateMemberIds(groupId, List.of(u11, 999999L)))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);

        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
    }

    @Test
    @DisplayName("绑定：跨租户用户 ID → 拒绝（成员候选同源校验，租户拦截器不可见）且不产生部分写入")
    void bind_crossTenantUser_shouldReject() {
        long u11 = seedUser("u11x", 11L);
        // 租户 9 的启用用户：同库但不同 tenant_id，租户拦截器对当前租户不可见
        jdbcTemplate.update("""
                        INSERT INTO sys_user (id, username, password, status, dept_id, create_by, tenant_id)
                        VALUES (?, 'u99x', 'x', 0, 11, 0, 9)
                        """,
                77777L);
        loginAs(DataScopeType.ALL, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        sysUserGroupService.updateMemberIds(groupId, List.of(u11, 77777L)))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);

        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
    }

    @Test
    @DisplayName("停用组：列表可见且保留成员；重新启用后仍可整量替换成员")
    void disabledGroup_keepsMembers_andReenabled_editable() {
        long u11 = seedUser("u11x", 11L);
        loginAs(DataScopeType.ALL, u11, 11L, Set.of(), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        sysUserGroupService.disable(groupId);
        // 停用保留配置与成员（有效成员查询仍返回既有成员）
        assertThat(sysUserGroupService.listMemberIds(groupId)).containsExactly(u11);
        // 停用组仍出现在列表（可管理）
        assertThat(pageGroups(null).getTotal()).isEqualTo(1L);

        sysUserGroupService.enable(groupId);
        sysUserGroupService.updateMemberIds(groupId, List.of());
        assertThat(sysUserGroupService.listMemberIds(groupId)).isEmpty();
    }

    // ─── 零隐式授权 ──────────────────────────────────────

    @Test
    @DisplayName("零隐式授权：加入/移出用户组不改变用户角色、菜单、数据范围与 superadmin 判定")
    void noImplicitAuthorization() {
        long u11 = seedUser("u11x", 11L);
        // 对照组：绑定前后登录上下文（角色/数据范围/superadmin）必须完全不变
        loginAs(DataScopeType.DEPT, u11, 11L, Set.of(1L), false);
        Long groupId = createGroup("G-11", "研发组", u11);

        assertThat(LoginUserHolder.get().getDataScope()).isEqualTo(DataScope.DEPT);
        assertThat(LoginUserHolder.get().getCustomDeptIds()).containsExactly(1L);
        assertThat(LoginUserHolder.get().isSuperAdmin()).isFalse();

        sysUserGroupService.updateMemberIds(groupId, List.of());
        sysUserGroupService.addMemberIds(groupId, List.of(u11));

        // 权限装配相关表零改动：sys_user_role / sys_role_menu / sys_role_dept 全表为空
        Integer roleRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user_role", Integer.class);
        Integer roleMenuRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role_menu", Integer.class);
        Integer roleDeptRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_role_dept", Integer.class);
        assertThat(roleRows).isZero();
        assertThat(roleMenuRows).isZero();
        assertThat(roleDeptRows).isZero();

        assertThat(LoginUserHolder.get().getDataScope()).isEqualTo(DataScope.DEPT);
        assertThat(LoginUserHolder.get().isSuperAdmin()).isFalse();
    }

    // ─── 测试上下文配置（对齐 SysUserDataScopeTest 装配，独立包避免 Bean 冲突） ───

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
        public SysUserGroupService sysUserGroupService(SysUserGroupMemberMapper memberMapper, SysUserMapper userMapper,
                                                       LoginContextProvider loginContextProvider) {
            return new SysUserGroupServiceImpl(memberMapper, userMapper, loginContextProvider);
        }

        @Bean
        public DeptScopeProvider deptScopeProvider(@Lazy SysDeptMapper sysDeptMapper) {
            return new DeptScopeProviderImpl(sysDeptMapper);
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
