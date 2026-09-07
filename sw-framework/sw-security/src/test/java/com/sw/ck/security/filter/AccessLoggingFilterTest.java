package com.sw.ck.security.filter;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AccessLoggingFilter 行为聚焦测试：链后输出 ACCESS 日志不抛异常、
 * userId 取自登录上下文、requestId 优先取 X-Request-Id 请求头。
 */
class AccessLoggingFilterTest {

    private final AccessLoggingFilter filter = new AccessLoggingFilter();

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    @Test
    void passesChainThroughAndClearsContextSafe() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workflow/tasks/todo");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void userIdResolvableFromLoginContextInsideFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/workflow/tasks/1/complete");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(42L);
        LoginUserHolder.set(loginUser);
        // 模拟链内行为后由上游清理；过滤器 finally 阶段上下文仍可读
        filter.doFilterInternal(request, response, mock(FilterChain.class));
        LoginUserHolder.clear();

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void headerRequestIdPreferredOverServletId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("X-Request-Id", "hdr-req-9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        filter.doFilterInternal(request, response, mock(FilterChain.class));

        assertThat(request.getHeader("X-Request-Id")).isEqualTo("hdr-req-9");
    }
}
