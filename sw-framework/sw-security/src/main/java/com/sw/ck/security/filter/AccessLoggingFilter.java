package com.sw.ck.security.filter;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 结构化访问日志过滤器。每请求一行 ACCESS 日志，含 method、path、status、耗时、
 * requestId（优先 X-Request-Id 请求头，缺省用 Servlet 6 request id）与已认证 userId，
 * 用于行为验收中「浏览器动作 ↔ 访问日志 ↔ API/数据库回读」三方关联。
 * <p>
 * 必须注册在安全链内 {@link DebugAuthenticationFilter} 之后：日志在链完成后输出，
 * 此时调试/JWT 认证上下文尚未被上游 finally 清理，userId 仍可读。
 */
@Slf4j
public class AccessLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            String requestId = request.getHeader("X-Request-Id");
            if (requestId == null || requestId.isBlank()) {
                requestId = request.getRequestId();
            }
            Long userId = null;
            LoginUser loginUser = LoginUserHolder.get();
            if (loginUser != null) {
                userId = loginUser.getUserId();
            }
            log.info("ACCESS method={} path={} status={} costMs={} requestId={} userId={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    System.currentTimeMillis() - start, requestId, userId);
        }
    }
}
