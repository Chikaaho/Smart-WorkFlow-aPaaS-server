package com.sw.ck.system.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 首次登录强制改密的服务端拦截（1.0.0 F1-4）。
 * <p>
 * 身份带 {@code mustChangePassword=true} 时，除改密闭环必需的端点外全部业务请求返回 403，
 * 不依赖前端跳转。放行范围收敛为改密链路本身：refresh / logout / 改密（/auth/password）
 * 与改密页渲染所需的自身信息只读端点（/auth/me、/auth/menus）。
 * 既有账号与 SSO 登录该标记恒为 false，链路零影响。
 * </p>
 * <p>
 * 以普通 Servlet 过滤器注册（默认 order 在 springSecurityFilterChain 之后），
 * 到达本过滤器时 JWT 已完成身份装配；无登录态的请求直接放行交给安全链处理。
 * </p>
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MustChangePasswordFilter.class);

    /** 改密闭环放行端点（不含 context-path；/auth/me、/auth/menus 仅自身只读信息）。 */
    private static final List<String> ALLOWED_PATHS = List.of(
            "/auth/refresh", "/auth/logout", "/auth/password", "/auth/me", "/auth/menus");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        LoginUser current = LoginUserHolder.get();
        if (current == null || !current.isMustChangePassword()) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = requestPath(request);
        if (ALLOWED_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.info("首登改密拦截: userId={} path={}", current.getUserId(), path);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        R<Void> body = R.fail(HttpServletResponse.SC_FORBIDDEN,
                SystemErrorKeys.MUST_CHANGE_PASSWORD, "首次登录请先修改密码", EventRef.current());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 去掉 context-path 后的控制器路径（context-path 生产可为 /sw-server）。 */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
