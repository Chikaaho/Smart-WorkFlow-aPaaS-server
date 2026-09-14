package com.sw.ck.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentConversationDTO;
import com.sw.ck.agent.dto.AgentConversationMessageDTO;
import com.sw.ck.agent.service.AgentConversationService;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AgentConversationController} 测试（M07 Step4 §10，mock Service，参照
 * {@code AgentToolConfigControllerTest} 风格：真实 JWT 认证链 + 权限码断言）。
 * <p>
 * 用户：1=无权限，2=有 agent:model:view，3=superAdmin。
 * </p>
 */
@SpringBootTest(
        classes = AgentConversationControllerTest.TestConfig.class,
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
@DisplayName("会话查询 Controller 测试（权限码约束）")
class AgentConversationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentConversationService agentConversationService;

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

    private AgentConversationDTO conversationDto(Long id) {
        AgentConversationDTO dto = new AgentConversationDTO();
        dto.setId(id);
        dto.setAgentModelConfigId(10L);
        dto.setStatus("ACTIVE");
        dto.setCreateTime(LocalDateTime.of(2026, 8, 9, 10, 0));
        return dto;
    }

    private AgentConversationMessageDTO messageDto(int order, String role, String content) {
        AgentConversationMessageDTO dto = new AgentConversationMessageDTO();
        dto.setId(100L + order);
        dto.setRole(role);
        dto.setContent(content);
        dto.setMsgOrder(order);
        dto.setCreateTime(LocalDateTime.of(2026, 8, 9, 10, 0));
        return dto;
    }

    // ==================== 用例 1：无 view 权限 → 403 ====================

    @Test
    @DisplayName("用例1: 无 agent:model:view 权限访问 GET /agent/conversations → 403")
    void list_withoutViewPermission_shouldReturn403() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/conversations")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
        assertThat(body.get("msg").asText()).isNotBlank();
    }

    // ==================== 用例 2：list 200 + 结构 ====================

    @Test
    @DisplayName("用例2: 具备 view 权限 GET /agent/conversations?agentModelConfigId= → 200，列表含 id/status/createTime")
    void list_withViewPermission_shouldReturn200() throws Exception {
        when(agentConversationService.listConversations(anyLong()))
                .thenReturn(List.of(conversationDto(1L), conversationDto(2L)));

        MvcResult result = mockMvc.perform(get("/agent/conversations")
                        .header("Authorization", bearerToken(2L))
                        .param("agentModelConfigId", "10"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        JsonNode data = body.get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("id").asLong()).isEqualTo(1L);
        assertThat(data.get(0).get("agentModelConfigId").asLong()).isEqualTo(10L);
        assertThat(data.get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get(0).get("createTime")).isNotNull();
    }

    // ==================== 用例 3：messages 200 + msg_order 升序 ====================

    @Test
    @DisplayName("用例3: 具备 view 权限 GET /agent/conversations/{id}/messages → 200，消息按 msg_order 升序")
    void messages_withViewPermission_shouldReturn200InOrder() throws Exception {
        when(agentConversationService.listMessages(anyLong()))
                .thenReturn(List.of(
                        messageDto(0, "USER", "第一轮输入"),
                        messageDto(1, "ASSISTANT", "第一轮回复"),
                        messageDto(2, "USER", "第二轮输入")));

        MvcResult result = mockMvc.perform(get("/agent/conversations/9/messages")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isZero();
        JsonNode data = body.get("data");
        assertThat(data).hasSize(3);
        assertThat(data.get(0).get("msgOrder").asInt()).isZero();
        assertThat(data.get(1).get("msgOrder").asInt()).isEqualTo(1);
        assertThat(data.get(2).get("msgOrder").asInt()).isEqualTo(2);
        assertThat(data.get(0).get("role").asText()).isEqualTo("USER");
        assertThat(data.get(0).get("content").asText()).isEqualTo("第一轮输入");
    }

    // ==================== 用例 4：superAdmin 绕过 ====================

    @Test
    @DisplayName("用例4: superAdmin 绕过权限校验，两个只读端点均 200")
    void superAdmin_shouldBypassAllPermissions() throws Exception {
        when(agentConversationService.listConversations(null)).thenReturn(List.of());
        when(agentConversationService.listMessages(anyLong())).thenReturn(List.of());

        mockMvc.perform(get("/agent/conversations")
                        .header("Authorization", bearerToken(3L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/agent/conversations/1/messages")
                        .header("Authorization", bearerToken(3L)))
                .andExpect(status().isOk());
    }

    // ==================== 用例 5-7：消息端点权限 + 未认证/撤权（M07-F04-02 D164 补证） ====================

    @Test
    @DisplayName("用例5: 无 agent:model:view 权限访问 GET /agent/conversations/{id}/messages → 403")
    void messages_withoutViewPermission_shouldReturn403() throws Exception {
        MvcResult result = mockMvc.perform(get("/agent/conversations/9/messages")
                        .header("Authorization", bearerToken(1L)))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("用例6: 无 token 访问会话端点 → 401（两个端点均未认证拒绝）")
    void listAndMessages_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/agent/conversations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/agent/conversations/9/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("用例7: 消息端点携带 token 但会话不存在/跨租户 → 404（Service 层语义透传）")
    void messages_notFoundSession_shouldReturn404() throws Exception {
        // doThrow 不覆盖既有 thenReturn stub（与 superAdmin 测试共享 mock）
        org.mockito.Mockito.doThrow(new com.sw.ck.common.exception.BaseException(
                        com.sw.ck.common.exception.CommonErrorCode.NOT_FOUND, "会话不存在"))
                .when(agentConversationService).listMessages(999L);

        MvcResult result = mockMvc.perform(get("/agent/conversations/999/messages")
                        .header("Authorization", bearerToken(2L)))
                .andExpect(status().isOk())  // 全局惯例：业务异常 HTTP 200 + body.code
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(404);
        assertThat(body.get("msg").asText()).contains("会话不存在");
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
        public AgentConversationService agentConversationService() {
            return mock(AgentConversationService.class);
        }

        @Bean
        public AgentConversationController agentConversationController(
                AgentConversationService agentConversationService) {
            return new AgentConversationController(agentConversationService);
        }

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

        /** 三个测试用户：1=无权限，2=view，3=superAdmin */
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
            userB.setPermissions(List.of("agent:model:view"));
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

        // ==================== 业务异常处理器（对齐真实全局惯例） ====================

        @RestControllerAdvice
        static class TestBusinessExceptionAdvice {

            @ExceptionHandler(com.sw.ck.common.exception.BaseException.class)
            public com.sw.ck.common.response.R<Void> handleBaseException(
                    com.sw.ck.common.exception.BaseException ex) {
                return com.sw.ck.common.response.R.fail(ex.getCode(), ex.getMessage());
            }
        }

        @Bean
        public TestBusinessExceptionAdvice testBusinessExceptionAdvice() {
            return new TestBusinessExceptionAdvice();
        }
    }
}
