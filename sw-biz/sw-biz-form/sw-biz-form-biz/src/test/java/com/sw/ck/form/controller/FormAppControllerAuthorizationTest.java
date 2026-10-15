package com.sw.ck.form.controller;

import com.sw.ck.form.service.FormAppService;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 应用中心边界鉴权证据（走真实 Spring Method Security）。
 * <p>
 * 覆盖：
 * <ul>
 *   <li>应用管理（form:app:manage）：无权限 403，有权限进入业务层；</li>
 *   <li>应用中心（用户端）：登录即可（无权限注解），未认证 401；</li>
 *   <li>用户端可见性不授予后台管理能力（无 form:app:manage 访问管理端点被拒）。</li>
 * </ul>
 */
@SpringJUnitConfig(FormAppControllerAuthorizationTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("应用中心边界鉴权")
class FormAppControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.permissions = List.of();
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== 管理端：form:app:manage ====================

    @Test
    @DisplayName("无 form:app:manage → 应用列表/新建/归属调整 403")
    void manageEndpoints_withoutPermission_forbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design");
        mockMvc.perform(get("/form/app/manage/list").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/form/app/manage").header("X-Test-User", "limited")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/form/app/manage/forms/f-1/membership").header("X-Test-User", "limited")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appId\":\"a-1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("有 form:app:manage → 应用列表/内容/目录/归属调整过网关（进入业务层）")
    void manageEndpoints_withPermission_passesGateway() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:app:manage");
        mockMvc.perform(get("/form/app/manage/list").header("X-Test-User", "admin"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/form/app/manage/a-1/content").header("X-Test-User", "admin"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/form/app/manage/a-1/dirs").header("X-Test-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"目录\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/form/app/manage/forms/f-1/membership").header("X-Test-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appId\":\"a-1\"}"))
                .andExpect(status().isOk());
    }

    // ==================== 用户端：登录即可 ====================

    @Test
    @DisplayName("用户端应用中心：登录即可访问（无需后台管理权限）")
    void centerEndpoints_loginOnly() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:data:query");
        mockMvc.perform(get("/form/app/center/apps").header("X-Test-User", "member"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/form/app/center/apps/a-1").header("X-Test-User", "member"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未认证 → 用户端与管理端均 401")
    void unauthenticated_unauthorized() throws Exception {
        mockMvc.perform(get("/form/app/center/apps")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/form/app/manage/list")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("仅用户端使用权限的普通用户访问管理端点 → 403（可见性不授予管理能力）")
    void centerOnlyUser_cannotManage() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:data:query", "form:data:submit");
        mockMvc.perform(get("/form/app/manage/list").header("X-Test-User", "member"))
                .andExpect(status().isForbidden());
    }

    // ==================== 测试装配 ====================

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        FormAppManageController formAppManageController() {
            return new FormAppManageController(mock(FormAppService.class));
        }

        @Bean
        FormAppCenterController formAppCenterController() {
            return new FormAppCenterController(mock(FormAppService.class));
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
                user.setTenantId(0L);
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
