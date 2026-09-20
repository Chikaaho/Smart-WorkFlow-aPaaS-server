package com.sw.ck.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.trace.EventRef;
import com.sw.ck.common.response.R;
import com.sw.ck.security.cache.LoginUserCacheService;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.config.SecurityProperties;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 从请求头解析 JWT → 校验 → 取出 userId → 经 {@link LoginUserLoader}（缓存优先，未命中
 * 才回查 {@code UserDetailsProvider} 并写回缓存）装载完整 {@link LoginUser} → 同时注入
 * {@link SecurityContextHolder}（供 {@code authorizeHttpRequests}/{@code @PreAuthorize}
 * 判断"已认证"）与 {@link LoginUserHolder}（供 {@code @ss.hasPermi}、MetaObjectHandler 等
 * 直接取用）。请求处理完毕统一在 finally 中 clear 两者，避免容器线程池复用造成上下文泄漏。
 * <p>
 * Authentication 的 authorities 固定为空集合：本项目的权限判断走 {@code @ss.hasPermi(...)}
 * 直接读取 {@link LoginUser#getPermissions()}，不使用 Spring Security 的
 * GrantedAuthority/hasAuthority 体系，避免维护两套权限表示。
 * <p>
 * {@code loginUserLoader} 由 Spring 托管注入、永不为 null（{@code SecurityAutoConfiguration}
 * 已改为无条件创建并经 {@code ObjectProvider} 运行期解析 {@code UserDetailsProvider}），因此
 * 本过滤器不再有「loader 为 null 则整段跳过」的早退逻辑——那曾把「安全链未装配」这一启动期
 * 缺陷静默降级为对所有请求 401，掩盖了根因。
 * <p>
 * 异常分档（system.md §8：令牌失败=401 vs 基础设施/装配故障=500/503，不得统一降级）：
 * 仅 token 自身的解析/校验失败在此 catch 并降级为「未认证」，交由 authorizeHttpRequests +
 * {@code AuthenticationEntryPoint} 统一吐 401；而 {@code loadByUserId} 内部因
 * {@code UserDetailsProvider} 缺失（装配故障）或 Redis/回查异常（基础设施故障）抛出的异常
 * 在此 catch 并由过滤器直接渲染 503（附根因消息）——不能放任其向容器传播后经 /error
 * 重入安全链被入口点改写为 401「未认证」，那会把 Redis 未就绪伪装成账号/权限问题。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final LoginUserLoader loginUserLoader;
    private final SecurityProperties securityProperties;
    private final LoginUserCacheService loginUserCacheService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            if (authenticate(request, response)) {
                filterChain.doFilter(request, response);
            }
            // authenticate 返回 false 表示已直写 503（基础设施未就绪），必须终止链条——
            // 否则下游 AuthorizationFilter 会把响应覆盖为 401。
        } finally {
            LoginUserHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /** @return false 表示已直写 503 错误响应，调用方不得继续过滤器链 */
    private boolean authenticate(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String token = resolveToken(request);
        if (token == null) {
            return true;
        }
        Long userId;
        try {
            if (!jwtTokenProvider.validate(token)) {
                return true;
            }
            userId = jwtTokenProvider.parseUserId(token);
        } catch (Exception e) {
            // 仅 token 自身的解析/校验失败 → 视为未认证，交由 AuthenticationEntryPoint 统一吐 401。
            log.warn("JWT 认证失败: {}", e.getMessage());
            return true;
        }
        // I5 §3.2 会话撤销（第三方解绑）：token 摘要维度的撤销检查先于装载——
        // 被撤销 token 即使与其他会话共享 userId 缓存也不得复活，新 token 不受影响
        if (loginUserCacheService.isTokenRevoked(token)) {
            return true;
        }
        LoginUser loginUser;
        try {
            loginUser = loginUserLoader.loadByUserId(userId);
        } catch (Exception e) {
            // 基础设施/装配故障（如 Redis 未就绪）→ 503 直出结论 + 事件引用；异常若放行到容器，
            // 会经 /error 重入安全链并被 AuthenticationEntryPoint 改写为 401，误导为账号/权限问题。
            // 真实原因与栈只进日志，由同一 eventRef 关联定位（P61 §3.2）。
            log.error("登录上下文装载失败（认证基础设施异常）: eventRef={}",
                    EventRef.current(), e);
            writeInfrastructureError(response, e);
            return false;
        }
        if (loginUser == null) {
            return true;
        }
        LoginUserHolder.set(loginUser);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    private void writeInfrastructureError(HttpServletResponse response, Exception cause) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        // P61：响应只给出安全结论与事件引用；真实原因（依赖异常、装配缺陷）只进日志，
        // 由 eventRef 关联定位，避免基础设施细节与类名进入未认证可见的响应体。
        R<Void> body = R.fail(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                CommonErrorCode.SYSTEM_ERROR.getErrorKey(),
                CommonErrorCode.SYSTEM_ERROR.getMessage(),
                EventRef.current());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(securityProperties.getTokenHeader());
        if (header != null && header.startsWith(securityProperties.getTokenPrefix())) {
            return header.substring(securityProperties.getTokenPrefix().length());
        }
        return null;
    }
}
