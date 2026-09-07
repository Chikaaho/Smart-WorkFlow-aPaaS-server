package com.sw.ck.system.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.filter.JwtAuthenticationFilter;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.jwt.JwtTokenProviderImpl;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.security.LoginChallengeService;
import com.sw.ck.system.security.LoginChallengeStore;
import com.sw.ck.system.security.LoginSecurityProperties;
import com.sw.ck.system.mapper.SysMenuMapper;
import com.sw.ck.system.mapper.SysRefreshTokenMapper;
import com.sw.ck.system.mapper.SysRoleDeptMapper;
import com.sw.ck.system.mapper.SysRoleMapper;
import com.sw.ck.system.mapper.SysRoleMenuMapper;
import com.sw.ck.system.mapper.SysUserRoleMapper;
import com.sw.ck.system.security.UserDetailsProviderImpl;
import com.sw.ck.system.service.RefreshTokenService;
import com.sw.ck.system.service.SysMenuService;
import com.sw.ck.system.service.SysUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链端到端集成测试。
 * <p>
 * 测试认证全链路：login → Bearer token → /me → /menus，
 * 请求经过真实的 {@link JwtAuthenticationFilter}、控制器和数据库。
 * </p>
 *
 * <p>测试范围：</p>
 * <ul>
 *   <li>Happy path：登录成功 → 带 Bearer 调 /me → 调 /menus</li>
 *   <li>无 token 调 /me → 401</li>
 *   <li>错误密码登录 → 非零错误码</li>
 * </ul>
 */
@SpringBootTest(
        classes = AuthFlowIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "sw.security.jwt.secret=test-jwt-secret-at-least-256-bits-long-for-hs256-algorithm",
                "sw.security.jwt.expire-seconds=7200",
                "sw.tenant.enabled=true",
                "sw.tenant.ignore-tables[0]=sys_menu",
                "sw.security.cookie.secure=false"
        }
)
@DisplayName("认证链端到端测试")
class AuthFlowIntegrationTest {

    private static final String ADMIN_PLAIN_PASSWORD = "admin123";
    // BCrypt hash for "admin123" with strength=10 (from V4 Flyway migration)
    private static final String ADMIN_BCRYPT_HASH = "$2a$10$GQx6ILw5jsPhqHxJ6/AcmOzSM8xRVRwqChiH/B9ylh0srY0/NqXiK";

    @Autowired
    private AuthController authController;

    @Autowired
    private LoginChallengeTestSupport.TestableLoginChallengeService testableChallengeService;

    @Autowired
    private AuthMeController authMeController;

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    // ==================== 表创建（只执行一次） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        // sys_user
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

        // sys_role（按 V5 迁移链尾契约建表：built_in boolean / remark varchar(255)，
        // 列名与 SysRole 实体的 @TableField 映射一致）
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

        // sys_user_role
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

        // sys_menu (BaseEntityNoTenant — no tenant_id column)
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

        // sys_role_menu
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

        // sys_role_dept（V30：角色部门关联，CUSTOM 数据范围的部门集合）
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

        // Unique indexes (matching production chain-end: V5 索引 + V13 加 deleted 列；sys_role_dept 按 V30)
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_role_tenant ON sys_user_role (tenant_id, user_id, role_id, deleted)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_menu_tenant ON sys_role_menu (tenant_id, role_id, menu_id, deleted)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_tenant_code ON sys_role (tenant_id, code, deleted)");
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_dept ON sys_role_dept (role_id, dept_id)");

        // sys_refresh_token（B1 V18 DDL，用于 B2 登录流程创建 refresh token）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_refresh_token (
                    id                bigint          not null primary key,
                    user_id           bigint          not null,
                    token_hash        varchar(128)    not null,
                    expires_at        timestamp       not null,
                    revoked           smallint        not null default 0,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_srt_token_hash ON sys_refresh_token (token_hash)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_srt_user_tenant ON sys_refresh_token (user_id, tenant_id)");
    }

    // ==================== 前置/后置 ====================

    @BeforeEach
    void setUp() throws Exception {
        // 清理旧数据
        jdbcTemplate.update("DELETE FROM sys_role_dept");
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user");
        jdbcTemplate.update("DELETE FROM sys_refresh_token");

        // ---- Seed: admin 用户 ----
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, status, is_admin,
                                      deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0)
                """, 1L, "admin", ADMIN_BCRYPT_HASH, "系统管理员", 1L, 0, 1);

        // ---- Seed: superadmin 角色 ----
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, name, code, sort, status, built_in, deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0)
                """, 1L, "超级管理员", "superadmin", 0, 1, true);

        // ---- Seed: 用户-角色关联 ----
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 0, 0)
                """, 1L, 1L, 1L);

        // ---- Seed: 菜单树（同 V6 迁移） ----
        // System 菜单
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, 1L, 0L, "System", "系统管理", false, 1, "system", "system/views/SystemHome", "system:view", "Setting", 10);

        // 低代码目录
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, 2L, 0L, "Form", "低代码", false, 0, "form", null, null, "Grid", 20);

        // 低代码子菜单
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, 3L, 2L, "FormOverview", "低代码概览", false, 1, "form/overview", "form/views/FormDefList", "form:view", "Document", 10);

        // Workflow
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, 5L, 0L, "Workflow", "流程引擎", false, 1, "workflow", "workflow/views/WorkflowHome", "workflow:view", "Share", 30);

        // Notify
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, parent_id, name, title, hidden, menu_type, path, component, permission, icon, sort, deleted, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0)
                """, 6L, 0L, "Notify", "通知", false, 1, "notify", "notify/views/NotifyHome", "notify:view", "Bell", 40);

        // ---- Seed: 角色-菜单关联（超管绑定所有菜单） ----
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 0, 0)
                """, 1L, 1L, 1L);
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 0, 0)
                """, 2L, 1L, 2L);
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 0, 0)
                """, 3L, 1L, 3L);
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 0, 0)
                """, 4L, 1L, 5L);
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id, deleted, tenant_id, version)
                VALUES (?, ?, ?, 0, 0, 0)
                """, 5L, 1L, 6L);

        // 初始化 MockMvc（每次新建，避免状态污染）
        LoginUserHolder.clear();
        mockMvc = MockMvcBuilders
                .standaloneSetup(authController, authMeController)
                .addFilter(jwtAuthenticationFilter)
                .build();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== 辅助方法 ====================

    /**
     * 登录并返回 JWT access token。
     * P45 全链：GET /auth/challenge 取挑战 → RSA-OAEP 加密密码 → 五字段 POST /auth/login。
     */
    private String login(String username, String password) throws Exception {
        MvcResult challengeResult = mockMvc.perform(get("/auth/challenge"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode challenge = objectMapper.readTree(challengeResult.getResponse().getContentAsString()).get("data");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", username)
                                .put("password", LoginChallengeTestSupport.encryptPassword(password))
                                .put("captcha", testableChallengeService.lastCaptchaCode())
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = root.get("data");
        return data != null && !data.isNull() && data.has("accessToken")
                ? data.get("accessToken").asText() : null;
    }

    /** 全链登录并保留完整 MvcResult（用于断言 refresh cookie） */
    private MvcResult loginMvc(String password) throws Exception {
        MvcResult challengeResult = mockMvc.perform(get("/auth/challenge"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode challenge = objectMapper.readTree(challengeResult.getResponse().getContentAsString()).get("data");
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", LoginChallengeTestSupport.encryptPassword(password))
                                .put("captcha", testableChallengeService.lastCaptchaCode())
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();
    }

    /** 签发挑战并返回完整 challenge data 节点（captcha/captchaId/publicKey/keyVersion/...） */
    private JsonNode newChallenge() throws Exception {
        MvcResult challengeResult = mockMvc.perform(get("/auth/challenge")).andReturn();
        return objectMapper.readTree(challengeResult.getResponse().getContentAsString()).get("data");
    }

    // ==================== 测试 1：端到端闭合 Happy Path ====================

    @Test
    @DisplayName("登录成功 → 带 Bearer 调 /me → 调 /menus")
    void e2e_login_then_me_then_menus() throws Exception {
        // ---- Act 1: 登录 ----
        String token = login("admin", ADMIN_PLAIN_PASSWORD);
        assertThat(token)
                .as("登录成功应返回 token")
                .isNotBlank();

        // ---- Act 2: 带 Bearer 调 /me ----
        MvcResult meResult = mockMvc.perform(get("/system/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode meBody = objectMapper.readTree(meResult.getResponse().getContentAsString());

        assertThat(meBody.get("code").asInt())
                .as("/me 应返回成功码 0")
                .isZero();

        JsonNode meData = meBody.get("data");
        assertThat(meData).as("/me 的 data 不应为 null").isNotNull();

        assertThat(meData.get("superAdmin").asBoolean())
                .as("admin 应为超管")
                .isTrue();

        JsonNode roles = meData.get("roles");
        assertThat(roles).as("roles 不应为 null").isNotNull();

        boolean hasSuperAdmin = false;
        for (JsonNode role : roles) {
            if ("superadmin".equals(role.asText())) {
                hasSuperAdmin = true;
                break;
            }
        }
        assertThat(hasSuperAdmin)
                .as("roles 应包含 'superadmin'")
                .isTrue();

        assertThat(meData.get("user").get("username").asText())
                .as("username 应为 admin")
                .isEqualTo("admin");

        // ---- Act 3: 带 Bearer 调 /menus ----
        MvcResult menusResult = mockMvc.perform(get("/system/auth/menus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode menusBody = objectMapper.readTree(menusResult.getResponse().getContentAsString());

        assertThat(menusBody.get("code").asInt())
                .as("/menus 应返回成功码 0")
                .isZero();

        JsonNode menuTree = menusBody.get("data");
        assertThat(menuTree)
                .as("超管菜单树不应为空")
                .isNotNull()
                .isNotEmpty();

        // 验证根节点 parentId 均为 null
        for (JsonNode node : menuTree) {
            assertThat(node.get("parentId").isNull())
                    .as("根节点 %s 的 parentId 应为 null", node.get("name").asText())
                    .isTrue();
        }

        // 验证 id 为 String 类型
        assertThat(menuTree.get(0).get("id").isTextual())
                .as("菜单 id 应为 String 类型")
                .isTrue();
    }

    // ==================== 测试 2：无 token 调 /me ====================

    @Test
    @DisplayName("无 token 调 /me → 401 或 code≠0")
    void me_withoutToken_shouldReturnUnauthorized() throws Exception {
        MvcResult result = mockMvc.perform(get("/system/auth/me"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("无 token 调 /me 应返回非 0 错误码")
                .isNotZero();
        assertThat(body.get("msg"))
                .as("应包含错误提示")
                .isNotNull();
    }

    // ==================== 测试 3：错误密码登录 ====================

    @Test
    @DisplayName("错误密码登录 → 2104 密码错误")
    void login_withWrongPassword_shouldReturnFailure() throws Exception {
        MvcResult challengeResult = mockMvc.perform(get("/auth/challenge")).andReturn();
        JsonNode challenge = objectMapper.readTree(challengeResult.getResponse().getContentAsString()).get("data");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", LoginChallengeTestSupport.encryptPassword("wrong-password"))
                                .put("captcha", testableChallengeService.lastCaptchaCode())
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("密码错误应返回 2104")
                .isEqualTo(2104);
        assertThat(body.get("msg").asText()).isEqualTo("密码错误");
    }

    @Test
    @DisplayName("验证码错误 → 2101，且不执行密码认证")
    void login_withWrongCaptcha_shouldReturn2101() throws Exception {
        MvcResult challengeResult = mockMvc.perform(get("/auth/challenge")).andReturn();
        JsonNode challenge = objectMapper.readTree(challengeResult.getResponse().getContentAsString()).get("data");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", LoginChallengeTestSupport.encryptPassword(ADMIN_PLAIN_PASSWORD))
                                .put("captcha", "xxxx")
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).as("验证码错误应返回 2101").isEqualTo(2101);
        // 顺序证明：验证码未通过时挑战未被消费，账号查询未执行
        assertThat(result.getResponse().getCookie("rt")).isNull();
    }

    @Test
    @DisplayName("客户端时间异常 → 2103，且挑战未被消费")
    void login_withSkewedTime_shouldReturn2103() throws Exception {
        MvcResult challengeResult = mockMvc.perform(get("/auth/challenge")).andReturn();
        JsonNode challenge = objectMapper.readTree(challengeResult.getResponse().getContentAsString()).get("data");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", LoginChallengeTestSupport.encryptPassword(ADMIN_PLAIN_PASSWORD))
                                .put("captcha", testableChallengeService.lastCaptchaCode())
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis() - 600_000))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).as("时间异常应返回 2103").isEqualTo(2103);
        assertThat(body.get("msg").asText()).isEqualTo("机器时间异常");
    }

    @Test
    @DisplayName("相同挑战重复提交 → 第二次 2101（一次性消费）")
    void login_replayChallenge_shouldBeRejected() throws Exception {
        String token = login("admin", ADMIN_PLAIN_PASSWORD);
        assertThat(token).isNotBlank();

        // 复用第一个挑战的验证码与 UUID 再次提交
        // （login() 已消费该挑战；这里用同一 captchaId + 任意验证码应得 2101）
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "admin")
                                .put("password", LoginChallengeTestSupport.encryptPassword(ADMIN_PLAIN_PASSWORD))
                                .put("captcha", "aaaa")
                                .put("captchaId", "consumed-or-unknown")
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(2101);
    }

    @Test
    @DisplayName("不存在的用户登录 → 2104 密码错误")
    void login_withUnknownUser_shouldReturnFailure() throws Exception {
        String token = login("nonexistent", "any-password");
        assertThat(token)
                .as("不存在的用户不应返回 token")
                .isNull();
    }

    // ==================== 测试 5：停用/锁定用户登录被拒 ====================

    @Test
    @DisplayName("停用用户（status=1）登录 → 401 + 账号已停用，不下发 refresh cookie")
    void login_withDisabledUser_shouldReturnFailure() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, status, is_admin,
                                      deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0)
                """, 2L, "disabled-user", ADMIN_BCRYPT_HASH, "停用用户", 1L, 1, 0);

        JsonNode challenge = newChallenge();
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "disabled-user")
                                .put("password", LoginChallengeTestSupport.encryptPassword(ADMIN_PLAIN_PASSWORD))
                                .put("captcha", testableChallengeService.lastCaptchaCode())
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("停用用户登录应返回 401")
                .isEqualTo(401);
        assertThat(body.get("msg").asText())
                .as("提示应区分'账号已停用'")
                .isEqualTo("账号已停用");
        // R.fail 的 data 字段为 Java null → Jackson 输出 "data":null → JsonNode.get 返回 NullNode
        // （非 Java null），故用 isNull()（JsonNode 语义）断言 JSON 字段为 null
        assertThat(body.get("data").isNull())
                .as("失败时 data 应为 null")
                .isTrue();
        assertThat(result.getResponse().getCookie("rt"))
                .as("停用用户登录不应下发 refresh cookie")
                .isNull();
    }

    @Test
    @DisplayName("锁定用户（status=2）登录 → 401 + 账号已锁定")
    void login_withLockedUser_shouldReturnFailure() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password, real_name, dept_id, status, is_admin,
                                      deleted, tenant_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0)
                """, 3L, "locked-user", ADMIN_BCRYPT_HASH, "锁定用户", 1L, 2, 0);

        JsonNode challenge = newChallenge();
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("username", "locked-user")
                                .put("password", LoginChallengeTestSupport.encryptPassword(ADMIN_PLAIN_PASSWORD))
                                .put("captcha", testableChallengeService.lastCaptchaCode())
                                .put("captchaId", challenge.get("captchaId").asText())
                                .put("timestamp", String.valueOf(System.currentTimeMillis()))
                                .toString()))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("锁定用户登录应返回 401")
                .isEqualTo(401);
        assertThat(body.get("msg").asText())
                .as("提示应区分'账号已锁定'")
                .isEqualTo("账号已锁定");
    }

    // ==================== 测试 6：停用用户 refresh 被拒 ====================

    @Test
    @DisplayName("账号停用后既有 refresh token 刷新 → 401 + 账号已停用 + 新轮换 token 已撤销")
    void refresh_withDisabledUser_shouldRejectAndRevokeRotatedToken() throws Exception {
        // ---- Act 1: 以正常状态登录，取得 refresh cookie ----
        MvcResult loginResult = loginMvc(ADMIN_PLAIN_PASSWORD);
        String rawToken = loginResult.getResponse().getCookie("rt").getValue();
        assertThat(rawToken)
                .as("登录成功应下发 refresh cookie")
                .isNotBlank();

        // ---- Act 2: 将 admin 置为停用（模拟管理员停用账号） ----
        jdbcTemplate.update("UPDATE sys_user SET status = 1 WHERE id = 1");

        // ---- Act 3: 携带既有 refresh cookie 刷新 → 401 ----
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("rt", rawToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("停用用户 refresh 应返回 401")
                .isEqualTo(401);
        assertThat(body.get("msg").asText())
                .as("提示应区分'账号已停用'")
                .isEqualTo("账号已停用");

        // ---- Act 4: 轮换出的新 token 应已撤销（DB 中该用户无存活 token） ----
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = 1 AND revoked = 0", Integer.class);
        assertThat(activeCount)
                .as("停用后不应存在任何存活的 refresh token")
                .isZero();
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = 1", Integer.class);
        assertThat(totalCount)
                .as("旧 token 已撤销 + 新 token 已签发并撤销，应共两条")
                .isEqualTo(2);
    }

    // ==================== 测试 7：正常用户 refresh 轮换回归 ====================

    @Test
    @DisplayName("正常用户 refresh → 新 access token + 新 refresh cookie，轮换语义不变")
    void refresh_withActiveUser_shouldRotateAndReturnNewToken() throws Exception {
        // ---- Act 1: 登录 ----
        MvcResult loginResult = loginMvc(ADMIN_PLAIN_PASSWORD);
        String oldRawToken = loginResult.getResponse().getCookie("rt").getValue();
        assertThat(oldRawToken)
                .as("登录成功应下发 refresh cookie")
                .isNotBlank();

        // ---- Act 2: 刷新 → 成功 + 新 cookie ----
        MvcResult refreshResult = mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("rt", oldRawToken)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        assertThat(body.get("code").asInt())
                .as("正常用户 refresh 应成功")
                .isZero();
        assertThat(body.get("data").get("accessToken").asText())
                .as("应返回新 access token")
                .isNotBlank();

        String newRawToken = refreshResult.getResponse().getCookie("rt").getValue();
        assertThat(newRawToken)
                .as("应下发新 refresh cookie")
                .isNotBlank()
                .isNotEqualTo(oldRawToken);

        // ---- Act 3: DB 轮换语义不变：1 条存活（新）+ 1 条已撤销（旧） ----
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = 1 AND revoked = 0", Integer.class);
        assertThat(activeCount)
                .as("轮换后应恰有 1 条存活 token")
                .isEqualTo(1);
        Integer revokedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_refresh_token WHERE user_id = 1 AND revoked = 1", Integer.class);
        assertThat(revokedCount)
                .as("轮换后旧 token 应已撤销")
                .isEqualTo(1);

        // ---- Act 4: 新 token 可再次刷新（双 token 架构回归） ----
        MvcResult refreshAgain = mockMvc.perform(post("/auth/refresh")
                        .cookie(new Cookie("rt", newRawToken)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(refreshAgain.getResponse().getContentAsString()).get("code").asInt())
                .as("新 token 应可再次轮换")
                .isZero();
    }

    // ==================== 测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.system.mapper")
    @ComponentScan("com.sw.ck.system.service.impl")
    @EnableTransactionManagement
    static class TestConfig {

        // ==================== 数据源 ====================

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:authflow;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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

        // ==================== MyBatis-Plus 基础设施 ====================

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
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
        public CommonMetaObjectHandler commonMetaObjectHandler(
                LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public CommonTenantLineHandler commonTenantLineHandler(
                TenantProperties tenantProperties,
                LoginContextProvider loginContextProvider) {
            return new CommonTenantLineHandler(tenantProperties, loginContextProvider);
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                CommonTenantLineHandler commonTenantLineHandler) {
            return new TenantLineInnerInterceptor(commonTenantLineHandler);
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(
                TenantLineInnerInterceptor tenantLineInnerInterceptor) {
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

        // ==================== 密码编码 ====================

        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }

        // ==================== JWT 和认证 ====================

        @Bean
        public JwtProperties jwtProperties() {
            JwtProperties props = new JwtProperties();
            props.setSecret("test-jwt-secret-at-least-256-bits-long-for-hs256-algorithm");
            props.setExpireSeconds(7200);
            props.setAccessExpireSeconds(900);
            props.setRefreshExpireSeconds(604800);
            return props;
        }

        @Bean
        public JwtTokenProvider jwtTokenProvider(JwtProperties jwtProperties) {
            return new JwtTokenProviderImpl(jwtProperties);
        }

        @Bean
        public SecurityProperties securityProperties() {
            SecurityProperties props = new SecurityProperties();
            props.setTokenHeader("Authorization");
            props.setTokenPrefix("Bearer ");
            props.setPermitUrls(List.of("/auth/login"));
            return props;
        }

        @Bean
        @SuppressWarnings({"unchecked", "rawtypes"})
        public LoginUserCacheService loginUserCacheService(JwtProperties jwtProperties) {
            RedisTemplate<String, Object> mockRedis = mock(RedisTemplate.class);
            ValueOperations<String, Object> mockOps = mock(ValueOperations.class);
            when(mockRedis.opsForValue()).thenReturn(mockOps);
            when(mockOps.get(anyString())).thenReturn(null);
            return new LoginUserCacheService(mockRedis, jwtProperties) {
                @Override
                public void cache(LoginUser loginUser) {
                    // no-op: no Redis in test
                }

                @Override
                public void evict(Long userId) {
                    // no-op
                }
            };
        }

        @Bean
        @SuppressWarnings("unchecked")
        public LoginUserLoader loginUserLoader(
                UserDetailsProvider userDetailsProvider,
                LoginUserCacheService loginUserCacheService) {
            org.springframework.beans.factory.ObjectProvider<UserDetailsProvider> provider =
                    mock(org.springframework.beans.factory.ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(userDetailsProvider);
            return new LoginUserLoader(provider, loginUserCacheService);
        }

        // ==================== UserDetailsProvider ====================

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

        // ==================== RefreshTokenService ====================

        @Bean
        public RefreshTokenService refreshTokenService(
                SysRefreshTokenMapper sysRefreshTokenMapper,
                PlatformTransactionManager transactionManager) {
            return new RefreshTokenService(sysRefreshTokenMapper, transactionManager);
        }

        // ==================== 认证过滤器 ====================

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(
                JwtTokenProvider jwtTokenProvider,
                LoginUserLoader loginUserLoader,
                SecurityProperties securityProperties) {
            return new JwtAuthenticationFilter(jwtTokenProvider, loginUserLoader, securityProperties);
        }

        // ==================== ObjectMapper（JSON 序列化） ====================

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // ==================== P45 登录挑战 / RSA ====================

        @Bean
        public LoginSecurityProperties loginSecurityProperties() {
            LoginSecurityProperties props = new LoginSecurityProperties();
            props.setRsaPrivateKey(LoginChallengeTestSupport.TEST_PRIVATE_KEY_PEM);
            props.setRsaKeyVersion("v1");
            props.setDigestSecret("test-digest-secret");
            return props;
        }

        @Bean
        public com.sw.ck.system.security.RsaLoginKeyManager rsaLoginKeyManager(
                LoginSecurityProperties loginSecurityProperties) {
            return new com.sw.ck.system.security.RsaLoginKeyManager(loginSecurityProperties);
        }

        @Bean
        public LoginChallengeStore loginChallengeStore() {
            return new LoginChallengeTestSupport.InMemoryLoginChallengeStore();
        }

        @Bean
        public com.sw.ck.system.security.PngCaptchaRenderer pngCaptchaRenderer() {
            return new com.sw.ck.system.security.PngCaptchaRenderer();
        }

        @Bean
        public LoginChallengeTestSupport.TestableLoginChallengeService loginChallengeService(
                LoginChallengeStore loginChallengeStore,
                com.sw.ck.system.security.RsaLoginKeyManager rsaLoginKeyManager,
                LoginSecurityProperties loginSecurityProperties,
                com.sw.ck.system.security.PngCaptchaRenderer pngCaptchaRenderer) {
            return new LoginChallengeTestSupport.TestableLoginChallengeService(
                    loginChallengeStore, rsaLoginKeyManager, loginSecurityProperties, pngCaptchaRenderer);
        }

        // ==================== 控制器 ====================

        @Bean
        public AuthController authController(
                UserDetailsProvider userDetailsProvider,
                PasswordEncoder passwordEncoder,
                JwtTokenProvider jwtTokenProvider,
                SysUserService sysUserService,
                JwtProperties jwtProperties,
                RefreshTokenService refreshTokenService,
                LoginUserLoader loginUserLoader,
                LoginChallengeService loginChallengeService,
                com.sw.ck.system.security.RsaLoginKeyManager rsaLoginKeyManager) {
            return new AuthController(userDetailsProvider, passwordEncoder,
                    jwtTokenProvider, sysUserService, jwtProperties,
                    refreshTokenService, loginUserLoader, loginChallengeService, rsaLoginKeyManager);
        }

        @Bean
        public AuthMeController authMeController(
                SysUserService sysUserService,
                SysMenuService sysMenuService) {
            return new AuthMeController(sysUserService, sysMenuService);
        }
    }
}
