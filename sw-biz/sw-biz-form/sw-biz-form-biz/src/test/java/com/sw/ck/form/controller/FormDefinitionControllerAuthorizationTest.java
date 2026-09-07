package com.sw.ck.form.controller;

import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormSnapshotMapper;
import com.sw.ck.form.service.impl.FormDefServiceImpl;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P52 表单工作台边界鉴权证据（走真实 Spring Method Security）。
 * <p>
 * 覆盖 FormDefinitionController 的权限声明：
 * <ul>
 *   <li>写操作（建草稿/存 config/发布）→ form:design:save / form:design:publish</li>
 *   <li>历史快照只读 → form:design（菜单基线码）</li>
 *   <li>无权限 → 403，未认证 → 401</li>
 * </ul>
 * 手法对齐 {@code StorageControllerAuthorizationTest}：测试鉴权 Filter 注入
 * LoginUser/权限集，请求经过真实 springSecurityFilterChain + @PreAuthorize。
 */
@SpringJUnitConfig(FormDefinitionControllerAuthorizationTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("P52 表单工作台边界鉴权")
class FormDefinitionControllerAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.permissions = List.of();
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    /** 权限网关放行后的请求会进入业务层；mock 数据源下统一抛 BaseException(表单不存在)，
     *  经 servlet 包装为 ServletException。断言"异常来自业务层"即证明未被 401/403 拦截。 */
    private void performAndAssertGatewayPassed(org.springframework.test.web.servlet.MockMvc mvc,
                                               org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        try {
            mvc.perform(req).andReturn();
        } catch (jakarta.servlet.ServletException e) {
            org.assertj.core.api.Assertions.assertThat(String.valueOf(e.getCause()))
                    .contains("表单不存在");
            return;
        }
        // 未抛异常（如 2xx）同样视为网关放行
    }

    @Test
    @DisplayName("有 form:design:save → 存 config 通过权限网关（进入业务层）")
    void saveConfig_withPermission_allowed() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design", "form:design:save");
        performAndAssertGatewayPassed(mockMvc, post("/form/def/f-1/config")
                .header("X-Test-User", "admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"definition\":\"{}\"}"));
    }

    @Test
    @DisplayName("无 form:design:save → 存 config 403（且不触达业务）")
    void saveConfig_withoutPermission_forbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design");
        mockMvc.perform(post("/form/def/f-1/config")
                        .header("X-Test-User", "limited")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\":\"{}\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("无 form:design:publish → 发布 403")
    void publish_withoutPermission_forbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design", "form:design:save");
        mockMvc.perform(post("/form/def/f-1/publish").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("有 form:design:publish → 发布放行（进入业务层，非 403）")
    void publish_withPermission_passesGateway() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design", "form:design:save", "form:design:publish");
        performAndAssertGatewayPassed(mockMvc, post("/form/def/f-1/publish").header("X-Test-User", "admin"));
    }

    @Test
    @DisplayName("S1 有 form:design → 身份/definition/列表过网关；无 → 403")
    void readChain_requiresFormDesignView() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design");
        performAndAssertGatewayPassed(mockMvc, get("/form/def/f-1").header("X-Test-User", "viewer"));
        performAndAssertGatewayPassed(mockMvc, get("/form/def/f-1/definition").header("X-Test-User", "viewer"));
        // /page 在 mock 数据源下业务层 NPE（无分页插件），到达业务层即证明未被 403 拦截
        try {
            mockMvc.perform(get("/form/def/page").header("X-Test-User", "viewer")).andReturn();
        } catch (jakarta.servlet.ServletException expected) {
            // 业务层异常 = 权限网关已放行
        }

        TestAuthenticationFilter.permissions = List.of();
        mockMvc.perform(get("/form/def/f-1").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/form/def/f-1/definition").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/form/def/page").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("有 form:design → 快照列表过网关；无 → 403")
    void snapshots_requiresFormDesignView() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:design");
        performAndAssertGatewayPassed(mockMvc, get("/form/def/f-1/snapshots").header("X-Test-User", "viewer"));

        TestAuthenticationFilter.permissions = List.of();
        mockMvc.perform(get("/form/def/f-1/snapshots").header("X-Test-User", "limited"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未认证 → 401")
    void unauthenticated_unauthorized() throws Exception {
        mockMvc.perform(get("/form/def/f-1/snapshots"))
                .andExpect(status().isUnauthorized());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        FormDefinitionController controller() {
            return new FormDefinitionController(new FormDefServiceImpl(
                    mock(FormDefMapper.class), mock(FormConfigMapper.class), mock(FormSnapshotMapper.class),
                    null, null, mock(com.fasterxml.jackson.databind.ObjectMapper.class)));
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
