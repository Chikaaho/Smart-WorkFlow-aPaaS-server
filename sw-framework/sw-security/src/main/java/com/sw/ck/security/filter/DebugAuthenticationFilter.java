package com.sw.ck.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.response.R;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.DebugAuthenticationProfile;
import com.sw.ck.security.config.DebugAuthenticationProperties;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受控开发调试认证过滤器。
 * <p>
 * 唯一接受 {@code Authorization: Bearer test_<userId>}，并且同时要求开关、dev/test
 * profile 和真实回环来源成立。成功后仍经 {@link LoginUserLoader} 回查正式用户、租户、
 * 角色和权限；本过滤器不创建用户、不注入权限、不签发 JWT，也不写 Cookie 或 token 表。
 * 调试 token 不是 JWT，拒绝后继续走正式 JWT 过滤器，最终由既有安全链决定 401/403。
 */
@Slf4j
public class DebugAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^test_([1-9][0-9]*)$");
    private static final String DEBUG_TOKEN_PREFIX = "test_";

    private final LoginUserLoader loginUserLoader;
    private final SecurityProperties securityProperties;
    private final DebugAuthenticationProperties debugAuthenticationProperties;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public DebugAuthenticationFilter(LoginUserLoader loginUserLoader,
                                     SecurityProperties securityProperties,
                                     DebugAuthenticationProperties debugAuthenticationProperties,
                                     Environment environment,
                                     ObjectMapper objectMapper) {
        this.loginUserLoader = loginUserLoader;
        this.securityProperties = securityProperties;
        this.debugAuthenticationProperties = debugAuthenticationProperties;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null || !token.startsWith(DEBUG_TOKEN_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        Matcher matcher = TOKEN_PATTERN.matcher(token);
        Long userId = matcher.matches() ? Long.valueOf(matcher.group(1)) : null;
        String debugTokenId = userId == null ? "test_<invalid>" : "test_" + userId;
        boolean contextSet = false;
        try {
            if (userId == null) {
                audit(request, debugTokenId, null, "REJECTED", "INVALID_FORMAT");
                filterChain.doFilter(request, response);
                return;
            }
            if (!debugAuthenticationProperties.isEnabled()) {
                audit(request, debugTokenId, userId, "REJECTED", "DISABLED");
                filterChain.doFilter(request, response);
                return;
            }
            if (!DebugAuthenticationProfile.isDevelopmentOnly(environment)) {
                audit(request, debugTokenId, userId, "REJECTED", "PROFILE_NOT_ALLOWED");
                filterChain.doFilter(request, response);
                return;
            }
            if (!isLoopback(request.getRemoteAddr())) {
                audit(request, debugTokenId, userId, "REJECTED", "NON_LOOPBACK_SOURCE");
                filterChain.doFilter(request, response);
                return;
            }

            // 每次从正式存储回查，确保停用/删除/角色变更不会被旧登录上下文缓存掩盖。
            loginUserLoader.kickOut(userId);
            LoginUser loginUser;
            try {
                loginUser = loginUserLoader.loadByUserId(userId);
            } catch (RuntimeException exception) {
                audit(request, debugTokenId, userId, "REJECTED", "IDENTITY_INFRASTRUCTURE_ERROR");
                writeInfrastructureError(response);
                return;
            }
            if (loginUser == null) {
                audit(request, debugTokenId, userId, "REJECTED", "USER_NOT_FOUND_OR_INACTIVE");
                filterChain.doFilter(request, response);
                return;
            }

            LoginUserHolder.set(loginUser);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
            contextSet = true;
            audit(request, debugTokenId, userId, "ACCEPTED", "FORMAL_IDENTITY_LOADED");
            filterChain.doFilter(request, response);
        } finally {
            if (contextSet) {
                LoginUserHolder.clear();
                SecurityContextHolder.clearContext();
            }
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(securityProperties.getTokenHeader());
        if (header != null && header.startsWith(securityProperties.getTokenPrefix())) {
            return header.substring(securityProperties.getTokenPrefix().length());
        }
        return null;
    }

    private boolean isLoopback(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private void audit(HttpServletRequest request,
                       String debugTokenId,
                       Long userId,
                       String decision,
                       String reason) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getRequestId();
        }
        log.info("debug_auth decision={} debugTokenId={} userId={} path={} requestId={} remoteAddr={} reason={}",
                decision, debugTokenId, userId, request.getRequestURI(), requestId,
                request.getRemoteAddr(), reason);
    }

    private void writeInfrastructureError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                R.fail(503, "登录上下文装载失败（认证基础设施未就绪）")));
        response.getWriter().flush();
    }
}
