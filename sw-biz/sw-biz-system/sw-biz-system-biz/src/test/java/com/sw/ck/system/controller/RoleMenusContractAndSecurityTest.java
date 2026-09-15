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
import com.sw.ck.common.exception.GlobalExceptionHandler;
import com.sw.ck.common.response.R;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.handler.RestAccessDeniedHandler;
import com.sw.ck.security.handler.RestAuthenticationEntryPoint;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
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
import org.springframework.http.MediaType;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET/PUT /system/role/{id}/menus} 的请求级契约与安全回归证据（P1/M02-F02/F03）。
 * <p>
 * 请求经过真实 Spring Method Security（{@code @PreAuthorize} + {@code @ss.hasPermi}）、
 * 真实 {@link com.sw.ck.system.controller.RoleController}、真实
 * {@code SysRoleServiceImpl}（MyBatis-Plus + 租户拦截器 + 逻辑删除）与 H2 数据库，覆盖：
 * <ul>
 *   <li>读取：返回全部已绑定 menuId（含按钮/目录/页面行），空集合行为</li>
 *   <li>写入：先删后插替换语义、filter+distinct 去重、null/空数组=清空</li>
 *   <li>未知角色：PUT 静默成功并写入孤儿关系（删除影响 0 行仍继续插入）</li>
 *   <li>受保护角色：superadmin（built_in=true &amp;&amp; code='superadmin'）被 assertMutable 拒绝
 *       （业务异常 HTTP 200 + body code=400）；普通 admin（role id=2）不受保护可写</li>
 *   <li>鉴权链：无权限 403、未认证 401、超管旁路放行</li>
 *   <li>租户隔离：sys_role_menu 在租户拦截器作用下不可跨租户读/写</li>
 * </ul>
 * 本测试类仅新增测试，不修改任何生产代码。
 */
@SpringJUnitConfig(RoleMenusContractAndSecurityTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("角色菜单权限端点：契约 + 鉴权 + 租户隔离（请求级）")
class RoleMenusContractAndSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.sw.ck.system.service.SysRoleService sysRoleService;

    // ==================== 表结构（与 AuthFlowIntegrationTest 同源 DDL） ====================

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
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id, deleted)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_tenant_code ON sys_role (tenant_id, code, deleted)");
        // 角色成员表：PUT /menus 后的 fail-secure 成员反查（kickOutMembers）依赖真实表
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
    }

    // ==================== 前置/后置 ====================

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");

        // 角色：1=superadmin（built_in=true，受保护），2=admin（built_in=false，不受保护），3=租户 5 的角色
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0)
                """, 1L, "超级管理员", "superadmin", 0, 1, true);
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0)
                """, 2L, "普通管理员", "admin", 1, 1, false);
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, 0, 5, 0)
                """, 3L, "租户5角色", "tenant5_role", 1, 1, false);

        // 菜单：18=按钮(menu_type=2)，200=菜单(menu_type=1)，201=目录(menu_type=0)，300=菜单
        insertMenu(18L, 0L, "Btn18", "按钮18", 2, null, null, "system:role:btn18", 1);
        insertMenu(200L, 0L, "Menu200", "菜单200", 1, "m200", "system/views/M200", "system:role:list", 10);
        insertMenu(201L, 0L, "Dir201", "目录201", 0, "d201", null, null, 20);
        insertMenu(300L, 0L, "Menu300", "菜单300", 1, "m300", "system/views/M300", "tenant5:view", 30);

        // 角色-菜单绑定：role 1(superadmin) → [1]；role 2 → [18, 200, 201]（含按钮/菜单/目录）；role 3(租户5) → [300]
        insertRoleMenu(5L, 1L, 1L, 0L);
        insertRoleMenu(1L, 2L, 18L, 0L);
        insertRoleMenu(2L, 2L, 200L, 0L);
        insertRoleMenu(3L, 2L, 201L, 0L);
        insertRoleMenu(4L, 3L, 300L, 5L);

        // 角色 2 的成员：PUT /menus 后 fail-secure 成员反查真实命中（loader 缺装配按容忍路径跳过驱逐）
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id, deleted, tenant_id, version)
                VALUES (1, 100, 2, 0, 0, 0)
                """);

        TestAuthenticationFilter.permissions = List.of();
        TestAuthenticationFilter.superAdmin = false;
        TestAuthenticationFilter.tenantId = 0L;
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.permissions = List.of();
        TestAuthenticationFilter.superAdmin = false;
        TestAuthenticationFilter.tenantId = 0L;
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void insertMenu(long id, long parentId, String name, String title, int menuType,
                            String path, String component, String permission, int sort) {
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component,
                                      permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, id, parentId, name, title, false, menuType, path, component, permission, "Setting", sort);
    }

    private void insertRoleMenu(long id, long roleId, long menuId, long tenantId) {
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, ?, 0)
                """, id, roleId, menuId, tenantId);
    }

    private long countRoleMenuRows(long roleId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND deleted = 0",
                Long.class, roleId);
    }

    private JsonNode getMenus(String roleId) throws Exception {
        MvcResult result = mockMvc.perform(get("/system/role/" + roleId + "/menus")
                        .header("X-Test-User", "admin"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode putMenus(String roleId, String body) throws Exception {
        MvcResult result = mockMvc.perform(put("/system/role/" + roleId + "/menus")
                        .header("X-Test-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ==================== A1 读取语义 ====================

    @Test
    @DisplayName("GET /menus → 返回全部已绑定 menuId（含按钮/菜单/目录行），code=0")
    void getMenus_shouldReturnAllBoundIdsIncludingButtonAndDirRows() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:list");
        JsonNode body = getMenus("2");
        assertThat(body.get("code").asInt()).isZero();
        List<Long> data = new java.util.ArrayList<>();
        body.get("data").forEach(n -> data.add(n.asLong()));
        assertThat(data).containsExactlyInAnyOrder(18L, 200L, 201L);
    }

    @Test
    @DisplayName("GET /menus → 无绑定角色返回空数组 code=0")
    void getMenus_withoutBindings_shouldReturnEmptyArray() throws Exception {
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = 2");
        TestAuthenticationFilter.permissions = List.of("system:role:list");
        JsonNode body = getMenus("2");
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").size()).isZero();
    }

    // ==================== A2 替换保存语义 ====================

    @Test
    @DisplayName("PUT /menus → 先删后插替换，再次读取回填一致")
    void putMenus_shouldReplaceAndReadBack() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update", "system:role:list");
        JsonNode write = putMenus("2", "[200, 300]");
        assertThat(write.get("code").asInt()).isZero();
        assertThat(countRoleMenuRows(2L)).isEqualTo(2);
        JsonNode read = getMenus("2");
        List<Long> data = new java.util.ArrayList<>();
        read.get("data").forEach(n -> data.add(n.asLong()));
        assertThat(data).containsExactlyInAnyOrder(200L, 300L);
    }

    @Test
    @DisplayName("PUT /menus → filter+distinct 去重，重复提交不产生重复行")
    void putMenus_shouldDeduplicate() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update", "system:role:list");
        JsonNode write = putMenus("2", "[200, 200, 18, 200, 18]");
        assertThat(write.get("code").asInt()).isZero();
        assertThat(countRoleMenuRows(2L)).isEqualTo(2);
        List<Long> data = new java.util.ArrayList<>();
        getMenus("2").get("data").forEach(n -> data.add(n.asLong()));
        assertThat(data).containsExactlyInAnyOrder(200L, 18L);
    }

    @Test
    @DisplayName("PUT /menus 空数组 → 清空全部绑定")
    void putMenus_withEmptyArray_shouldClearAll() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update");
        JsonNode write = putMenus("2", "[]");
        assertThat(write.get("code").asInt()).isZero();
        assertThat(countRoleMenuRows(2L)).isZero();
        JsonNode read = getMenus("2");
        assertThat(read.get("data").size()).isZero();
    }

    @Test
    @DisplayName("PUT /menus body=null → 语义=清空（updateMenuIds 对 null 返回前已删除全部绑定）")
    void putMenus_withNullBody_shouldClearAll() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update");
        // service 级直调无认证过滤器：显式建立种子数据所属的租户 0 上下文（I5 fail-closed 后不再隐式回落）
        com.sw.ck.security.holder.LoginUserHolder.set(new com.sw.ck.security.holder.LoginUser() {{
            setUserId(1L); setTenantId(0L); setPermissions(java.util.List.of());
        }});
        // 经服务方法直接验证 null 语义：先删后插中的删除已执行，null 不插入任何行
        sysRoleService.updateMenuIds(2L, null);
        assertThat(countRoleMenuRows(2L))
                .as("null 集合同样先删除全部绑定，等价于清空")
                .isZero();
    }

    // ==================== A3 未知角色行为 ====================

    @Test
    @DisplayName("PUT /menus 到不存在的 roleId → 静默成功且写入孤儿关系（记录行为，非缺陷修复）")
    void putMenus_toUnknownRole_shouldSucceedSilentlyWithOrphanRows() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update");
        JsonNode write = putMenus("99999", "[100]");
        assertThat(write.get("code").asInt())
                .as("未知角色 PUT 应静默成功（删除影响 0 行仍继续插入）")
                .isZero();
        long orphanCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 99999 AND tenant_id = 0 AND deleted = 0",
                Long.class);
        assertThat(orphanCount)
                .as("删除影响 0 行后仍插入孤儿关系行")
                .isEqualTo(1);
    }

    // ==================== A4 受保护角色 ====================

    @Test
    @DisplayName("PUT /menus 到 superadmin → assertMutable 拒绝：HTTP 200 + body code=400，绑定未被删改")
    void putMenus_toSuperadmin_shouldBeRejectedWithParamError() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update");
        MvcResult result = mockMvc.perform(put("/system/role/1/menus")
                        .header("X-Test-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[200]"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode write = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(write.get("code").asInt())
                .as("业务异常经 GlobalExceptionHandler：HTTP 200 + body code=400")
                .isEqualTo(400);
        assertThat(write.get("msg").asText()).contains("内置超管角色不可修改或删除");
        assertThat(countRoleMenuRows(1L))
                .as("superadmin 原绑定（1 行）未被删除或改写")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /menus 到普通 admin（role id=2，built_in=false）→ 不受保护，可正常写")
    void putMenus_toAdminRole_shouldSucceed() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:update");
        JsonNode write = putMenus("2", "[200]");
        assertThat(write.get("code").asInt()).isZero();
        assertThat(countRoleMenuRows(2L)).isEqualTo(1);
    }

    // ==================== A5 鉴权链 ====================

    @Test
    @DisplayName("无 system:role:update 权限 → 403（HTTP 403 + body code=403）")
    void putMenus_withoutPermission_shouldBeForbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:role:list");
        MvcResult result = mockMvc.perform(put("/system/role/2/menus")
                        .header("X-Test-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[200]"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(countRoleMenuRows(2L)).isEqualTo(3);
    }

    @Test
    @DisplayName("未认证请求 → 401（HTTP 401 + body code=401）")
    void putMenus_unauthenticated_shouldBeUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(put("/system/role/2/menus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[200]"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(401);
    }

    @Test
    @DisplayName("超管（superAdmin=true，permissions 空）→ hasPermi 旁路放行，可写普通角色")
    void putMenus_superAdminBypass_shouldPassMethodSecurity() throws Exception {
        TestAuthenticationFilter.superAdmin = true;
        TestAuthenticationFilter.permissions = List.of();
        JsonNode write = putMenus("2", "[200]");
        assertThat(write.get("code").asInt())
                .as("超管旁路放行（无显式权限仍可达）")
                .isZero();
        assertThat(countRoleMenuRows(2L)).isEqualTo(1);
    }

    // ==================== A6 租户隔离 ====================

    @Test
    @DisplayName("租户隔离：租户0 读不到租户5 角色绑定；跨租户 roleId 按未知角色处理且不改动原租户数据")
    void roleMenus_shouldBeTenantIsolated() throws Exception {
        // 租户 0 用户：读取租户 5 的角色（role 3）→ 空数组（拦截器自动追加 tenant_id=0）
        TestAuthenticationFilter.permissions = List.of("system:role:list", "system:role:update");
        TestAuthenticationFilter.tenantId = 0L;
        JsonNode crossRead = getMenus("3");
        assertThat(crossRead.get("data").size())
                .as("跨租户读取：租户0 看不到租户5 的绑定 [300]")
                .isZero();

        // 租户 0 用户对跨租户 roleId 3 PUT → 按未知角色处理：插入的是租户 0 的孤儿行；
        // 租户 5 的原绑定不被改动（租户 5 的删除条件在拦截器注入 tenant_id=0 后影响 0 行）
        JsonNode crossWrite = putMenus("3", "[100]");
        assertThat(crossWrite.get("code").asInt()).isZero();
        long tenant0Orphan = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 3 AND tenant_id = 0 AND deleted = 0",
                Long.class);
        assertThat(tenant0Orphan).isEqualTo(1);
        long tenant5Rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 3 AND tenant_id = 5 AND deleted = 0",
                Long.class);
        assertThat(tenant5Rows)
                .as("租户5 的绑定 [300] 未被跨租户请求修改")
                .isEqualTo(1);

        // 租户 5 用户重新登录：读取 role 3 → 仍为 [300]，原租户数据不受影响
        TestAuthenticationFilter.tenantId = 5L;
        JsonNode ownRead = getMenus("3");
        List<Long> data = new java.util.ArrayList<>();
        ownRead.get("data").forEach(n -> data.add(n.asLong()));
        assertThat(data)
                .as("租户5 读取自身绑定不受跨租户写入影响")
                .containsExactly(300L);
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
                    .url("jdbc:h2:mem:rolemenus;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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

        @Bean
        public RoleController roleController(com.sw.ck.system.service.SysRoleService sysRoleService) {
            return new RoleController(sysRoleService);
        }

        @Bean("ss")
        public PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        public TestAuthenticationFilter testAuthenticationFilter() {
            return new TestAuthenticationFilter();
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // 生产全局异常处理器：业务异常（BaseException，如 superadmin 拒绝）→ HTTP 200 + body code；
        // 方法级鉴权拒绝由生产 GlobalExceptionHandler 的 AuthorizationDeniedException 分支兜底为 403（真实链路）。
        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
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
     * 测试认证过滤器：带 {@code X-Test-User} 头视为已认证，权限/超管/租户由静态字段驱动，
     * 经真实 {@link PermissionService}（{@code @ss.hasPermi}）与方法安全（{@code @PreAuthorize}）。
     */
    static class TestAuthenticationFilter extends OncePerRequestFilter {
        private static volatile List<String> permissions = List.of();
        private static volatile boolean superAdmin = false;
        private static volatile long tenantId = 0L;

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (request.getHeader("X-Test-User") != null) {
                LoginUser user = new LoginUser();
                user.setUserId(1L);
                user.setUsername("admin");
                user.setPermissions(permissions);
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
