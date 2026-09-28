package com.sw.ck.system.sso;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider 回调白名单策略（I5 复验 G5）。
 * <p>
 * 与 Spring Security {@code permit-urls}（传输层匿名放行）职责分离：本策略约束
 * 「服务端向 Provider 声明的 redirect_uri / 对外通告的回调地址」必须落在配置的
 * 受控回调白名单内；未配置 base-url 时保持相对路径行为（由同源前端代理），
 * 配置了 base-url 而不在白名单内则 fail closed。
 * </p>
 */
@Service
public class SsoCallbackPolicy {

    /** 服务端对外基址（https://host[/prefix]）；空 = 相对路径模式 */
    private final String callbackBaseUrl;

    /** 允许的回调 URL 前缀白名单（配置为空时仅允许相对路径模式） */
    private final List<String> callbackAllowlist;

    /** 前端 SPA 基路径（如 /sw）；空 = 前端与回调同根部署 */
    private final String frontendBasePath;

    public SsoCallbackPolicy(
            @Value("${sw.security.sso.callback-base-url:}") String callbackBaseUrl,
            @Value("${sw.security.sso.callback-allowlist:}") List<String> callbackAllowlist,
            @Value("${sw.security.sso.frontend-base-path:}") String frontendBasePath) {
        this.callbackBaseUrl = callbackBaseUrl == null ? "" : callbackBaseUrl.trim();
        this.callbackAllowlist = callbackAllowlist == null ? List.of()
                : callbackAllowlist.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
        this.frontendBasePath = frontendBasePath == null ? "" : frontendBasePath.trim();
    }

    /**
     * 合成同源前端回跳路径：先按安全规则校正 path（必须以 / 开头且非 //，否则取
     * fallback），再前置前端基路径（基路径非法时忽略，不放大为可绕过形态）。
     * 返回值始终是以 / 开头、无协议跳转歧义的站内路径，供 302 Location 使用。
     */
    public String resolveFrontendPath(String path, String fallback) {
        String safe = (path == null || path.isBlank() || !path.startsWith("/") || path.startsWith("//"))
                ? fallback
                : path;
        if (frontendBasePath.isEmpty() || !frontendBasePath.startsWith("/") || frontendBasePath.startsWith("//")) {
            return safe;
        }
        String base = frontendBasePath.endsWith("/")
                ? frontendBasePath.substring(0, frontendBasePath.length() - 1)
                : frontendBasePath;
        return base + safe;
    }

    /**
     * 解析指定 Provider 的服务端回调 URL；相对路径模式下返回以 / 开头的相对路径，
     * 绝对模式下拼 base-url 并校验白名单，不在白名单内抛 IllegalStateException。
     */
    public String resolveCallbackUrl(String provider) {
        String path = "/api/auth/sso/" + provider.toLowerCase() + "/callback";
        if (callbackBaseUrl.isEmpty()) {
            return path;
        }
        String url = callbackBaseUrl.endsWith("/")
                ? callbackBaseUrl.substring(0, callbackBaseUrl.length() - 1) + path
                : callbackBaseUrl + path;
        requireAllowed(url);
        return url;
    }

    /** 正向断言：回调 URL 必须命中白名单任一前缀。 */
    public void requireAllowed(String callbackUrl) {
        for (String allowed : callbackAllowlist) {
            if (callbackUrl.equals(allowed) || callbackUrl.startsWith(allowed)) {
                return;
            }
        }
        throw new IllegalStateException("回调地址不在受控白名单内: " + callbackUrl);
    }

    /** 相对路径模式（未配置对外基址）。 */
    public boolean isRelativeMode() {
        return callbackBaseUrl.isEmpty();
    }

    public List<String> allowlist() {
        return new ArrayList<>(callbackAllowlist);
    }
}
