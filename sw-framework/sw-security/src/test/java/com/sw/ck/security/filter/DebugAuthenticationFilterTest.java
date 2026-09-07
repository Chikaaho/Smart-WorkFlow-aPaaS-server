package com.sw.ck.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.DebugAuthenticationProfile;
import com.sw.ck.security.config.DebugAuthenticationProperties;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebugAuthenticationFilterTest {

    private final LoginUserLoader loginUserLoader = mock(LoginUserLoader.class);
    private final SecurityProperties securityProperties = new SecurityProperties();
    private final DebugAuthenticationProperties debugProperties = new DebugAuthenticationProperties();
    private final MockEnvironment environment = environment("dev");
    private final DebugAuthenticationFilter filter = new DebugAuthenticationFilter(
            loginUserLoader, securityProperties, debugProperties, environment, new ObjectMapper());

    @AfterEach
    void clearContext() {
        LoginUserHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void validLoopbackToken_loadsFormalIdentityAndDoesNotCreateResponseToken() throws Exception {
        debugProperties.setEnabled(true);
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(7L);
        loginUser.setTenantId(42L);
        loginUser.setRoles(List.of("reviewer"));
        loginUser.setPermissions(List.of("workflow:def:view"));
        when(loginUserLoader.loadByUserId(7L)).thenReturn(loginUser);

        MockHttpServletRequest request = request("test_7", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean downstreamAuthenticated = new AtomicBoolean();
        FilterChain chain = (req, res) ->
                downstreamAuthenticated.set(LoginUserHolder.get() == loginUser
                        && SecurityContextHolder.getContext().getAuthentication() != null);

        filter.doFilter(request, response, chain);

        assertThat(downstreamAuthenticated).isTrue();
        assertThat(response.getCookies()).isEmpty();
        assertThat(response.getContentAsString()).doesNotContain("accessToken", "refresh");
        verify(loginUserLoader).kickOut(7L);
        verify(loginUserLoader).loadByUserId(7L);
        assertThat(LoginUserHolder.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void disabledDebugAuth_leavesRequestUnauthenticated() throws Exception {
        AtomicBoolean downstreamUnauthenticated = new AtomicBoolean();
        FilterChain chain = (req, res) ->
                downstreamUnauthenticated.set(LoginUserHolder.get() == null);

        filter.doFilter(request("test_7", "127.0.0.1"), new MockHttpServletResponse(), chain);

        assertThat(downstreamUnauthenticated).isTrue();
        verify(loginUserLoader, org.mockito.Mockito.never()).loadByUserId(7L);
    }

    @Test
    void invalidFormatAndNonLoopbackSource_areRejectedBeforeIdentityLookup() throws Exception {
        debugProperties.setEnabled(true);
        FilterChain invalidChain = (req, res) -> { };
        filter.doFilter(request("test_user", "127.0.0.1"), new MockHttpServletResponse(), invalidChain);

        FilterChain nonLoopbackChain = (req, res) -> { };
        filter.doFilter(request("test_7", "192.0.2.7"), new MockHttpServletResponse(), nonLoopbackChain);
        verify(loginUserLoader, org.mockito.Mockito.never()).kickOut(7L);
    }

    @Test
    void unknownOrInactiveFormalIdentity_isRejectedWithoutPrivilegeFallback() throws Exception {
        debugProperties.setEnabled(true);
        when(loginUserLoader.loadByUserId(7L)).thenReturn(null);
        AtomicBoolean downstreamCalled = new AtomicBoolean();
        FilterChain chain = (req, res) -> downstreamCalled.set(true);

        filter.doFilter(request("test_7", "127.0.0.1"), new MockHttpServletResponse(), chain);

        assertThat(downstreamCalled).isTrue();
        assertThat(LoginUserHolder.get()).isNull();
        verify(loginUserLoader).kickOut(7L);
        verify(loginUserLoader).loadByUserId(7L);
    }

    @Test
    void identityInfrastructureFailure_returns503AndDoesNotContinueChain() throws Exception {
        debugProperties.setEnabled(true);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(loginUserLoader).loadByUserId(7L);
        AtomicBoolean downstreamCalled = new AtomicBoolean();
        FilterChain chain = (req, res) -> downstreamCalled.set(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("test_7", "127.0.0.1"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("认证基础设施未就绪");
        assertThat(downstreamCalled).isFalse();
    }

    @Test
    void profilePolicy_requiresOnlyDevOrTestProfiles() {
        assertThat(DebugAuthenticationProfile.isDevelopmentOnly(environment)).isTrue();
        assertThat(DebugAuthenticationProfile.isDevelopmentOnly(
                environment("prod"))).isFalse();
        assertThat(DebugAuthenticationProfile.isDevelopmentOnly(
                environment("dev", "prod"))).isFalse();
        assertThat(DebugAuthenticationProfile.isDevelopmentOnly(new MockEnvironment())).isFalse();
    }

    private static MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private MockHttpServletRequest request(String token, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/system/auth/me");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Request-Id", "debug-test-request");
        return request;
    }
}
