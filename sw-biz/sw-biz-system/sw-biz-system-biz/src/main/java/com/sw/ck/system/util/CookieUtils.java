package com.sw.ck.system.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * httpOnly cookie 工具 — 仅用于 refresh token 的 Set-Cookie 操作。
 * <p>
 * 所有 cookie 均设置 httpOnly=true（JS 不可读）、可配置 Path（P45：必须覆盖当前公开
 * refresh/logout URL 的浏览器可见最小路径，生产按公开 API 前缀如 /sw-server/api/ 配置，
 * 不再硬编码内部路径假设）、SameSite 策略（Secure=true → Strict, Secure=false → Lax）。
 */
public final class CookieUtils {

    /** Refresh token cookie 名称 */
    public static final String REFRESH_COOKIE_NAME = "rt";
    /** 开发默认 Refresh token cookie 路径（生产经 sw.security.cookie.path 覆盖） */
    public static final String DEFAULT_REFRESH_COOKIE_PATH = "/api/auth/";
    /** Refresh token 默认 Max-Age（秒）= 7 天 */
    public static final int REFRESH_MAX_AGE = 604800;

    private CookieUtils() {
        // 工具类不可实例化
    }

    /**
     * 设置 refresh token httpOnly cookie。
     *
     * @param response HTTP 响应
     * @param token    原始 refresh token 字符串（非 hash）
     * @param maxAge   过期秒数，传 0 或负数使用默认 7 天
     * @param secure   是否设置 Secure 属性（生产环境 true，开发期 false）
     * @param path     浏览器可见 cookie Path（须覆盖公开 refresh/logout URL 最小前缀）
     */
    public static void setRefreshCookie(HttpServletResponse response, String token, int maxAge,
                                        boolean secure, String path) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath(path != null && !path.isBlank() ? path : DEFAULT_REFRESH_COOKIE_PATH);
        cookie.setAttribute("SameSite", secure ? "Strict" : "Lax");
        cookie.setMaxAge(maxAge > 0 ? maxAge : REFRESH_MAX_AGE);
        response.addCookie(cookie);
    }

    /**
     * 清除 refresh token cookie（logout / token 撤销时调用）。
     * 设置 Max-Age=0 + 空值，浏览器立即删除。清除必须使用与设置时相同的 Path。
     */
    public static void clearRefreshCookie(HttpServletResponse response, String path) {
        Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true); // 清除时 Secure 属性不影响删除
        cookie.setPath(path != null && !path.isBlank() ? path : DEFAULT_REFRESH_COOKIE_PATH);
        cookie.setAttribute("SameSite", "Strict");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    /**
     * 从请求中读取 refresh token cookie。
     *
     * @param request HTTP 请求
     * @return cookie 值（原始 refresh token），无此 cookie 时返回 null
     */
    public static String getRefreshTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
