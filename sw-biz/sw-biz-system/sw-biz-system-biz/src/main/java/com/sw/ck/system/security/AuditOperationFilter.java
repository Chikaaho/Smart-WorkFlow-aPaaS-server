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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 管理操作审计过滤器（1.0.0 F3-2，规划返修 R3）：只记录<b>改动类</b>管理操作
 * （新增、修改、删除、启停、授权、导入、导出、发布），查询、分页、详情不记。
 * <p>
 * 记录内容面向业务用户可读：动作为中文（新增/修改/删除/导入/导出…），模块为中文名
 * （用户管理、角色管理…），对象为业务对象（如「用户 zhangsan」；拿不到名称时写对象 id），
 * 不再记录接口路径。对象名优先取请求体里的名称字段，取不到回退路径 id。
 * </p>
 * <p>
 * 结果以响应为准：HTTP 2xx 且（JSON 响应 {@code code}=0，或非 JSON 的下载类响应）记
 * SUCCESS，否则记 FAIL 并携带可读的 {@code failReason}。写审计失败不影响业务。
 * 未认证请求、首登改密拦截前的请求不记录。
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

    /** 查询/读取类路径末段（POST 形式的查询也不记）。 */
    private static final Set<String> QUERY_SEGMENTS = Set.of(
            "page", "list", "query", "search", "tree", "detail", "options", "candidates",
            "check", "validate", "preview", "stats", "count", "current", "audit",
            "bindings", "administrators", "status-audits", "script-context");

    /** 路径末段 → 中文动作（未命中的按 HTTP 方法归类）。 */
    private static final Map<String, String> SEGMENT_ACTIONS = new LinkedHashMap<>();

    static {
        SEGMENT_ACTIONS.put("import", "导入");
        SEGMENT_ACTIONS.put("export", "导出");
        SEGMENT_ACTIONS.put("publish", "发布");
        SEGMENT_ACTIONS.put("publish-version", "发布");
        SEGMENT_ACTIONS.put("enable", "启用");
        SEGMENT_ACTIONS.put("enabled", "启用");
        SEGMENT_ACTIONS.put("activate", "启用");
        SEGMENT_ACTIONS.put("disable", "停用");
        SEGMENT_ACTIONS.put("suspend", "停用");
        SEGMENT_ACTIONS.put("menus", "授权");
        SEGMENT_ACTIONS.put("roles", "授权");
        SEGMENT_ACTIONS.put("posts", "授权");
        SEGMENT_ACTIONS.put("administrator", "授权");
        SEGMENT_ACTIONS.put("authorize-rules", "授权");
        SEGMENT_ACTIONS.put("reset-password", "重置密码");
        SEGMENT_ACTIONS.put("config", "修改");
        SEGMENT_ACTIONS.put("visibility", "修改");
        SEGMENT_ACTIONS.put("name", "修改");
        SEGMENT_ACTIONS.put("list-config", "修改");
        SEGMENT_ACTIONS.put("status", "启停");
        SEGMENT_ACTIONS.put("theme-rule", "修改");
        SEGMENT_ACTIONS.put("graph", "修改");
        SEGMENT_ACTIONS.put("copy", "新增");
    }

    /** 路径模块段 → (中文模块名, 业务对象名)。 */
    private static final Map<String, String[]> MODULE_LABELS = new LinkedHashMap<>();

    static {
        MODULE_LABELS.put("user", new String[]{"用户管理", "用户"});
        MODULE_LABELS.put("user-group", new String[]{"用户组管理", "用户组"});
        MODULE_LABELS.put("role", new String[]{"角色管理", "角色"});
        MODULE_LABELS.put("dept", new String[]{"部门管理", "部门"});
        MODULE_LABELS.put("post", new String[]{"岗位管理", "岗位"});
        MODULE_LABELS.put("post-delegate", new String[]{"岗位代理", "岗位代理"});
        MODULE_LABELS.put("menu", new String[]{"菜单管理", "菜单"});
        MODULE_LABELS.put("dict", new String[]{"字典管理", "字典"});
        MODULE_LABELS.put("tenant", new String[]{"租户管理", "租户"});
        MODULE_LABELS.put("sso", new String[]{"单点登录", "单点登录配置"});
        MODULE_LABELS.put("notify-subject-binding", new String[]{"通知主体绑定", "通知主体绑定"});
        MODULE_LABELS.put("user-workspace", new String[]{"工作台配置", "工作台配置"});
        MODULE_LABELS.put("audit-log", new String[]{"日志查询", "日志"});
        MODULE_LABELS.put("defs", new String[]{"流程定义", "流程定义"});
        MODULE_LABELS.put("categories", new String[]{"流程分类", "流程分类"});
        MODULE_LABELS.put("catalog", new String[]{"流程目录", "流程目录"});
        MODULE_LABELS.put("def", new String[]{"表单定义", "表单定义"});
    }

    /** 请求体里的业务名称字段（按优先级）。 */
    private static final List<String> BODY_NAME_KEYS = List.of(
            "username", "roleName", "deptName", "postName", "menuName", "dictName",
            "tenantName", "groupName", "processName", "formName", "defName", "name", "title");

    private static final Pattern BODY_CODE = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern BODY_MSG = Pattern.compile("\"msg\"\\s*:\\s*\"([^\"]{0,300})\"");
    private static final Pattern JSON_NAME = Pattern.compile(
            "\"(?:" + String.join("|", BODY_NAME_KEYS) + ")\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final Pattern STATUS_ZERO = Pattern.compile("\"status\"\\s*:\\s*\"?0\"?");
    private static final Pattern ENABLED_TRUE = Pattern.compile("\"enabled\"\\s*:\\s*true");
    /** 路径中的业务对象标识：数字 id 或 UUID（表单定义等使用 UUID 主键）。 */
    private static final Pattern PATH_ID = Pattern.compile(
            "/([0-9a-fA-F]{8}-[0-9a-fA-F-]{8,30}|\\d{1,19})(?=/|$)");

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
        if (AUDITED_PREFIXES.stream().noneMatch(path::startsWith)) {
            return true;
        }
        // R3：查询/分页/详情不记（POST 形式的查询同样排除）
        return QUERY_SEGMENTS.contains(lastSegment(path));
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
        // 仅对 JSON 请求缓存请求体（用于取业务对象名称）；multipart 导入不读体
        HttpServletRequest effectiveRequest = isMultipart(request)
                ? request : new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            try {
                filterChain.doFilter(effectiveRequest, responseWrapper);
            } catch (Exception e) {
                record(request, current, false, "系统处理异常，操作未完成");
                responseWrapper.copyBodyToResponse();
                throw e;
            }
            LAST_BODY.set(readRequestBody(effectiveRequest));
            String body = readResponseBody(responseWrapper);
            int status = responseWrapper.getStatus();
            boolean jsonResponse = isJsonResponse(responseWrapper);
            String bodyCode = jsonResponse ? bodyCode(body) : null;
            boolean success = status < 400 && (!jsonResponse || "0".equals(bodyCode));
            String failReason = success ? null : readableFailure(status, jsonResponse, body, bodyCode);
            try {
                record(request, current, success, failReason);
            } catch (Exception e) {
                log.warn("操作审计记录异常(忽略): {}", e.getMessage());
            }
            responseWrapper.copyBodyToResponse();
        } finally {
            LAST_BODY.remove();
        }
    }

    private void record(HttpServletRequest request, LoginUser current,
                        boolean success, String failReason) {
        String path = requestPath(request);
        String moduleSegment = moduleSegment(path);
        Map<String, String> detail = describe(path, request.getMethod());
        SysAuditLog entry = new SysAuditLog();
        entry.setLogType(SysAuditLog.TYPE_OPERATION);
        entry.setTenantId(current.getTenantId());
        entry.setUserId(current.getUserId());
        entry.setUsername(current.getUsername());
        entry.setResult(success ? SysAuditLog.RESULT_SUCCESS : SysAuditLog.RESULT_FAIL);
        entry.setFailReason(success ? null : failReason);
        entry.setModule(labelOf(moduleSegment)[0]);
        entry.setAction(detail.get("action"));
        entry.setTarget(detail.get("target"));
        entry.setIp(AuditLogService.clientIp(request));
        entry.setUserAgent(request.getHeader("User-Agent"));
        auditLogService.getObject().record(entry);
    }

    /**
     * 生成可读的动作与对象：
     * 动作 = 中文动作；对象 = 业务对象类型 + 名称（请求体名称字段优先，回退路径 id）；
     * 导入/导出等无对象的操作写操作名本身。
     */
    private Map<String, String> describe(String path, String method) {
        String segment = lastSegment(path);
        String action = actionOf(path, segment, method);
        String[] label = labelOf(moduleSegment(path));
        String objectType = label[1];
        String name = null;
        switch (action) {
            case "导入" -> name = objectType + "批量导入";
            case "导出" -> name = objectType + "导出";
            case "发布" -> {
                String id = lastPathId(path);
                name = id != null ? objectType + " " + id + " 发布" : objectType + "版本发布";
            }
            default -> {
                String rawName = bodyName();
                if (rawName != null && !rawName.isBlank()) {
                    name = objectType + " " + rawName;
                } else {
                    String id = lastPathId(path);
                    if (id != null) {
                        name = objectType + " " + id;
                    }
                }
            }
        }
        if (name == null || name.isBlank()) {
            name = objectType;
        }
        return Map.of("action", action, "target", name);
    }

    private String actionOf(String path, String segment, String method) {
        String mapped = SEGMENT_ACTIONS.get(segment);
        if ("toggle".equals(segment) || path.contains("/toggle/")) {
            return path.endsWith("/toggle/false") || path.endsWith("/toggle/0") ? "停用" : "启用";
        }
        if ("启停".equals(mapped)) {
            // status 接口按请求体取值（本系统 status=0 为正常/启用，1 为停用）
            String body = cachedBody();
            if (body != null && STATUS_ZERO.matcher(body).find()) {
                return "启用";
            }
            if (body != null && (body.contains("\"status\":1") || body.contains("\"status\":\"1\""))) {
                return "停用";
            }
            return "修改";
        }
        if (mapped != null) {
            return mapped;
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            return "删除";
        }
        if ("PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            return "修改";
        }
        return "新增";
    }

    private String bodyName() {
        String body = cachedBody();
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher matcher = JSON_NAME.matcher(body);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** 当前请求的缓存体（由 doFilterInternal 写入请求属性，供 describe 读取）。 */
    private String cachedBody() {
        Object value = LAST_BODY.get();
        return value instanceof String text ? text : null;
    }

    private static final ThreadLocal<String> LAST_BODY = new ThreadLocal<>();

    private String readRequestBody(HttpServletRequest request) {
        if (request instanceof ContentCachingRequestWrapper wrapper) {
            byte[] content = wrapper.getContentAsByteArray();
            if (content.length > 0) {
                return new String(content, 0, Math.min(content.length, 8192),
                        StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private String readResponseBody(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        return content.length == 0 ? "" : new String(content, 0,
                Math.min(content.length, 8192), StandardCharsets.UTF_8);
    }

    /** 解析响应体开头的业务码；读不到（非 JSON/空）返回 null。 */
    private String bodyCode(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Matcher matcher = BODY_CODE.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean isJsonResponse(ContentCachingResponseWrapper response) {
        String contentType = response.getContentType();
        if (contentType == null) {
            return true; // 未声明类型时按 JSON 约定处理（R 包）
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.contains("json") || lower.startsWith("text/");
    }

    private String readableFailure(int status, boolean jsonResponse, String body, String bodyCode) {
        Matcher msg = BODY_MSG.matcher(body == null ? "" : body);
        if (jsonResponse && msg.find() && !msg.group(1).isBlank()) {
            return msg.group(1);
        }
        if (status >= 400) {
            return switch (status) {
                case 400 -> "请求参数或当前状态不合法";
                case 401 -> "登录状态已失效";
                case 403 -> "没有权限执行该操作";
                case 404 -> "操作对象不存在";
                case 409 -> "操作对象已被占用或状态冲突";
                case 429 -> "请求过于频繁";
                default -> status >= 500 ? "系统处理异常，操作未完成" : "请求未被受理（HTTP " + status + "）";
            };
        }
        if (!jsonResponse) {
            return "响应格式无法识别";
        }
        return bodyCode == null ? "操作未成功（服务端未返回错误说明）" : "操作未成功（错误码 " + bodyCode + "）";
    }

    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /** 模块段：/system/user → user；/workflow/defs → defs；/form/def → def。 */
    private String moduleSegment(String path) {
        String[] segments = path.split("/");
        if (segments.length >= 3 && !segments[2].isEmpty()) {
            return segments[2];
        }
        if (segments.length >= 2 && !segments[1].isEmpty()) {
            return segments[1];
        }
        return path;
    }

    private String[] labelOf(String segment) {
        String[] label = MODULE_LABELS.get(segment);
        if (label != null) {
            return label;
        }
        if ("workflow".equals(segment)) {
            return new String[]{"流程管理", "流程"};
        }
        if ("form".equals(segment)) {
            return new String[]{"表单管理", "表单"};
        }
        return new String[]{"系统管理", "系统对象"};
    }

    private String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int idx = trimmed.lastIndexOf('/');
        return idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
    }

    private String lastPathId(String path) {
        Matcher matcher = PATH_ID.matcher(path);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    private boolean isMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
    }
}
