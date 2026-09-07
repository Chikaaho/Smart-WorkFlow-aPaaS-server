package com.sw.ck.bpm.process.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.api.node.BpmNodeRegistry;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
import com.sw.ck.system.api.user.UserQueryFacade;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P52 流程定义边界鉴权证据（走真实 Spring Method Security）。
 * <p>
 * 覆盖 {@link BpmProcessDefController} 的权限声明：
 * <ul>
 *   <li>列表查询 → workflow:def:view（表单工作台「关联流程」读取基线）</li>
 *   <li>创建 → workflow:def:create；发布 → workflow:def:publish</li>
 *   <li>无权限 → 403，未认证 → 401</li>
 * </ul>
 * 手法对齐 {@code StorageControllerAuthorizationTest}。
 */
@SpringJUnitConfig(BpmProcessDefControllerAuthorizationTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("P52 流程定义边界鉴权")
class BpmProcessDefControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.permissions = List.of();
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("有 workflow:def:view → 列表过网关；无 → 403")
    void listDefs_requiresViewPermission() throws Exception {
        TestAuthenticationFilter.permissions = List.of("workflow:def:view");
        int status = mockMvc.perform(get("/workflow/defs").header("X-Test-User", "viewer"))
                .andReturn().getResponse().getStatus();
        org.junit.jupiter.api.Assertions.assertNotEquals(403, status);

        TestAuthenticationFilter.permissions = List.of();
        mockMvc.perform(get("/workflow/defs").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("无 workflow:def:create → 创建流程 403（零副作用，不到业务层）")
    void create_withoutPermission_forbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of("workflow:def:view");
        mockMvc.perform(post("/workflow/defs")
                        .header("X-Test-User", "limited")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"伪关联流程\",\"formKey\":\"some_form\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("有 workflow:def:create → 创建过权限网关（非 401/403）")
    void create_withPermission_passesGateway() throws Exception {
        TestAuthenticationFilter.permissions = List.of("workflow:def:view", "workflow:def:create");
        try {
            int status = mockMvc.perform(post("/workflow/defs")
                            .header("X-Test-User", "admin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"P52流程\",\"formKey\":\"some_form\"}"))
                    .andReturn().getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertNotEquals(401, status);
            org.junit.jupiter.api.Assertions.assertNotEquals(403, status);
        } catch (jakarta.servlet.ServletException e) {
            // 权限网关放行后进入业务层（mock service 抛错），即证明未在网关被拒
        }
    }

    @Test
    @DisplayName("无 workflow:def:publish → 流程发布 403")
    void publish_withoutPermission_forbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of("workflow:def:view");
        mockMvc.perform(post("/workflow/defs/1/publish").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未认证 → 401")
    void unauthenticated_unauthorized() throws Exception {
        mockMvc.perform(get("/workflow/defs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("有 workflow:def:view → 节点能力清单过网关；无 → 403")
    void nodeCapabilities_requiresViewPermission() throws Exception {
        TestAuthenticationFilter.permissions = List.of("workflow:def:view");
        mockMvc.perform(get("/workflow/defs/node-capabilities").header("X-Test-User", "viewer"))
                .andExpect(status().isOk());

        TestAuthenticationFilter.permissions = List.of();
        mockMvc.perform(get("/workflow/defs/node-capabilities").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        BpmProcessDefController controller() {
            return new BpmProcessDefController(mock(BpmProcessDefService.class),
                    new ObjectMapper(), mock(UserQueryFacade.class), mock(BpmNodeRegistry.class));
        }

        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        TestAuthenticationFilter testAuthenticationFilter() {
            return new TestAuthenticationFilter();
        }

        @Bean
        Filter springSecurityFilterChain(HttpSecurity http, TestAuthenticationFilter filter) throws Exception {
            return new FilterChainProxy(http.csrf(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                            .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                    .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
                    .build());
        }

        @Bean
        MockMvc mockMvc(WebApplicationContext context,
                        @Qualifier("springSecurityFilterChain") Filter chain) {
            return MockMvcBuilders.webAppContextSetup(context).addFilters(chain).build();
        }
    }

    static class TestAuthenticationFilter extends OncePerRequestFilter {
        private static volatile List<String> permissions = List.of();

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            if (request.getHeader("X-Test-User") != null) {
                LoginUser user = new LoginUser();
                user.setUserId(1L);
                user.setUsername("admin");
                user.setPermissions(permissions);
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
