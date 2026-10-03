package com.sw.ck.form.txn.controller;

import com.sw.ck.form.txn.service.C1PolicyService;
import com.sw.ck.form.txn.service.TxnActionExecutor;
import com.sw.ck.form.txn.service.TxnActionService;
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
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
 * P62 事务动作边界鉴权证据（T02：无权限被拒绝；走真实 Spring Method Security）。
 * <p>
 * 覆盖 TxnActionController 的权限声明：
 * <ul>
 *   <li>配置管理（新建/更新/校验/启停）→ {@code form:action:manage}</li>
 *   <li>发布与 C1 保护策略保存 → {@code form:action:publish}</li>
 *   <li>动作与结果只读 → {@code form:action:view}</li>
 *   <li>调用执行与结果/预占/台账回查 → {@code form:action:invoke}</li>
 *   <li>无权限 → 403，未认证 → 401</li>
 * </ul>
 * 手法对齐 {@code FormDefinitionControllerAuthorizationTest}：测试鉴权 Filter 注入
 * LoginUser/权限集，请求经过真实 springSecurityFilterChain + {@code @PreAuthorize}；
 * 服务层以 mock 隔离，「过闸门」= HTTP 200（未落 401/403）。
 */
@SpringJUnitConfig(TxnActionControllerAuthorizationTest.TestConfig.class)
@WebAppConfiguration
@DisplayName("P62 事务动作控制器边界鉴权（T02）")
class TxnActionControllerAuthorizationTest {

    private static final String ACTION_BODY =
            "{\"actionKey\":\"stock_reserve\",\"name\":\"库存预占\",\"actionType\":\"RESERVE\","
                    + "\"config\":{\"balanceField\":\"qty_available\",\"reservedField\":\"qty_reserved\","
                    + "\"expiresInSeconds\":900}}";

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void tearDown() {
        TestAuthenticationFilter.permissions = List.of();
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    // ==================== form:action:manage ====================

    @Test
    @DisplayName("manage 权限：新建/更新/发布校验/停用/启用过闸门；无权限 403")
    void manageEndpoints_requireManagePermission() throws Exception {
        String[] required = {"form:action:manage"};

        TestAuthenticationFilter.permissions = List.of(required);
        expectOk(post("/form/action").param("formId", "f-1").header("X-Test-User", "admin")
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY));
        expectOk(put("/form/action/a-1").header("X-Test-User", "admin")
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY));
        expectOk(post("/form/action/a-1/validate").header("X-Test-User", "admin"));
        expectOk(post("/form/action/a-1/disable").header("X-Test-User", "admin"));
        expectOk(post("/form/action/a-1/enable").header("X-Test-User", "admin"));

        TestAuthenticationFilter.permissions = List.of();
        expectForbidden(post("/form/action").param("formId", "f-1").header("X-Test-User", "limited")
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY));
        expectForbidden(put("/form/action/a-1").header("X-Test-User", "limited")
                .contentType(MediaType.APPLICATION_JSON).content(ACTION_BODY));
        expectForbidden(post("/form/action/a-1/validate").header("X-Test-User", "limited"));
        expectForbidden(post("/form/action/a-1/disable").header("X-Test-User", "limited"));
        expectForbidden(post("/form/action/a-1/enable").header("X-Test-User", "limited"));
    }

    // ==================== form:action:publish ====================

    @Test
    @DisplayName("publish 权限：发布与 C1 策略保存过闸门；无权限 403")
    void publishEndpoints_requirePublishPermission() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:action:publish");
        expectOk(post("/form/action/a-1/publish").header("X-Test-User", "publisher"));
        expectOk(put("/form/action/c1-policy").param("formId", "f-1").header("X-Test-User", "publisher")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":{\"enabled\":true,\"protectedFields\":[\"qty_available\"]}}"));

        TestAuthenticationFilter.permissions = List.of("form:action:manage");
        expectForbidden(post("/form/action/a-1/publish").header("X-Test-User", "manager"));
        expectForbidden(put("/form/action/c1-policy").param("formId", "f-1").header("X-Test-User", "manager")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":{\"enabled\":true,\"protectedFields\":[\"qty_available\"]}}"));
    }

    // ==================== form:action:view ====================

    @Test
    @DisplayName("view 权限：列表/详情/C1 读取过闸门；无权限 403")
    void viewEndpoints_requireViewPermission() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:action:view");
        expectOk(get("/form/action/list").param("formId", "f-1").header("X-Test-User", "viewer"));
        expectOk(get("/form/action/a-1").header("X-Test-User", "viewer"));
        expectOk(get("/form/action/c1-policy").param("formId", "f-1").header("X-Test-User", "viewer"));

        TestAuthenticationFilter.permissions = List.of();
        expectForbidden(get("/form/action/list").param("formId", "f-1").header("X-Test-User", "limited"));
        expectForbidden(get("/form/action/a-1").header("X-Test-User", "limited"));
        expectForbidden(get("/form/action/c1-policy").param("formId", "f-1").header("X-Test-User", "limited"));
    }

    // ==================== form:action:invoke ====================

    @Test
    @DisplayName("invoke 权限：调用与结果/预占/台账回查过闸门；无权限 403")
    void invokeEndpoints_requireInvokePermission() throws Exception {
        TestAuthenticationFilter.permissions = List.of("form:action:invoke");
        expectOk(post("/form/action/a-1/invoke").header("X-Test-User", "operator")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recordId\":\"r-1\",\"quantity\":\"5\",\"invocationKey\":\"K1\"}"));
        expectOk(get("/form/action/invocations/i-1").header("X-Test-User", "operator"));
        expectOk(get("/form/action/a-1/invocations").header("X-Test-User", "operator"));
        expectOk(get("/form/action/reservations/rv-1").header("X-Test-User", "operator"));
        expectOk(get("/form/action/a-1/reservations").header("X-Test-User", "operator"));
        expectOk(get("/form/action/a-1/ledger").header("X-Test-User", "operator"));

        TestAuthenticationFilter.permissions = List.of();
        expectForbidden(post("/form/action/a-1/invoke").header("X-Test-User", "limited")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recordId\":\"r-1\",\"quantity\":\"5\"}"));
        expectForbidden(get("/form/action/invocations/i-1").header("X-Test-User", "limited"));
        expectForbidden(get("/form/action/a-1/invocations").header("X-Test-User", "limited"));
        expectForbidden(get("/form/action/reservations/rv-1").header("X-Test-User", "limited"));
        expectForbidden(get("/form/action/a-1/reservations").header("X-Test-User", "limited"));
        expectForbidden(get("/form/action/a-1/ledger").header("X-Test-User", "limited"));
    }

    // ==================== 未认证 ====================

    @Test
    @DisplayName("未认证 → 401（不触达业务）")
    void unauthenticated_unauthorized() throws Exception {
        mockMvc.perform(get("/form/action/list").param("formId", "f-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/form/action/a-1/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ==================== 工具 ====================

    private void expectOk(MockHttpServletRequestBuilder req) throws Exception {
        mockMvc.perform(req).andExpect(status().isOk());
    }

    private void expectForbidden(MockHttpServletRequestBuilder req) throws Exception {
        mockMvc.perform(req).andExpect(status().isForbidden());
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableMethodSecurity
    static class TestConfig {

        @Bean
        TxnActionController controller() {
            return new TxnActionController(mock(TxnActionService.class), mock(TxnActionExecutor.class),
                    mock(C1PolicyService.class), mock(com.sw.ck.form.txn.guard.TxnActionRealtimeGuard.class));
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
