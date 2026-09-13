package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.model.TokenResponse;
import com.sw.ck.system.sso.SsoAuthService;
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

    private final SsoAuthService ssoAuthService;
    private final SsoTicketStore ticketStore;
    private final com.sw.ck.system.service.SysUserService sysUserService;
    private final com.sw.ck.security.jwt.JwtTokenProvider jwtTokenProvider;
    private final com.sw.ck.security.jwt.JwtProperties jwtProperties;
    private final com.sw.ck.system.service.RefreshTokenService refreshTokenService;
    @org.springframework.beans.factory.annotation.Value("${sw.security.cookie.secure:false}")
    private boolean cookieSecure;
    @org.springframework.beans.factory.annotation.Value("${sw.security.cookie.path:/api/auth/}")
    private String cookiePath;

    public SsoAuthController(SsoAuthService ssoAuthService,
                             SsoTicketStore ticketStore,
                             com.sw.ck.system.service.SysUserService sysUserService,
                             com.sw.ck.security.jwt.JwtTokenProvider jwtTokenProvider,
                             com.sw.ck.security.jwt.JwtProperties jwtProperties,
                             com.sw.ck.system.service.RefreshTokenService refreshTokenService) {
        this.ssoAuthService = ssoAuthService;
        this.ticketStore = ticketStore;
        this.sysUserService = sysUserService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
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
     * 无既有登录态时以显式 tenant 参数确定租户：服务端校验租户有效性与该租户
     * Provider 配置启用后才签发 state；不授予任何权限。
     */
    @GetMapping("/{provider}/authorize-login")
    public R<Map<String, Object>> authorizeLogin(@PathVariable("provider") String provider,
                                                 @RequestParam(value = "tenant", required = false) Long tenant,
                                                 @RequestParam(value = "redirect", required = false) String redirect) {
        try {
            SsoAuthService.AuthorizeStart start = ssoAuthService.startAuthorizeLogin(provider, tenant, redirect);
            return R.ok(Map.of(
                    "authorizeUrl", start.authorizeUrl(),
                    "state", start.state()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            // fail closed 必须返回可判定结果（不可 500/不可栈泄漏；不回显任何敏感值）
            log.warn("SSO 登录前发起被拒: provider={}, reason={}", provider, e.getMessage());
            return R.fail(400, e.getMessage());
        }
    }

    /**
     * 服务端回调/换票（免认证白名单）。校验一次性 state → code 换外部身份 →
     * 定位绑定。已绑定：302 回跳（携带一次性 ticket）；未绑定：302 到同源绑定
     * 确认页（携带一次性候选 ticket，不携带 code/state）。
     */
    @GetMapping("/{provider}/callback")
    public Object callback(@PathVariable("provider") String provider,
                           @RequestParam(value = "code", required = false) String code,
                           @RequestParam(value = "state", required = false) String state) {
        try {
            SsoAuthService.CallbackResult result = ssoAuthService.handleCallback(provider, code, state);
            if (result.bound()) {
                String ticket = ticketStore.issue(result.userId(), result.tenantId());
                String target = safeRedirect(result.redirectPath())
                        + (safeRedirect(result.redirectPath()).contains("?") ? "&" : "?")
                        + "sso_ticket=" + urlEncode(ticket);
                return java.util.Map.of("redirect", target);
            }
            String ticket = ticketStore.issueCandidate(result.externalId(), result.tenantId(), result.provider());
            return java.util.Map.of("redirect", "/sso/bind?ticket=" + urlEncode(ticket));
        } catch (IllegalStateException | IllegalArgumentException | com.sw.ck.system.sso.SsoProviderClient.SsoProviderException e) {
            return deny(provider, e);
        }
    }

    private R<Void> deny(String provider, Exception e) {
        // 回调拒绝返回可判定结果：不回显 code/state 原文，不泄漏栈
        log.warn("SSO 回调被拒: provider={}, reason={}", provider, e.getMessage());
        return R.fail(400, "SSO 回调被拒绝: " + e.getMessage());
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
            return R.fail(401, "登录票据无效或已过期");
        }
        com.sw.ck.system.entity.SysUser user = sysUserService.getById(session.userId());
        if (user == null || (user.getStatus() != null && user.getStatus() != 0)) {
            return R.fail(401, "账号已停用");
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
     * 绑定候选票据兑换（免认证白名单）：返回 Provider 与外部标识摘要前 8 位；
     * 原文 externalId 不出服务端（绑定动作仍用同一候选票据原子消费）。
     */
    @PostMapping("/candidate")
    public R<Map<String, Object>> candidate(@RequestBody Map<String, String> body) {
        SsoTicketStore.ConsumedCandidate candidate = ticketStore.peekCandidate(body.get("ticket"));
        if (candidate == null) {
            return R.fail(401, "绑定票据无效或已过期");
        }
        return R.ok(Map.of(
                "provider", candidate.provider(),
                "externalDigestPrefix", com.sw.ck.system.sso.SsoAuthService.digestPrefix(candidate.externalId())));
    }

    /**
     * 绑定候选确认（需认证）：消费候选票据并完成绑定（冲突/重复显式拒绝）。
     */
    @PostMapping("/bind-candidate")
    public R<Void> bindCandidate(@RequestBody Map<String, String> body) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            return R.fail(401, "未登录");
        }
        SsoTicketStore.ConsumedCandidate candidate = ticketStore.consumeCandidate(body.get("ticket"));
        if (candidate == null) {
            return R.fail(401, "绑定票据无效或已过期");
        }
        if (!current.getTenantId().equals(candidate.tenantId())) {
            return R.fail(403, "绑定候选与当前租户不匹配");
        }
        try {
            ssoAuthService.bind(candidate.provider(), current.getUserId(), candidate.externalId());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("SSO 候选绑定被拒: reason={}", e.getMessage());
            return R.fail(400, e.getMessage());
        }
        return R.ok();
    }

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
            return R.fail(401, "未登录");
        }
        return R.ok(ssoAuthService.listBindings(current.getUserId()));
    }

    /** 绑定（请求体携带外部身份候选 ticket 换取的外部标识；服务端校验冲突）。 */
    @PostMapping("/bind")
    public R<Void> bind(@RequestBody BindRequest request) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            return R.fail(401, "未登录");
        }
        try {
            ssoAuthService.bind(request.provider(), current.getUserId(), request.externalId());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("SSO 绑定被拒: reason={}", e.getMessage());
            return R.fail(400, e.getMessage());
        }
        return R.ok();
    }

    /** 解绑。 */
    @PostMapping("/unbind")
    public R<Void> unbind(@RequestBody BindRequest request) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            return R.fail(401, "未登录");
        }
        try {
            ssoAuthService.unbind(request.provider(), current.getUserId());
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("SSO 解绑被拒: reason={}", e.getMessage());
            return R.fail(400, e.getMessage());
        }
        return R.ok();
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
