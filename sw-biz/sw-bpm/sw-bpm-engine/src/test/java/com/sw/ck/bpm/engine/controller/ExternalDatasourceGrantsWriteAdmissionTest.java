package com.sw.ck.bpm.engine.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.bpm.engine.service.ExternalDatasourceGrantService;
import com.sw.ck.bpm.engine.service.impl.ExternalDatasourceServiceImpl;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
import com.sw.ck.bpm.engine.executor.SqlExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.config.BeanIds;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * BE020-G1 余项：授权替换写请求的真实拒绝（真实 HTTP + 真实安全过滤链 +
 * 真实方法级鉴权代理 + 真实租户过滤 + 真实授权持久化）。
 * <p>
 * PUT /workflow/external-datasource/{id}/grants 的两个负向：
 * 本租户无 workflow:datasource:manage 的主体被 @PreAuthorize 实际拒绝（403）；
 * 持有本租户管理权限的另一租户主体按对象租户过滤被拒（目标不可见）。
 * 两次拒绝后两租户授权集合持久回读零变化。管理员正向替换基线沿既有
 * ExternalDatasourceControllerTest/授权端口测试承接，不在本批新建。
 * </p>
 */
@org.springframework.test.context.junit.jupiter.SpringJUnitConfig(
        ExternalDatasourceGrantsWriteAdmissionTest.TestConfig.class)
@org.springframework.test.context.web.WebAppConfiguration
@DisplayName("BE020-G1 余项：grants 替换写请求真实拒绝与双租户零变更")
class ExternalDatasourceGrantsWriteAdmissionTest {

    private static final long TENANT_1 = 1L;
    private static final long TENANT_2 = 2L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    @MapperScan("com.sw.ck.bpm.engine.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url("jdbc:h2:mem:g1grantswrite;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override public Long getUserId() {
                    LoginUser u = LoginUserHolder.get();
                    return u == null ? null : u.getUserId();
                }
                @Override public Long getTenantId() {
                    LoginUser u = LoginUserHolder.get();
                    return u == null ? null : u.getTenantId();
                }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, LoginContextProvider loginContextProvider) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.bpm.engine.entity");
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
            globalConfig.setMetaObjectHandler(new CommonMetaObjectHandler(loginContextProvider));
            factory.setGlobalConfig(globalConfig);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(new TenantProperties(), loginContextProvider)));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public AesGcmCipher aesGcmCipher() {
            return new AesGcmCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        }

        @Bean
        public ExternalDatasourceServiceImpl externalDatasourceService(AesGcmCipher cipher) {
            return new ExternalDatasourceServiceImpl(cipher);
        }

        @Bean
        public ExternalDatasourceGrantService externalDatasourceGrantService(
                com.sw.ck.bpm.engine.mapper.ExternalDatasourceGrantMapper grantMapper,
                ExternalDatasourceServiceImpl datasourceService,
                PermissionService permissionService) {
            return new ExternalDatasourceGrantService(grantMapper, datasourceService.getBaseMapper(),
                    permissionService);
        }

        @Bean
        public ExternalDatasourceController externalDatasourceController(
                ExternalDatasourceServiceImpl datasourceService,
                ExternalDatasourceGrantService grantService) {
            return new ExternalDatasourceController(datasourceService, mock(SqlExecutor.class),
                    grantService);
        }

        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        TestAuthenticationFilter testAuthenticationFilter() {
            return new TestAuthenticationFilter();
        }

        @Bean(name = BeanIds.SPRING_SECURITY_FILTER_CHAIN)
        public Filter springSecurityFilterChain(HttpSecurity http, TestAuthenticationFilter filter)
                throws Exception {
            return new FilterChainProxy(http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(
                            SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((request, response, exception) ->
                                    response.setStatus(401))
                            .accessDeniedHandler((request, response, exception) ->
                                    response.setStatus(403)))
                    .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                    .build());
        }

        @Bean
        MockMvc mockMvc(WebApplicationContext context,
                        @org.springframework.beans.factory.annotation.Qualifier(
                                BeanIds.SPRING_SECURITY_FILTER_CHAIN) Filter chain) {
            return MockMvcBuilders.webAppContextSetup(context).addFilters(chain).build();
        }
    }

    /** 测试认证过滤器：按用例注入主体（身份/租户/角色/权限），经真实安全链与 @PreAuthorize。 */
    static class TestAuthenticationFilter extends OncePerRequestFilter {
        static volatile long userId = 0L;
        static volatile long tenantId = 0L;
        static volatile List<String> roles = List.of();
        static volatile List<String> permissions = List.of();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        jakarta.servlet.FilterChain filterChain)
                throws ServletException, IOException {
            LoginUser user = new LoginUser();
            user.setUserId(userId);
            user.setTenantId(tenantId);
            user.setRoles(roles);
            user.setPermissions(permissions);
            LoginUserHolder.set(user);
            SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.
                            UsernamePasswordAuthenticationToken(user, null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            try {
                filterChain.doFilter(request, response);
            } finally {
                LoginUserHolder.clear();
                SecurityContextHolder.clearContext();
            }
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_bpm_ext_datasource (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by BIGINT,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    name VARCHAR(100), type VARCHAR(32), jdbc_url VARCHAR(500),
                    driver_class VARCHAR(128), username VARCHAR(100),
                    password_cipher VARCHAR(500), read_only SMALLINT DEFAULT 1,
                    enabled SMALLINT DEFAULT 1)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_bpm_ext_datasource_grant (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    create_by BIGINT,
                    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_by BIGINT,
                    deleted SMALLINT NOT NULL DEFAULT 0,
                    version BIGINT NOT NULL DEFAULT 0,
                    datasource_id BIGINT NOT NULL,
                    subject_type VARCHAR(16) NOT NULL,
                    subject_key VARCHAR(64) NOT NULL)
                """);
        jdbcTemplate.execute("DELETE FROM sw_bpm_ext_datasource_grant");
        jdbcTemplate.execute("DELETE FROM sw_bpm_ext_datasource");
        jdbcTemplate.update("INSERT INTO sw_bpm_ext_datasource (id, tenant_id, name, type, "
                + "enabled, deleted, version) VALUES (9, ?, '租户一报表库', 'h2', 1, 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_bpm_ext_datasource (id, tenant_id, name, type, "
                + "enabled, deleted, version) VALUES (20, ?, '租户二报表库', 'h2', 1, 0, 0)", TENANT_2);
        jdbcTemplate.update("INSERT INTO sw_bpm_ext_datasource_grant (id, tenant_id, datasource_id, "
                + "subject_type, subject_key, deleted, version) "
                + "VALUES (101, ?, 9, 'USER', '77', 0, 0)", TENANT_1);
        jdbcTemplate.update("INSERT INTO sw_bpm_ext_datasource_grant (id, tenant_id, datasource_id, "
                + "subject_type, subject_key, deleted, version) "
                + "VALUES (201, ?, 20, 'USER', '88', 0, 0)", TENANT_2);
    }

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.userId = 0L;
        TestAuthenticationFilter.tenantId = 0L;
        TestAuthenticationFilter.roles = List.of();
        TestAuthenticationFilter.permissions = List.of();
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private List<String> grantRows(long tenantId, long datasourceId) {
        return jdbcTemplate.queryForList(
                "SELECT subject_type || ':' || subject_key FROM sw_bpm_ext_datasource_grant "
                        + "WHERE tenant_id = ? AND datasource_id = ? AND deleted = 0 "
                        + "ORDER BY subject_type, subject_key",
                String.class, tenantId, datasourceId);
    }

    @Test
    @DisplayName("本租户已认证但无 workflow:datasource:manage → grants 替换 403，授权集合零变更")
    void sameTenantWithoutManagePermissionRejectedWithZeroChange() throws Exception {
        TestAuthenticationFilter.userId = 78L;
        TestAuthenticationFilter.tenantId = TENANT_1;
        TestAuthenticationFilter.roles = List.of();
        TestAuthenticationFilter.permissions = List.of("form:design:save");

        var result = mockMvc.perform(put("/workflow/external-datasource/9/grants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grants\":[{\"subjectType\":\"USER\",\"subjectKey\":\"78\"}]}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("无管理权限主体被真实方法级鉴权拒绝").isEqualTo(403);

        assertThat(grantRows(TENANT_1, 9)).as("目标数据源授权集合零变更")
                .containsExactly("USER:77");
        assertThat(grantRows(TENANT_2, 20)).as("其他租户授权集合零变更")
                .containsExactly("USER:88");
    }

    @Test
    @DisplayName("另一租户持有管理权限的主体 → 目标按租户隔离不可见拒绝，双租户授权集合零变更")
    void crossTenantManagerRejectedByIsolationWithZeroChange() throws Exception {
        TestAuthenticationFilter.userId = 88L;
        TestAuthenticationFilter.tenantId = TENANT_2;
        TestAuthenticationFilter.roles = List.of();
        TestAuthenticationFilter.permissions = List.of("workflow:datasource:manage");

        try {
            var result = mockMvc.perform(put("/workflow/external-datasource/9/grants")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"grants\":[{\"subjectType\":\"USER\",\"subjectKey\":\"88\"}]}"))
                    .andReturn();
            // 若以受控包络返回，记录实际业务码（本断言仅在未抛出时校验非 2xx 语义）
            assertThat(result.getResponse().getStatus())
                    .as("跨租户对象不可见，不得返回成功").isNotEqualTo(200);
        } catch (ServletException expected) {
            // 服务端 requireExisting 按租户过滤后对象不可见 → 业务拒绝
            assertThat(String.valueOf(expected.getCause()))
                    .contains("外部数据源不存在");
        }

        assertThat(grantRows(TENANT_1, 9)).as("目标租户授权集合零变更")
                .containsExactly("USER:77");
        assertThat(grantRows(TENANT_2, 20)).as("操作者本租户授权集合零变更")
                .containsExactly("USER:88");
    }
}
