package com.sw.ck.system.security;

import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.service.AuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理操作审计过滤器（1.0.0 F3-2）：记录管理域写操作的时间、操作人、模块、动作、对象与结果。
 * <p>
 * 覆盖 F3-2 点名的管理操作域：系统管理（用户/角色/部门/岗位/菜单/字典/租户等
 * {@code /system/**}）、流程定义域（{@code /workflow/defs、/workflow/categories、/workflow/catalog}）、
 * 表单定义域（{@code /form/def}）；人员导入导出（{@code /system/user/import、export}）随之覆盖。
 * 业务流转与业务数据操作（任务、实例、表单数据等）不在第一版审计范围。
 * </p>
 * <p>
 * 结果以响应为准：HTTP 状态 2xx 且响应体 {@code code}=0 记 SUCCESS，否则记 FAIL 并携带
 * {@code failReason}（受控文案）。写审计失败不影响业务（AuditLogService 内部吞错）。
 * 未认证请求、GET 请求不记录（改密前拦截行为由 LOGIN 类型覆盖）。
 * </p>
 */
@Component
public class AuditOperationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditOperationFilter.class);

    /** 管理操作审计的路径前缀（不含 context-path）。 */
    private static final List<String> AUDITED_PREFIXES = List.of(
            "/system/", "/workflow/defs", "/workflow/categories", "/workflow/catalog", "/form/def");

    /** 不审计的特例：会话链路与自助改密（改密已按 LOGIN 类型记录）、脚本执行类旁路。 */
    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "/system/auth/", "/system/tenant-context/", "/system/org/script");

    private static final Pattern BODY_CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");

    /**
     * 惰性解析（1.0.0）：过滤器 Bean 在 Servlet 上下文 onRefresh 阶段实例化，
     * 早于常规单例（sqlSessionFactory）；构造期直接依赖会把 mapper 初始化拖进
     * Tomcat 资源未就绪的时段（启动测试实证），故经 ObjectProvider 延迟到首个请求。
     */
    private final org.springframework.beans.factory.ObjectProvider<AuditLogService> auditLogService;

    public AuditOperationFilter(org.springframework.beans.factory.ObjectProvider<AuditLogService> auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)) {
            return true;
        }
        String path = requestPath(request);
        if (EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith)) {
            return true;
        }
        return AUDITED_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        LoginUser current = LoginUserHolder.get();
        if (current == null || current.isMustChangePassword()) {
            // 未认证（安全链自会拒绝）与首登改密拦截前的请求不产生操作审计
            filterChain.doFilter(request, response);
            return;
        }
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (Exception e) {
            // 罕见：异常未被 MVC 处理器收敛而穿透过滤器。记 FAIL 后原样上抛
            record(request, current, false, "处理异常: " + e.getClass().getSimpleName());
            responseWrapper.copyBodyToResponse();
            throw e;
        }
        int status = responseWrapper.getStatus();
        String bodyCode = bodyCode(responseWrapper);
        boolean success = status < 400 && "0".equals(bodyCode);
        String failReason = success ? null
                : (status >= 400 ? "HTTP " + status : "业务码 " + (bodyCode == null ? "非0" : bodyCode));
        try {
            record(request, current, success, failReason);
        } catch (Exception e) {
            log.warn("操作审计记录异常(忽略): {}", e.getMessage());
        }
        responseWrapper.copyBodyToResponse();
    }

    private void record(HttpServletRequest request, LoginUser current,
                        boolean success, String failReason) {
        String path = requestPath(request);
        SysAuditLog entry = new SysAuditLog();
        entry.setLogType(SysAuditLog.TYPE_OPERATION);
        entry.setTenantId(current.getTenantId());
        entry.setUserId(current.getUserId());
        entry.setUsername(current.getUsername());
        entry.setResult(success ? SysAuditLog.RESULT_SUCCESS : SysAuditLog.RESULT_FAIL);
        entry.setFailReason(success ? null : failReason);
        entry.setModule(moduleOf(path));
        entry.setAction(request.getMethod());
        entry.setTarget(AuditLogService.requestTarget(request));
        entry.setIp(AuditLogService.clientIp(request));
        entry.setUserAgent(request.getHeader("User-Agent"));
        auditLogService.getObject().record(entry);
    }

    /** 解析响应体开头的业务码；读不到（非 JSON/空）返回 null。 */
    private String bodyCode(ContentCachingResponseWrapper responseWrapper) {
        byte[] content = responseWrapper.getContentAsByteArray();
        if (content.length == 0) {
            return null;
        }
        String preview = new String(content, 0, Math.min(content.length, 512),
                StandardCharsets.UTF_8);
        Matcher matcher = BODY_CODE.matcher(preview);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 模块取更细一段：/system/user → user；/workflow/defs → defs；/form/def → def。 */
    private String moduleOf(String path) {
        String[] segments = path.split("/");
        if (segments.length >= 3 && !segments[2].isEmpty()) {
            return segments[2];
        }
        if (segments.length >= 2 && !segments[1].isEmpty()) {
            return segments[1];
        }
        return path;
    }
}
