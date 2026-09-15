package com.sw.ck.system.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.handler.RestAccessDeniedHandler;
import com.sw.ck.security.handler.RestAuthenticationEntryPoint;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.mapper.SysMenuMapper;
import com.sw.ck.system.mapper.SysRoleDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.security.UserDetailsProviderImpl;
import com.sw.ck.system.service.SysMenuService;
import com.sw.ck.system.service.SysUserService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /system/auth/menus} 非超管授权菜单过滤链的请求级契约与撤权语义证据（P1/M02-F02/F03 验收 5）。
 * <p>
 * 请求经过真实 Spring Security 过滤链、真实 {@link AuthMeController}、真实
 * {@link SysMenuServiceImpl}（MyBatis-Plus + 租户拦截器 + 逻辑删除）与 H2 数据库，
 * 覆盖验收 5「已授权菜单可见（非超管正面）」「撤权后菜单不可达」两个缺口子项：
 * <ul>
 *   <li>正面：非超管用户经 sys_user_role→sys_role_menu 绑定（目录/页面/按钮混合行）
 *       → 菜单树只含绑定行，树形结构（parent_id=0 根、children 挂载、sort 升序）正确</li>
 *   <li>按钮行契约：menu_type=2 行出现在树中且 component=null，permission 原样返回</li>
 *   <li>撤权（绑定删除）：删除 sys_role_menu 绑定 → 菜单树为空列表</li>
 *   <li>撤权（角色停用 status=0）：菜单侧与权限侧对称过滤——{@code loadMenuIdsByUserId}
 *       与 {@link UserDetailsProviderImpl} 均只装配启用角色（status=1），停用后
 *       菜单树为空、按钮 permission 不再装配，角色停用成为有效撤权手段</li>
 *   <li>无绑定空树：无任何 sys_user_role 绑定 → 空列表（请求级 + service 级）</li>
 *   <li>超管旁路对照 / 未认证 401 / 菜单树级租户隔离</li>
 *   <li>真实装配侧（{@link UserDetailsProviderImpl}）：角色停用从 roles 列表剔除
 *       （status=1 过滤），菜单树与按钮 permissions 同步不再装配——两条撤权路径
 *       行为一致</li>
 * </ul>
 */
@SpringJUnitConfig(AuthMenusContractAndSecurityTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("认证菜单树端点：非超管授权过滤 + 撤权语义 + 契约（请求级）")
class AuthMenusContractAndSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysMenuService sysMenuService;

    @Autowired
    private UserDetailsProvider userDetailsProvider;

    // ==================== 表结构（与 AuthFlowIntegrationTest / RoleMenusContractAndSecurityTest 同源 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_role (
                    id                bigint          not null primary key,
                    name              varchar(64)     not null,
                    code              varchar(64)     not null,
                    sort              integer         not null default 0,
                    status            smallint        not null default 0,
                    data_scope        smallint,
                    built_in          boolean         not null default false,
                    remark            varchar(255),
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_menu (
                    id                bigint          not null primary key,
                    parent_id         bigint          not null default 0,
                    name              varchar(64)     not null,
                    title             varchar(64)     not null default '',
                    menu_type         smallint        not null default 0,
                    path              varchar(128),
                    component         varchar(255),
                    permission        varchar(128),
                    icon              varchar(64),
                    sort              integer         not null default 0,
                    hidden            boolean         not null default false,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_role_menu (
                    id                bigint          not null primary key,
                    role_id           bigint          not null,
                    menu_id           bigint          not null,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_user_role (
                    id                bigint          not null primary key,
                    user_id           bigint          not null,
                    role_id           bigint          not null,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_user (
                    id                bigint          not null primary key,
                    username          varchar(50)     not null,
                    password          varchar(200)    not null,
                    real_name         varchar(50),
                    email             varchar(100),
                    phone             varchar(20),
                    avatar            varchar(200),
                    sex               smallint        not null default 0,
                    status            smallint        not null default 0,
                    dept_id           bigint,
                    is_admin          smallint        not null default 0,
                    last_login_time   timestamp,
                    last_login_ip     varchar(50),
                    remark            clob,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_role_dept (
                    id                bigint          not null primary key,
                    role_id           bigint          not null,
                    dept_id           bigint          not null,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id, deleted)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_tenant_code ON sys_role (tenant_id, code, deleted)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_role_tenant ON sys_user_role (tenant_id, user_id, role_id, deleted)");
    }

    // ==================== 前置/后置 ====================

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_role_dept");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_menu");
        jdbcTemplate.update("DELETE FROM sys_user");

        // ---- 用户 ----
        // 1=普通管理员 u1（非超管，请求级测试主体），2=无绑定用户 u2，3=启用角色对照用户 u3，
        // 4=停用角色用户 u4，5=多角色去重用户 u5（admin+assist）
        insertUser(1L, "u1");
        insertUser(2L, "u2");
        insertUser(3L, "u3");
        insertUser(4L, "u4");
        insertUser(5L, "u5");

        // ---- 角色 ----
        // 1=superadmin（built_in=true），2=admin（status=1 启用），3=assist（status=1，与 role2 共绑 100 验证去重），
        // 4=disabled（status=0 停用，绑定不删），5=tenant5（租户 5 角色）
        insertRole(1L, "超级管理员", "superadmin", 0, 1, true, 0L);
        insertRole(2L, "普通管理员", "admin", 1, 1, false, 0L);
        insertRole(3L, "辅助角色", "assist", 2, 1, false, 0L);
        insertRole(4L, "停用角色", "disabled", 3, 0, false, 0L);
        insertRole(5L, "租户5角色", "tenant5_role", 1, 1, false, 5L);

        // ---- 菜单树（目录 100/200，页面 110/120/300，按钮 111 混合行）----
        // 100: 目录(sort=10) ─ 110: 页面(sort=10) ─ 111: 按钮(sort=10)
        //                    └ 120: 页面(sort=20)
        // 200: 目录(sort=20，未绑定)
        // 300: 页面(sort=30，仅绑定租户5角色)
        insertMenu(100L, 0L, "Dir100", "目录100", 0, "d100", null, null, 10);
        insertMenu(110L, 100L, "Menu110", "页面110", 1, "m110", "system/views/M110", "system:user:list", 10);
        insertMenu(111L, 110L, "Btn111", "按钮111", 2, null, null, "system:user:btn111", 10);
        insertMenu(120L, 100L, "Menu120", "页面120", 1, "m120", "system/views/M120", "system:role:list", 20);
        insertMenu(200L, 0L, "Dir200", "目录200", 0, "d200", null, null, 20);
        insertMenu(300L, 0L, "Menu300", "页面300", 1, "m300", "system/views/M300", "tenant5:view", 30);

        // ---- 用户-角色绑定 ----
        // u1 → admin(2) + tenant5(5)；u2 → 无；u3 → admin(2)；u4 → disabled(4)；u5 → admin(2) + assist(3)
        insertUserRole(1L, 1L, 2L, 0L);
        insertUserRole(2L, 1L, 5L, 5L);
        insertUserRole(3L, 3L, 2L, 0L);
        insertUserRole(4L, 4L, 4L, 0L);
        insertUserRole(5L, 5L, 2L, 0L);
        insertUserRole(6L, 5L, 3L, 0L);

        // ---- 角色-菜单绑定 ----
        // admin(2) → [100,110,111,120]；assist(3) → [100]（与 admin 重复绑定验证去重）；disabled(4) → [100,110,111,120]；
        // tenant5(5) → [300]（租户 5）
        insertRoleMenu(1L, 2L, 100L, 0L);
        insertRoleMenu(2L, 2L, 110L, 0L);
        insertRoleMenu(3L, 2L, 111L, 0L);
        insertRoleMenu(4L, 2L, 120L, 0L);
        insertRoleMenu(5L, 3L, 100L, 0L);
        insertRoleMenu(6L, 4L, 100L, 0L);
        insertRoleMenu(7L, 4L, 110L, 0L);
        insertRoleMenu(8L, 4L, 111L, 0L);
        insertRoleMenu(9L, 4L, 120L, 0L);
        insertRoleMenu(10L, 5L, 300L, 5L);

        // ---- 测试认证过滤器默认态 ----
        TestAuthenticationFilter.userId = 1L;
        TestAuthenticationFilter.superAdmin = false;
        TestAuthenticationFilter.tenantId = 0L;
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.userId = 1L;
        TestAuthenticationFilter.superAdmin = false;
        TestAuthenticationFilter.tenantId = 0L;
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== 种子辅助方法 ====================

    private void insertUser(long id, String username) {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, status, is_admin,
                                      deleted, tenant_id, version)
                VALUES (?, ?, 'x', ?, ?, 0, 0, 0, 0, 0)
                """, id, username, username, 1L);
    }

    private void insertRole(long id, String name, String code, int sort, int status, boolean builtIn, long tenantId) {
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0)
                """, id, name, code, sort, status, builtIn, tenantId);
    }

    private void insertMenu(long id, long parentId, String name, String title, int menuType,
                            String path, String component, String permission, int sort) {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component,
                                      permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, id, parentId, name, title, false, menuType, path, component, permission, "Setting", sort);
    }

    private void insertUserRole(long id, long userId, long roleId, long tenantId) {
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, ?, 0)
                """, id, userId, roleId, tenantId);
    }

    private void insertRoleMenu(long id, long roleId, long menuId, long tenantId) {
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, ?, 0)
                """, id, roleId, menuId, tenantId);
    }

    // ==================== 请求级辅助方法 ====================

    /** 以当前 TestAuthenticationFilter 静态态发起 GET /system/auth/menus，返回 JSON 根节点。 */
    private JsonNode getMenus() throws Exception {
        MvcResult result = mockMvc.perform(get("/system/auth/menus")
                        .header("X-Test-User", "u1"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 在树中按 id 查找节点（含递归 children）。 */
    private JsonNode findNode(JsonNode tree, String id) {
        for (JsonNode node : tree) {
            if (id.equals(node.get("id").asText())) {
                return node;
            }
            JsonNode hit = findNode(node.get("children"), id);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private List<String> idsOf(JsonNode tree) {
        List<String> ids = new ArrayList<>();
        tree.forEach(n -> ids.add(n.get("id").asText()));
        return ids;
    }

    // ==================== A1 正面：非超管已授权菜单可见（请求级） ====================

    @Test
    @DisplayName("非超管 u1（绑定 目录100/页面110/120/按钮111）→ 菜单树只含绑定行，树形结构正确")
    void menus_nonSuperAdmin_shouldReturnOnlyBoundMenus() throws Exception {
        TestAuthenticationFilter.superAdmin = false;
        JsonNode body = getMenus();

        assertThat(body.get("code").asInt())
                .as("非超管应返回成功码 0")
                .isZero();

        JsonNode tree = body.get("data");
        assertThat(idsOf(tree))
                .as("根节点应只含绑定的目录 100（sort=10），未绑定的 200/300 不可见")
                .containsExactly("100");

        // 根节点契约：parentId=null、id 为 String
        JsonNode root = tree.get(0);
        assertThat(root.get("parentId").isNull())
                .as("根节点 parentId 应为 null")
                .isTrue();
        assertThat(root.get("id").isTextual())
                .as("id 应为 String 类型")
                .isTrue();
        assertThat(root.get("menuType").asInt())
                .as("根为目录（menu_type=0）")
                .isZero();

        // 子节点按 sort 升序挂载：110(sort=10) 在 120(sort=20) 之前
        JsonNode dir100Children = root.get("children");
        assertThat(idsOf(dir100Children))
                .as("目录 100 的子节点应按 sort 升序：110 → 120")
                .containsExactly("110", "120");
        assertThat(dir100Children.get(0).get("parentId").asText())
                .as("子节点 110 的 parentId 应为 String \"100\"")
                .isEqualTo("100");

        // 按钮 111 挂载在页面 110 之下
        JsonNode menu110 = findNode(tree, "110");
        assertThat(menu110).isNotNull();
        assertThat(idsOf(menu110.get("children")))
                .as("页面 110 的子节点应为按钮 111")
                .containsExactly("111");

        // 未绑定行不可达
        assertThat(findNode(tree, "200"))
                .as("未绑定的目录 200 不应出现在树中")
                .isNull();
        assertThat(findNode(tree, "300"))
                .as("未绑定的页面 300 不应出现在树中")
                .isNull();
    }

    // ==================== A2 按钮行契约 ====================

    @Test
    @DisplayName("按钮行契约：menu_type=2 节点 component=null、permission 原样返回；目录 component=null；页面 component 保留")
    void menus_voContract_shouldMapButtonAndDirComponentToNullAndKeepPermission() throws Exception {
        TestAuthenticationFilter.superAdmin = false;
        JsonNode tree = getMenus().get("data");

        // 按钮 111：menu_type=2，component=null，permission 原样返回
        JsonNode btn = findNode(tree, "111");
        assertThat(btn).as("按钮 111 应出现在树中").isNotNull();
        assertThat(btn.get("menuType").asInt())
                .as("按钮 menu_type 应为 2")
                .isEqualTo(2);
        assertThat(btn.get("component").isNull())
                .as("按钮 component 应为 null（AuthMenuVO 转换契约）")
                .isTrue();
        assertThat(btn.get("permission").asText())
                .as("按钮 permission 应原样返回")
                .isEqualTo("system:user:btn111");

        // 目录 100：menu_type=0，component=null
        JsonNode dir = findNode(tree, "100");
        assertThat(dir.get("component").isNull())
                .as("目录 component 应为 null")
                .isTrue();

        // 页面 110：menu_type=1，component 保留
        JsonNode page = findNode(tree, "110");
        assertThat(page.get("menuType").asInt()).isEqualTo(1);
        assertThat(page.get("component").asText())
                .as("页面 component 应保留原值")
                .isEqualTo("system/views/M110");
        assertThat(page.get("permission").asText())
                .as("页面 permission 应原样返回")
                .isEqualTo("system:user:list");
    }

    // ==================== A3 撤权：绑定删除 → 菜单不可达 ====================

    @Test
    @DisplayName("撤权（删除 sys_role_menu 绑定）→ 同一用户菜单树为空列表")
    void menus_afterDeletingRoleMenuBindings_shouldReturnEmptyTree() throws Exception {
        TestAuthenticationFilter.superAdmin = false;

        // 撤权前：菜单树非空
        assertThat(getMenus().get("data"))
                .as("撤权前非超管应可见绑定菜单")
                .isNotEmpty();

        // 撤权：删除 u1 角色（admin=2）的全部菜单绑定（方向 §2.3 主撤权路径）
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = 2 AND tenant_id = 0");

        JsonNode body = getMenus();
        assertThat(body.get("code").asInt())
                .as("撤权后仍应返回成功码 0")
                .isZero();
        assertThat(body.get("data").size())
                .as("撤权（绑定删除）后菜单树应为空列表")
                .isZero();
    }

    // ==================== A4 撤权：角色停用（status=0）= 有效撤权 ====================

    @Test
    @DisplayName("撤权（角色停用 status=0，绑定不删）→ 菜单树为空：菜单侧按启用角色过滤（与权限装配对称）")
    void menus_roleDisabled_shouldRevokeBoundMenus() throws Exception {
        TestAuthenticationFilter.superAdmin = false;

        // 停用前：菜单树非空
        assertThat(getMenus().get("data"))
                .as("停用前非超管应可见绑定菜单")
                .isNotEmpty();

        // 角色停用：admin(2) status 1→0，绑定行保持不变
        jdbcTemplate.update("UPDATE sys_role SET status = 0 WHERE id = 2");

        JsonNode body = getMenus();
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").size())
                .as("角色停用后菜单树应为空：loadMenuIdsByUserId 仅装配启用角色（status=1），"
                        + "与 UserDetailsProviderImpl 权限装配对称，角色停用 = 有效撤权")
                .isZero();
    }

    @Test
    @DisplayName("service 级：角色停用后 getMenuTree(该用户, false) → 空列表")
    void service_getMenuTree_roleDisabled_shouldReturnEmptyList() {
        // service 级直调无认证过滤器：显式建立种子数据所属的租户 0 上下文（I5 fail-closed 后不再隐式回落）
        LoginUserHolder.set(tenantZeroUser());
        jdbcTemplate.update("UPDATE sys_role SET status = 0 WHERE id = 2");

        assertThat(sysMenuService.getMenuTree(1L, false))
                .as("停用角色不再贡献任何菜单（service 级对称证据）")
                .isEmpty();
    }

    // ==================== A5 无绑定空树（请求级 + service 级） ====================

    @Test
    @DisplayName("无任何绑定用户（u2，无 sys_user_role 行）→ 菜单树为空列表（请求级）")
    void menus_userWithoutAnyBinding_shouldReturnEmptyTree() throws Exception {
        TestAuthenticationFilter.userId = 2L;
        TestAuthenticationFilter.superAdmin = false;

        JsonNode body = getMenus();
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").size())
                .as("无绑定用户菜单树应为空列表")
                .isZero();
    }

    @Test
    @DisplayName("service 级：getMenuTree(无绑定 userId, false) → 空列表")
    void service_getMenuTree_userWithoutBindings_shouldReturnEmptyList() {
        LoginUserHolder.set(tenantZeroUser());
        assertThat(sysMenuService.getMenuTree(2L, false))
                .as("无绑定用户 service 级应返回空列表")
                .isEmpty();
    }

    @Test
    @DisplayName("service 级：多角色重复绑定同一菜单 → 去重后树只含绑定行")
    void service_getMenuTree_userWithMultipleRoles_shouldDeduplicateAndFilterByBindings() {
        LoginUserHolder.set(tenantZeroUser());
        // u5 同时持 admin(2)→[100,110,111,120] 与 assist(3)→[100]：100 经两条路径绑定
        List<AuthMenuVO> tree = sysMenuService.getMenuTree(5L, false);

        assertThat(tree)
                .as("树应只含绑定根目录 100")
                .hasSize(1);
        assertThat(tree.get(0).getId()).isEqualTo("100");
        assertThat(tree.get(0).getChildren())
                .as("子节点按 sort 升序：110 → 120")
                .extracting(AuthMenuVO::getId)
                .containsExactly("110", "120");
        // 去重断言：全树 id 计数中 "100" 只出现一次
        assertThat(countAllIds(tree).stream().filter("100"::equals).count())
                .as("重复绑定不应产生重复节点")
                .isEqualTo(1);
    }

    /** 种子数据所属租户 0 的测试身份（service 级直调用）。 */
    private com.sw.ck.security.holder.LoginUser tenantZeroUser() {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        user.setPermissions(java.util.List.of());
        return user;
    }

    /** 平铺返回树内全部节点 id（含递归 children）。 */
    private List<String> countAllIds(List<AuthMenuVO> nodes) {
        List<String> ids = new ArrayList<>();
        for (AuthMenuVO node : nodes) {
            ids.add(node.getId());
            ids.addAll(countAllIds(node.getChildren()));
        }
        return ids;
    }

    // ==================== A6 超管旁路对照 ====================

    @Test
    @DisplayName("超管（superAdmin=true）→ 返回全量菜单树，含未绑定目录/页面（旁路对照）")
    void menus_superAdmin_shouldReturnFullTreeIncludingUnboundMenus() throws Exception {
        TestAuthenticationFilter.superAdmin = true;

        JsonNode body = getMenus();
        assertThat(body.get("code").asInt()).isZero();
        JsonNode tree = body.get("data");
        assertThat(idsOf(tree))
                .as("超管应返回全量根节点（含未绑定的 200/300），按 sort 升序")
                .containsExactly("100", "200", "300");
        assertThat(findNode(tree, "300"))
                .as("超管应可见未绑定页面 300")
                .isNotNull();
    }

    // ==================== A7 未认证 ====================

    @Test
    @DisplayName("未认证请求（无 X-Test-User）→ HTTP 401 + body code=401")
    void menus_unauthenticated_shouldReturn401() throws Exception {
        MvcResult result = mockMvc.perform(get("/system/auth/menus"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("未认证应返回 code=401")
                .isEqualTo(401);
    }

    // ==================== A8 菜单树级租户隔离 ====================

    @Test
    @DisplayName("租户隔离：u1 的租户5角色（绑定 300）不进入租户0 菜单树")
    void menus_tenantIsolation_shouldNotSeeOtherTenantRoleBindings() throws Exception {
        TestAuthenticationFilter.superAdmin = false;
        TestAuthenticationFilter.tenantId = 0L;

        JsonNode tree = getMenus().get("data");

        // u1 同时持租户0 角色 admin（→100/110/111/120）与租户5 角色 tenant5（→300）
        assertThat(idsOf(tree)).containsExactly("100");
        assertThat(findNode(tree, "300"))
                .as("租户5 角色的绑定 300 不应出现在租户0 用户的菜单树中")
                .isNull();
    }

    // ==================== A9 撤权语义：真实装配（UserDetailsProviderImpl） ====================

    @Test
    @DisplayName("角色停用 vs 启用（真实装配）：停用角色从 roles 剔除，按钮 permission 不再装配")
    void provider_roleStatus_shouldRevokeRolesMenusAndPermissions() {
        // service 级 getMenuTree 直调无认证过滤器：显式建立租户 0 上下文（I5 fail-closed）
        LoginUserHolder.set(tenantZeroUser());
        // ---- 启用角色用户 u3：roles=[admin]，permissions 含绑定按钮权限 ----
        com.sw.ck.security.holder.LoginUser active = userDetailsProvider.loadByUserId(3L);
        assertThat(active.getRoles())
                .as("启用角色应装配进 roles")
                .containsExactly("admin");
        assertThat(active.getPermissions())
                .as("启用角色用户应装配按钮 permission")
                .contains("system:user:btn111");
        assertThat(sysMenuService.getMenuTree(3L, false))
                .as("启用角色用户应可见绑定菜单树")
                .isNotEmpty();

        // ---- 停用角色用户 u4（status=0，绑定 [100,110,111,120] 保留）----
        com.sw.ck.security.holder.LoginUser disabled = userDetailsProvider.loadByUserId(4L);
        assertThat(disabled.getRoles())
                .as("停用角色应被 status=1 过滤，roles 为空")
                .isEmpty();
        assertThat(disabled.isSuperAdmin()).as("停用角色不触发超管判定").isFalse();
        // 权限装配仅取启用角色（与 roles 同源过滤）→ 停用角色绑定不再贡献按钮 permission
        assertThat(disabled.getPermissions())
                .as("停用角色绑定不应再贡献按钮 permission（与菜单侧对称，角色停用 = 有效撤权）")
                .isEmpty();
        // 菜单树侧同步：停用角色不再贡献菜单（与 A4 请求级结论一致）
        assertThat(sysMenuService.getMenuTree(4L, false))
                .as("停用角色绑定的菜单树应为空（菜单侧按启用角色过滤）")
                .isEmpty();
    }

    // ==================== 测试配置 ====================

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @MapperScan("com.sw.ck.system.mapper")
    @ComponentScan("com.sw.ck.system.service.impl")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:authmenus;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
            // 与生产 application.yml 一致：sys_menu 全局表不进租户拦截器
            props.setIgnoreTables(List.of("sys_menu"));
            return props;
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
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public CommonTenantLineHandler commonTenantLineHandler(
                TenantProperties tenantProperties, LoginContextProvider loginContextProvider) {
            return new CommonTenantLineHandler(tenantProperties, loginContextProvider);
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(CommonTenantLineHandler handler) {
            return new TenantLineInnerInterceptor(handler);
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.system.entity");
            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            ibatisConfig.setUseGeneratedKeys(true);
            factory.setConfiguration(ibatisConfig);
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }

        // 生产实现：非超管经 sys_user_role→sys_role_menu 装配 roles/permissions/菜单
        @Bean
        public UserDetailsProvider userDetailsProvider(
                SysUserService sysUserService,
                SysUserRoleMapper sysUserRoleMapper,
                SysRoleMapper sysRoleMapper,
                SysRoleMenuMapper sysRoleMenuMapper,
                SysMenuMapper sysMenuMapper,
                SysRoleDeptMapper sysRoleDeptMapper) {
            return new UserDetailsProviderImpl(sysUserService, sysUserRoleMapper, sysRoleMapper,
                    sysRoleMenuMapper, sysMenuMapper, sysRoleDeptMapper);
        }

        @Bean
        public AuthMeController authMeController(
                SysUserService sysUserService,
                SysMenuService sysMenuService) {
            return new AuthMeController(sysUserService, sysMenuService);
        }

        @Bean
        public TestAuthenticationFilter testAuthenticationFilter() {
            return new TestAuthenticationFilter();
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // 生产 401/403 响应处理器：HTTP 401/403 + R{code:401/403} body
        @Bean
        public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
            return new RestAuthenticationEntryPoint(objectMapper);
        }

        @Bean
        public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
            return new RestAccessDeniedHandler(objectMapper);
        }

        @Bean
        public Filter springSecurityFilterChain(HttpSecurity http, TestAuthenticationFilter filter,
                                                RestAuthenticationEntryPoint authenticationEntryPoint,
                                                RestAccessDeniedHandler accessDeniedHandler) throws Exception {
            return new FilterChainProxy(http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler))
                    .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                    .build());
        }

        @Bean
        public MockMvc mockMvc(WebApplicationContext context,
                               @Qualifier("springSecurityFilterChain") Filter chain) {
            return MockMvcBuilders.webAppContextSetup(context).addFilters(chain).build();
        }
    }

    /**
     * 测试认证过滤器：带 {@code X-Test-User} 头视为已认证，用户 id / 权限 / 超管 / 租户由静态字段驱动，
     * 经 LoginUserHolder 装配（与 RoleMenusContractAndSecurityTest 同模式）。
     */
    static class TestAuthenticationFilter extends OncePerRequestFilter {
        private static volatile long userId = 1L;
        private static volatile boolean superAdmin = false;
        private static volatile long tenantId = 0L;

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (request.getHeader("X-Test-User") != null) {
                LoginUser user = new LoginUser();
                user.setUserId(userId);
                user.setUsername("u" + userId);
                user.setPermissions(List.of());
                user.setSuperAdmin(superAdmin);
                user.setTenantId(tenantId);
                LoginUserHolder.set(user);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            }
            try {
                filterChain.doFilter(request, response);
            } finally {
                LoginUserHolder.clear();
                SecurityContextHolder.clearContext();
            }
        }
    }
}
