package com.sw.ck.system.usergroup;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.support.PermissionService;
import com.sw.ck.system.controller.UserGroupController;
import com.sw.ck.system.entity.SysUserGroup;
import com.sw.ck.system.service.SysUserGroupService;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.filter.OncePerRequestFilter;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import jakarta.servlet.Filter;
import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户组端点请求级鉴权证据（D113 标准 6）。
 * <p>
 * 真实 Spring Method Security 请求链：未认证 401、缺权 403、
 * 查看权限（system:userGroup:list）成功、管理权限（system:userGroup:manage）
 * 成功与拒绝。装配对齐 StorageControllerAuthorizationTest 先例（P24 已验收链路）。
 * </p>
 */
@SpringJUnitConfig(UserGroupAuthorizationTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("用户组请求级鉴权测试")
class UserGroupAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.permissions = List.of();
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("未认证请求 → 401")
    void unauthenticatedRequest_shouldBeUnauthorized() throws Exception {
        mockMvc.perform(get("/system/user-group/page"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("已认证但缺全部权限 → 查看与管理端点均 403")
    void authenticatedWithoutPermission_shouldBeForbidden() throws Exception {
        TestAuthenticationFilter.permissions = List.of();
        mockMvc.perform(post("/system/user-group/page").header("X-Test-User", "admin")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/system/user-group").header("X-Test-User", "admin")
                        .contentType("application/json").content("{\"groupCode\":\"G-1\",\"groupName\":\"组\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("仅查看权限 → 查看端点成功、管理端点 403")
    void viewPermission_onlyReadAllowed() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:userGroup:list");
        mockMvc.perform(post("/system/user-group/page").header("X-Test-User", "admin").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/system/user-group").header("X-Test-User", "admin").contentType("application/json")
                        .content("{\"groupCode\":\"G-1\",\"groupName\":\"组\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("查看+管理权限 → 查看与管理端点均成功")
    void viewAndManagePermissions_shouldSucceed() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:userGroup:list", "system:userGroup:manage");
        mockMvc.perform(post("/system/user-group/page").header("X-Test-User", "admin").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/system/user-group").header("X-Test-User", "admin").contentType("application/json")
                        .content("{\"groupCode\":\"G-1\",\"groupName\":\"组\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("仅管理权限 → 管理端点成功、查看端点 403（权限分离）")
    void managePermission_onlyManageAllowed() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:userGroup:manage");
        mockMvc.perform(post("/system/user-group").header("X-Test-User", "admin").contentType("application/json")
                        .content("{\"groupCode\":\"G-2\",\"groupName\":\"组\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/system/user-group/page").header("X-Test-User", "admin").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("成员端点：读取需查看权限、写入需管理权限")
    void memberEndpoints_permissionSeparation() throws Exception {
        TestAuthenticationFilter.permissions = List.of("system:userGroup:list");
        mockMvc.perform(get("/system/user-group/1/members").header("X-Test-User", "admin"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/system/user-group/1/members").header("X-Test-User", "admin").contentType("application/json").content("[]"))
                .andExpect(status().isForbidden());

        TestAuthenticationFilter.permissions = List.of("system:userGroup:manage");
        mockMvc.perform(put("/system/user-group/1/members").header("X-Test-User", "admin").contentType("application/json").content("[]"))
                .andExpect(status().isOk());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        UserGroupController controller() {
            SysUserGroupService service = mock(SysUserGroupService.class);
            // 鉴权通过后的成功路径：返回空分页结果（避免 null 触发 400）
            PageResult<SysUserGroup> empty = new PageResult<>();
            empty.setRecords(List.of());
            empty.setTotal(0L);
            Mockito.when(service.page(ArgumentMatchers.any(PageParam.class),
                            ArgumentMatchers.<SysUserGroup>any()))
                    .thenReturn(empty);
            return new UserGroupController(service);
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
