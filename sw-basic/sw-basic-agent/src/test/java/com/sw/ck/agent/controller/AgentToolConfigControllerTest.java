package com.sw.ck.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentToolExternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolInternalConfigDTO;
import com.sw.ck.agent.service.AgentToolConfigService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
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
import org.springframework.web.context.WebApplicationContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentToolConfigController} 测试（M07 Step3 §10，mock Service，参照
 * {@code AgentModelControllerTest} 风格）。
 * <p>
 * 装配真实 {@link JwtAuthenticationFilter} + {@link SecurityFilterChain} +
 * {@code @EnableMethodSecurity}（来自 TestConfig），请求携带真实 JWT token；Service 为
 * Mockito mock（controller 层行为 + 权限码校验是断言对象）。用户：1=无权限，
 * 2=view+manage，3=superAdmin。
 * </p>
 */
@SpringBootTest(
        classes = AgentToolConfigControllerTest.TestConfig.class,
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
@DisplayName("工具沙箱配置 Controller 测试（权限码约束）")
class AgentToolConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentToolConfigService agentToolConfigService;

    @BeforeEach
    void setUp() {
        LoginUserHolder.clear();
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.generateToken(userId);
    }

    private String internalJson(String name) {
        return """
                {"name":"%s","description":"计算工具","inputSchema":"{\\"type\\":\\"string\\"}","beanName":"calcBean","methodName":"execute","enabled":true}
                """.formatted(name);
    }

    private String externalJson(String name) {
        return """
                {"name":"%s","description":"天气查询","url":"http://127.0.0.1:1/weather","httpMethod":"POST","timeoutSeconds":30,"enabled":true}
                """.formatted(name);
    }

    // ==================== 用例 1：无 view 权限 → 403 ====================

    @Test
    @DisplayName("用例1: 无 agent:tool:view 权限访问 GET /agent/tool/internal → 403")
    void page_withoutViewPermission_shouldReturn403() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/tool/internal")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(body.get("msg").asText()).isNotBlank();
    }

    // ==================== 用例 2：manage 权限可创建（内部工具） ====================

    @Test
    @DisplayName("用例2: 具备 agent:tool:manage 权限 POST /agent/tool/internal → 200 且返回新 id")
    void createInternal_withManagePermission_shouldSucceed() throws Exception {
        when(agentToolConfigService.createInternalTool(any(AgentToolInternalConfigDTO.class))).thenReturn(100L);

        MvcResult result = mockMvc.perform(post("/agent/tool/internal")
                        .header("Authorization", bearerToken(2L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(internalJson("controller-calc")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").asLong()).isEqualTo(100L);
    }

    // ==================== 用例 3：toggle 无 manage → 403；详情有 view → 200 ====================

    @Test
    @DisplayName("用例3: PUT /agent/tool/external/{id}/toggle 无 manage 权限 → 403；GET /agent/tool/external/{id} 有 view 权限 → 200")
    void toggleExternal_withoutManage_shouldReturn403_getWithView_shouldReturn200() throws Exception {
        // 无 manage 权限 → 403
        MvcResult forbidden = mockMvc.perform(put("/agent/tool/external/1/toggle")
                        .header("Authorization", bearerToken(1L))
                        .param("enabled", "true"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode forbiddenBody = objectMapper.readTree(forbidden.getResponse().getContentAsString());
        assertThat(forbiddenBody.get("code").asInt()).isEqualTo(403);

        // 有 view 权限 → 详情 200
        AgentToolExternalConfigDTO dto = new AgentToolExternalConfigDTO();
        dto.setId(7L);
        dto.setName("weather_tool");
        when(agentToolConfigService.getExternalTool(anyLong())).thenReturn(dto);
        mockMvc.perform(get("/agent/tool/external/7")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk());

        // 有 manage 权限 → toggle 200
        doNothing().when(agentToolConfigService).toggleExternalTool(anyLong(), any(boolean.class));
        mockMvc.perform(put("/agent/tool/external/7/toggle")
                        .header("Authorization", bearerToken(2L))
                        .param("enabled", "false"))
                .andExpect(status().isOk());
    }

    // ==================== 用例 4：superAdmin 绕过全部权限 ====================

    @Test
    @DisplayName("用例4: superAdmin 绕过权限校验，可调用查询与写入端点")
    void superAdmin_shouldBypassAllPermissions() throws Exception {
        // GET 分页（内部）
        mockMvc.perform(get("/agent/tool/internal")
                        .header("Authorization", bearerToken(3L)))
                .andExpect(status().isOk());
        // GET 分页（外部）
        mockMvc.perform(get("/agent/tool/external")
                        .header("Authorization", bearerToken(3L)))
                .andExpect(status().isOk());
        // POST 创建（外部）
        when(agentToolConfigService.createExternalTool(any(AgentToolExternalConfigDTO.class))).thenReturn(200L);
        MvcResult createResult = mockMvc.perform(post("/agent/tool/external")
                        .header("Authorization", bearerToken(3L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(externalJson("super-weather")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode createBody = objectMapper.readTree(createResult.getResponse().getContentAsString());
        assertThat(createBody.get("code").asInt()).isZero();
        assertThat(createBody.get("data").asLong()).isEqualTo(200L);
    }

    // ==================== 组合测试配置 ====================

    /** 按 userId 提供可控权限的 UserDetailsProvider 测试桩 */
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
    @EnableMethodSecurity
    @EnableWebSecurity
    static class TestConfig {

        @Bean
        public AgentToolConfigService agentToolConfigService() {
            return mock(AgentToolConfigService.class);
        }

        @Bean
        public AgentToolConfigController agentToolConfigController(
                AgentToolConfigService agentToolConfigService) {
            return new AgentToolConfigController(agentToolConfigService);
        }

        // ==================== JSON ====================
        // 不手动定义 ObjectMapper：@EnableAutoConfiguration 的 JacksonAutoConfiguration
        // 会提供（自动注册 JavaTimeModule，LocalDateTime 序列化所需）

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

        /** 三个测试用户：1=无权限，2=view+manage，3=superAdmin */
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
            userB.setPermissions(List.of("agent:tool:view", "agent:tool:manage"));
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
