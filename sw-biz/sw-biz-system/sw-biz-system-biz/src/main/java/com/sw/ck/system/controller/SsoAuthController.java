package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.common.trace.EventRef;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.model.TokenResponse;
import com.sw.ck.common.i18n.LocalizedMessages;
import com.sw.ck.system.security.SystemErrorKeys;
import com.sw.ck.system.sso.SsoAuthService;
import com.sw.ck.system.sso.SsoRejectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 第三方 SSO 控制器（I5）。
 * <p>
 * 服务端授权发起（{@code /auth/sso/{provider}/authorize}，需认证——绑定入口）与
 * 服务端回调/换票（{@code /auth/sso/{provider}/callback}，免认证白名单）；回调后
 * 已绑定用户由既有服务端权威装载建立会话（302 + 一次性 ticket），未绑定返回
 * 受控绑定候选（同源前端页）。token 不进 URL 残留：回跳页持 ticket 经
 * {@code /auth/sso/ticket} 兑换。
 * </p>
 */
@RestController
@RequestMapping("/auth/sso")
public class SsoAuthController {

    private static final Logger log = LoggerFactory.getLogger(SsoAuthController.class);

    /** 第三方登录未完成的统一结论；精确原因只进日志与 SSO 审计（P61 §3.6）。 */
    private static final String SSO_LOGIN_FAILED_MSG =
            "第三方登录未能完成，请返回登录页重新发起，或改用账号密码登录";

    private final SsoAuthService ssoAuthService;
    private final SsoTicketStore ticketStore;
    private final com.sw.ck.system.sso.SsoCallbackPolicy callbackPolicy;
    private final com.sw.ck.system.service.SysUserService sysUserService;
    private final com.sw.ck.security.jwt.JwtTokenProvider jwtTokenProvider;
    private final com.sw.ck.security.jwt.JwtProperties jwtProperties;
    private final com.sw.ck.system.service.RefreshTokenService refreshTokenService;
    private final com.sw.ck.system.service.TenantValidityService tenantValidityService;
    @org.springframework.beans.factory.annotation.Value("${sw.security.cookie.secure:false}")
    private boolean cookieSecure;
    @org.springframework.beans.factory.annotation.Value("${sw.security.cookie.path:/api/auth/}")
    private String cookiePath;

    public SsoAuthController(SsoAuthService ssoAuthService,
                             SsoTicketStore ticketStore,
                             com.sw.ck.system.sso.SsoCallbackPolicy callbackPolicy,
                             com.sw.ck.system.service.SysUserService sysUserService,
                             com.sw.ck.security.jwt.JwtTokenProvider jwtTokenProvider,
                             com.sw.ck.security.jwt.JwtProperties jwtProperties,
                             com.sw.ck.system.service.RefreshTokenService refreshTokenService,
                             com.sw.ck.system.service.TenantValidityService tenantValidityService) {
        this.ssoAuthService = ssoAuthService;
        this.ticketStore = ticketStore;
        this.callbackPolicy = callbackPolicy;
        this.sysUserService = sysUserService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
        this.tenantValidityService = tenantValidityService;
    }

    /**
     * 授权发起（需认证；登录页发起场景经 tenant 上下文由查询参数显式提供租户时另行处理）。
     * 返回 Provider 授权 URL 与 state；state 同时落库（摘要）限时一次性。
     */
    @GetMapping("/{provider}/authorize")
    public R<Map<String, Object>> authorize(@PathVariable("provider") String provider,
                                            @RequestParam(value = "redirect", required = false) String redirect) {
        SsoAuthService.AuthorizeStart start = ssoAuthService.startAuthorize(provider, redirect);
        return R.ok(Map.of(
                "authorizeUrl", start.authorizeUrl(),
                "state", start.state()));
    }

    /**
     * 登录前安全授权发起（免认证白名单；I5 复验 G5）。
     * V012-BUG-019：登录页只提交租户名称（tenantName），服务端按名称精确解析为
     * 唯一租户（零命中/多行命中/停用均 fail closed），并校验该租户 Provider 配置
     * 启用后才签发 state；不授予任何权限。不再接受手填数值租户 ID。
     */
    @GetMapping("/{provider}/authorize-login")
    public R<Map<String, Object>> authorizeLogin(@PathVariable("provider") String provider,
                                                 @RequestParam(value = "tenantName", required = false) String tenantName,
                                                 @RequestParam(value = "redirect", required = false) String redirect) {
        try {
            SsoAuthService.AuthorizeStart start = ssoAuthService.startAuthorizeLogin(provider, tenantName, redirect);
            return R.ok(Map.of(
                    "authorizeUrl", start.authorizeUrl(),
                    "state", start.state()));
        } catch (SsoRejectionException e) {
            // fail closed 必须返回可判定结果（不可 500/不可栈泄漏；不回显任何敏感值）
            log.warn("SSO 登录前发起被拒: eventRef={} errorKey={} detail={}",
                    EventRef.current(), e.getErrorKey(), e.getMessage());
            return R.failResolved(400, e.getErrorKey(), e.getMessage(), EventRef.current());
        } catch (RuntimeException e) {
            log.warn("SSO 登录前发起失败: eventRef={} detail={}", EventRef.current(), e.getMessage(), e);
            return R.failResolved(400, SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SSO_LOGIN_FAILED_MSG,
                    EventRef.current());
        }
    }

    /**
     * 服务端回调/换票（免认证白名单）。校验一次性 state → code 换外部身份 →
     * B 端手机号准入（sso-admin-config §一）：已有绑定经本地有效性+手机号一致性
     * 校验登录；无绑定按可信手机号在当前租户唯一准入用户并自动绑定登录；准入
     * 拒绝统一 302 到回跳页携带脱敏 errorKey（不携带 code/state 原文）。
     */
    @GetMapping("/{provider}/callback")
    public org.springframework.http.ResponseEntity<Void> callback(@PathVariable("provider") String provider,
                           @RequestParam(value = "code", required = false) String code,
                           @RequestParam(value = "state", required = false) String state) {
        try {
            SsoAuthService.CallbackResult result = ssoAuthService.handleCallback(provider, code, state);
            if (!result.bound()) {
                // 防御分支：准入链未绑定即拒绝，不再签发候选票据（旧候选绑定页已移除）
                return deny(provider, new SsoRejectionException(SystemErrorKeys.SSO_ADMISSION_REJECTED,
                        "暂时无法使用第三方登录，请联系管理员核对账号信息"));
            }
            String ticket = ticketStore.issue(result.userId(), result.tenantId(), result.provider(),
                    result.configDigest());
            // 统一经同源回跳页兑换票据（工作台等目标页不消费票据）；
            // state.redirect_path 仅作为兑换后的最终去向（redirect 参数）
            String target = "/sso/return?sso_ticket=" + urlEncode(ticket)
                    + "&redirect=" + urlEncode(safeRedirect(result.redirectPath()));
            return seeOther(callbackPolicy.resolveFrontendPath(target, "/sso/return"));
        } catch (SsoRejectionException | IllegalStateException | IllegalArgumentException
                 | com.sw.ck.system.sso.SsoProviderClient.SsoProviderException e) {
            return deny(provider, e);
        }
    }

    private org.springframework.http.ResponseEntity<Void> deny(String provider, Exception e) {
        // 回调拒绝 302 到同源前端回跳页并携带脱敏 errorKey：不回显 code/state 原文，
        // 不泄漏 Provider 配置、三方响应、租户标识或回调白名单，也不泄漏栈。
        String errorKey;
        if (e instanceof SsoRejectionException rejection) {
            errorKey = rejection.getErrorKey();
            log.warn("SSO 回调被拒: eventRef={} provider={} errorKey={} detail={}",
                    EventRef.current(), provider, rejection.getErrorKey(), rejection.getMessage());
        } else {
            errorKey = SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED;
            log.warn("SSO 回调失败: eventRef={} provider={} detail={}",
                    EventRef.current(), provider, e.getMessage(), e);
        }
        return seeOther(callbackPolicy.resolveFrontendPath("/sso/return?sso_error=" + urlEncode(errorKey), "/sso/return"));
    }

    /** 302 回跳（Location 为站内相对路径，浏览器按回调同源解析，票据不出站）。 */
    private org.springframework.http.ResponseEntity<Void> seeOther(String path) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .location(java.net.URI.create(path))
                .build();
    }

    /**
     * 一次性票据兑换（免认证白名单）：会话票据 → 本地会话（access token + refresh
     * cookie，与第一方登录同一 TokenResponse/cookie 契约）；票据限时一次性。
     */
    @PostMapping("/ticket")
    public R<TokenResponse> exchangeTicket(@RequestBody Map<String, String> body,
                                           jakarta.servlet.http.HttpServletResponse response) {
        SsoTicketStore.ConsumedSession session = ticketStore.consumeSession(body.get("ticket"));
        if (session == null) {
            return R.failResolved(401, SystemErrorKeys.SSO_TICKET_INVALID,
                LocalizedMessages.text(SystemErrorKeys.SSO_TICKET_INVALID, "登录票据无效或已过期"), EventRef.current());
        }
        try {
            tenantValidityService.requireValid(session.tenantId());
        } catch (IllegalStateException e) {
            return R.failResolved(401, SystemErrorKeys.SSO_TENANT_INVALID,
                LocalizedMessages.text(SystemErrorKeys.SSO_TENANT_INVALID, "租户无效或已停用/过期"), EventRef.current());
        }
        // A4 配置期间授权语义：票据绑定签发时刻配置指纹——Provider 停用或
        // appId/secret/身份模式/企业标识变化后，未兑换票据不得再签发新会话
        // （配置变更期间不以旧配置建立会话；重新发起授权即可恢复）
        String currentDigest = ssoAuthService.currentConfigDigestFor(session.provider(), session.tenantId());
        if (currentDigest == null || !currentDigest.equals(session.configDigest())) {
            ssoAuthService.auditRejection(session.provider(), "LOGIN_FAILED",
                    currentDigest == null ? "provider disabled at ticket exchange"
                            : "config changed at ticket exchange");
            return R.failResolved(401, SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED,
                LocalizedMessages.text(SystemErrorKeys.SSO_LOGIN_NOT_COMPLETED, SSO_LOGIN_FAILED_MSG), EventRef.current());
        }
        // 免认证兑换路径无登录态：租户拦截器 fail-closed 会拒绝生成过滤条件；
        // 租户语义由显式谓词承担（票据载荷 tenantId + 账号行 tenantId 一致性校验）
        com.sw.ck.system.entity.SysUser user;
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            user = sysUserService.getById(session.userId());
        }
        if (user == null || !java.util.Objects.equals(user.getTenantId(), session.tenantId())
                || (user.getStatus() != null && user.getStatus() != 0)) {
            return R.failResolved(401, SystemErrorKeys.SSO_ACCOUNT_UNAVAILABLE,
                LocalizedMessages.text(SystemErrorKeys.SSO_ACCOUNT_UNAVAILABLE, "账号已停用"), EventRef.current());
        }
        String accessToken = jwtTokenProvider.generateToken(user.getId());
        String refreshToken = refreshTokenService.createRefreshToken(
                user.getId(), user.getTenantId(), jwtProperties.getRefreshExpireSeconds());
        com.sw.ck.system.util.CookieUtils.setRefreshCookie(response, refreshToken,
                (int) jwtProperties.getRefreshExpireSeconds(), cookieSecure, cookiePath);
        log.info("SSO 登录成功: userId={}, provider-ticket", user.getId());
        return R.ok(new TokenResponse(accessToken, jwtProperties.getAccessExpireSeconds()));
    }

    /**
     * 绑定候选票据兑换、候选确认与直连绑定端点已随 B 端手机号准入收敛移除
     * （sso-admin-config §一）：绑定只在回调准入链内按可信手机号完成，历史
     * 绑定数据与审计保留；旧候选/手动绑定路径不得绕过准入规则。
     */

    /** SSO 审计查询（I5 复验 G7）：权限守卫 + 租户隔离；无权与跨租户查询被拒绝。 */
    @org.springframework.security.access.prepost.PreAuthorize("@ss.hasPermi('system:sso:audit:query')")
    @GetMapping("/audit")
    public R<Map<String, Object>> audit(@RequestParam(value = "provider", required = false) String provider,
                                        @RequestParam(value = "eventType", required = false) String eventType,
                                        @RequestParam(value = "result", required = false) String result,
                                        @RequestParam(value = "localUserId", required = false) Long localUserId,
                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                        @RequestParam(value = "size", defaultValue = "20") int size) {
        return R.ok(ssoAuthService.queryAudit(provider, eventType, result, localUserId, page, size));
    }

    /** 当前用户绑定状态（个人中心）。 */
    @GetMapping("/bindings")
    public R<Map<String, Object>> bindings() {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            return R.failResolved(401, SystemErrorKeys.SESSION_REQUIRED,
                LocalizedMessages.text(SystemErrorKeys.SESSION_REQUIRED, "未登录"), EventRef.current());
        }
        return R.ok(ssoAuthService.listBindings(current.getUserId()));
    }

    /** 解绑（携带当前 access token：解绑触发该 token 的会话撤销——I5 §3.2）。 */
    @PostMapping("/unbind")
    public R<Void> unbind(@RequestBody BindRequest request,
                          @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String authorization) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            return R.failResolved(401, SystemErrorKeys.SESSION_REQUIRED,
                LocalizedMessages.text(SystemErrorKeys.SESSION_REQUIRED, "未登录"), EventRef.current());
        }
        try {
            ssoAuthService.unbind(request.provider(), current.getUserId(), bearerToken(authorization));
        } catch (SsoRejectionException e) {
            log.warn("SSO 解绑被拒: eventRef={} errorKey={} detail={}",
                    EventRef.current(), e.getErrorKey(), e.getMessage());
            return R.failResolved(400, e.getErrorKey(), e.getMessage(), EventRef.current());
        } catch (RuntimeException e) {
            log.warn("SSO 解绑失败: eventRef={} detail={}", EventRef.current(), e.getMessage(), e);
            return R.failResolved(400, SystemErrorKeys.SSO_BINDING_NOT_FOUND,
                    "解绑未能完成，请稍后重试或联系管理员处理", EventRef.current());
        }
        return R.ok();
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length());
    }

    private String safeRedirect(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/") || path.startsWith("//")) {
            return "/workspace";
        }
        return path;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record BindRequest(String provider, String externalId) {
    }
}
