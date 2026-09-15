package com.sw.ck.agent.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.mapper.AgentModelConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentModelConfigService;
import com.sw.ck.agent.service.AgentOrchestrationService;
import com.sw.ck.agent.service.impl.AgentModelConfigServiceImpl;
import com.sw.ck.agent.service.impl.AgentOrchestrationServiceImpl;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.filter.JwtAuthenticationFilter;
import com.sw.ck.security.handler.RestAccessDeniedHandler;
import com.sw.ck.security.handler.RestAuthenticationEntryPoint;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.jwt.JwtTokenProviderImpl;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.security.support.PermissionService;
import jakarta.servlet.Filter;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentOrchestrationController} 测试（M07 Step2，权限校验）。
 * <p>
 * 策略与 Step1 的 {@code AgentModelControllerTest} 同款：{@code @SpringBootTest}（MOCK
 * 环境）+ 手动 {@code MockMvc}（webAppContextSetup），装配真实 {@link JwtAuthenticationFilter}
 * + {@link SecurityFilterChain} + {@code @EnableMethodSecurity}，请求携带真实 JWT token，
 * 用户权限组合可控（1=无权限，2=model:manage+orchestration:run，3=superAdmin）。
 * </p>
 * <p>
 * 执行端点用例：模型 baseUrl 指向未监听端口（127.0.0.1:1），run 返回 {@code success=false}
 * 但 HTTP 200（Service 层兜底，不抛 500），与 Step1 的 test-connection 用例同构。
 * </p>
 */
@SpringBootTest(
        classes = AgentOrchestrationControllerTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration,"
                        + "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
                "spring.ai.openai.api-key=test-dummy"
        }
)
@DisplayName("编排执行 Controller 测试（权限校验）")
class AgentOrchestrationControllerTest {

    private static final String TEST_BASE64_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 建表（V19 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_model_config (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    protocol_type   VARCHAR(32) NOT NULL,
                    base_url        VARCHAR(500) NOT NULL,
                    model_name      VARCHAR(100) NOT NULL,
                    api_key_cipher  CLOB,
                    temperature     DECIMAL(4,2),
                    max_tokens      INT,
                    top_p           DECIMAL(4,2),
                    timeout_seconds INT NOT NULL DEFAULT 30,
                    retry_count     INT NOT NULL DEFAULT 0,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    remark          VARCHAR(500),
                    create_time     TIMESTAMP,
                    create_by       VARCHAR(64),
                    update_time     TIMESTAMP,
                    update_by       VARCHAR(64),
                    deleted         SMALLINT NOT NULL DEFAULT 0,
                    tenant_id       BIGINT NOT NULL DEFAULT 0,
                    version         BIGINT NOT NULL DEFAULT 0,
                    group_key       VARCHAR(100),
                    sort            INT NOT NULL DEFAULT 0,
                    locked_until    TIMESTAMP,
                    quota_cooldown_seconds INT NOT NULL DEFAULT 60
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_model_name ON sw_agent_model_config (tenant_id, name)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_tenant_deleted ON sw_agent_model_config (tenant_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_group ON sw_agent_model_config (tenant_id, group_key, sort)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_model_config");
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.generateToken(userId);
    }

    private String createModelJson(String name) {
        return """
                {"name":"%s","protocolType":"openai","baseUrl":"http://127.0.0.1:1","modelName":"gpt-4o","apiKey":"sk-test-123456","timeoutSeconds":30,"retryCount":0,"enabled":true}
                """.formatted(name);
    }

    private Long createModelAs(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/models")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createModelJson(name)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        return body.get("data").asLong();
    }

    private JsonNode runOrchestrationAs(String token, Long modelId, String input) throws Exception {
        String bodyJson = """
                {"agentModelConfigId":%d,"input":"%s"}
                """.formatted(modelId, input);
        MvcResult result = mockMvc.perform(post("/agent/orchestration/run")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ==================== 用例 1：无权限 → 403 ====================

    @Test
    @DisplayName("用例1: 无 agent:orchestration:run 权限调 POST /agent/orchestration/run → 403")
    void run_withoutPermission_shouldReturn403() throws Exception {
        String bodyJson = """
                {"agentModelConfigId":1,"input":"hello"}
                """;
        MvcResult result = mockMvc.perform(post("/agent/orchestration/run")
                        .header("Authorization", bearerToken(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(body.get("msg").asText()).isNotBlank();
    }

    // ==================== 用例 2：具备权限 → 执行成功返回（模型不可达 → success=false 但 HTTP 200） ====================

    @Test
    @DisplayName("用例2: 具备 agent:orchestration:run 权限 → HTTP 200 + code=0；模型不可达时 data.success=false 且 errorMessage 不含明文 Key")
    void run_withPermission_shouldReturnR200() throws Exception {
        Long id = createModelAs(bearerToken(2L), "run-perm-model");

        JsonNode body = runOrchestrationAs(bearerToken(2L), id, "hello");

        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("success").asBoolean()).isFalse();
        assertThat(body.get("data").get("errorMessage").asText()).isNotBlank();
        assertThat(body.get("data").get("errorMessage").asText())
                .as("errorMessage 不得包含明文 API Key")
                .doesNotContain("sk-test-123456");
    }

    // ==================== 用例 3：superAdmin 绕过权限 ====================

    @Test
    @DisplayName("用例3: superAdmin 绕过权限校验，可调用编排执行端点")
    void superAdmin_shouldBypassPermissions() throws Exception {
        Long id = createModelAs(bearerToken(3L), "super-admin-orch-model");

        JsonNode body = runOrchestrationAs(bearerToken(3L), id, "hello");

        // 权限放行 + 不抛异常：模型不可达 → success=false 但 HTTP 200 + code=0
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("success").asBoolean()).isFalse();
        assertThat(body.get("data").get("errorMessage").asText()).isNotBlank();
        assertThat(body.get("data").get("latencyMs").asLong()).isGreaterThanOrEqualTo(0);
    }

    // ==================== 组合测试配置 ====================

    /** 按 userId 提供可控权限的 UserDetailsProvider 测试桩（Step1 同款） */
    static class StubUserDetailsProvider implements UserDetailsProvider {

        private final Map<Long, LoginUser> users;

        StubUserDetailsProvider(Map<Long, LoginUser> users) {
            this.users = users;
        }

        @Override
        public LoginUser loadByUsername(String username) {
            return null;
        }

        @Override
        public LoginUser loadByUserId(Long userId) {
            return users.get(userId);
        }
    }

    @Configuration
    @EnableAutoConfiguration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        // ==================== 数据源 + MyBatis-Plus ====================

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentorchtl;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(new com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler(
                    new com.sw.ck.common.config.mybatis.tenant.TenantProperties(), loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.agent.entity");
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

        // ==================== 业务 Bean（Step1 + Step2） ====================

        @Bean
        public AesGcmCipher agentAesGcmCipher() {
            return new AesGcmCipher(TEST_BASE64_KEY);
        }

        @Bean
        public AgentModelConfigService agentModelConfigService(AesGcmCipher agentAesGcmCipher,
                                                               com.sw.ck.common.security.LoginContextProvider loginContextProvider,
                                                               com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            return new AgentModelConfigServiceImpl(agentAesGcmCipher, loginContextProvider, deptScopeProvider);
        }

        @Bean
        public com.sw.ck.common.datascope.DeptScopeProvider testDeptScopeProvider() {
            // 测试用映射实现：无子部门（本测试不覆盖 DEPT_AND_CHILD 展开）
            return deptId -> java.util.List.of();
        }

        @Bean
        public AgentModelController agentModelController(AgentModelConfigService agentModelConfigService) {
            return new AgentModelController(agentModelConfigService);
        }

        @Bean
        public ChatModelFactory chatModelFactory() {
            return new ChatModelFactory();
        }

        @Bean
        public CompiledGraph<AgentState> agentCompiledGraph() throws GraphStateException {
            return new AgentGraphFactory().buildGraph();
        }

        @Bean
        public AgentOrchestrationService agentOrchestrationService(
                AgentModelConfigMapper agentModelConfigMapper,
                AesGcmCipher agentAesGcmCipher,
                ChatModelFactory chatModelFactory,
                CompiledGraph<AgentState> agentCompiledGraph) {
            return new AgentOrchestrationServiceImpl(
                    agentModelConfigMapper, agentAesGcmCipher, chatModelFactory, agentCompiledGraph);
        }

        @Bean
        public AgentOrchestrationController agentOrchestrationController(
                AgentOrchestrationService agentOrchestrationService) {
            return new AgentOrchestrationController(agentOrchestrationService);
        }

        // ==================== JSON ====================
        // 不手动定义 ObjectMapper：@EnableAutoConfiguration 的 JacksonAutoConfiguration
        // 会提供（自动注册 JavaTimeModule）；手动裸 ObjectMapper 会退让自动配置并导致
        // java.time 类型序列化失败（Step1 实测结论）

        // ==================== JWT / 认证 ====================

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

        /** 三个测试用户：1=无权限，2=model:manage + orchestration:run，3=superAdmin */
        @Bean
        public UserDetailsProvider userDetailsProvider() {
            Map<Long, LoginUser> users = new HashMap<>();

            LoginUser userA = new LoginUser();
            userA.setUserId(1L);
            userA.setTenantId(100L);
            userA.setUsername("user_a");
            userA.setPermissions(List.of());
            userA.setSuperAdmin(false);
            users.put(1L, userA);

            LoginUser userB = new LoginUser();
            userB.setUserId(2L);
            userB.setTenantId(100L);
            userB.setUsername("user_b");
            userB.setPermissions(List.of("agent:model:manage", "agent:orchestration:run"));
            userB.setSuperAdmin(false);
            users.put(2L, userB);

            LoginUser userC = new LoginUser();
            userC.setUserId(3L);
            userC.setTenantId(100L);
            userC.setUsername("super_admin");
            userC.setPermissions(List.of());
            userC.setSuperAdmin(true);
            users.put(3L, userC);

            return new StubUserDetailsProvider(users);
        }

        // ==================== 安全链（对齐 WebSecurityAutoConfiguration 装配） ====================

        @Bean("ss")
        public PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
            return new RestAuthenticationEntryPoint(objectMapper);
        }

        @Bean
        public RestAccessDeniedHandler restAccessDeniedHandler(ObjectMapper objectMapper) {
            return new RestAccessDeniedHandler(objectMapper);
        }

        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(
                JwtTokenProvider jwtTokenProvider,
                LoginUserLoader loginUserLoader,
                SecurityProperties securityProperties) {
            return new JwtAuthenticationFilter(jwtTokenProvider, loginUserLoader, securityProperties,
            org.mockito.Mockito.mock(com.sw.ck.security.cache.LoginUserCacheService.class));
        }

        @Bean
        public UserDetailsService noopUserDetailsService() {
            return username -> {
                throw new UsernameNotFoundException("本系统认证不经过 UserDetailsService：" + username);
            };
        }

        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                       JwtAuthenticationFilter jwtAuthenticationFilter,
                                                       RestAuthenticationEntryPoint authenticationEntryPoint,
                                                       RestAccessDeniedHandler accessDeniedHandler) throws Exception {
            http
                    .csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(authenticationEntryPoint)
                            .accessDeniedHandler(accessDeniedHandler))
                    .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        // ==================== MockMvc（webAppContextSetup + 真实安全链） ====================

        @Bean
        public MockMvc mockMvc(WebApplicationContext context,
                               @Qualifier("springSecurityFilterChain") Filter springSecurityFilterChain) {
            return MockMvcBuilders.webAppContextSetup(context)
                    .addFilters(springSecurityFilterChain)
                    .build();
        }
    }
}
