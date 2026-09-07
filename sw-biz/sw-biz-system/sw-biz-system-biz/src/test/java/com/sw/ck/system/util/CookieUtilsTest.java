package com.sw.ck.system.util;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CookieUtils 测试")
class CookieUtilsTest {

    // ============ setRefreshCookie ============

    @Test
    @DisplayName("setRefreshCookie：设置正确 cookie 属性")
    void setRefreshCookie_shouldSetCorrectAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtils.setRefreshCookie(response, "test-token-value", 900, false, CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);

        Cookie cookie = response.getCookie(CookieUtils.REFRESH_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("test-token-value");
        assertThat(cookie.isHttpOnly()).as("HttpOnly 必须为 true").isTrue();
        assertThat(cookie.getSecure()).as("secure=false 时不应设 Secure").isFalse();
        assertThat(cookie.getPath()).isEqualTo(CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);
        assertThat(cookie.getMaxAge()).isEqualTo(900);
        // SameSite 通过 getAttribute 验证
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    @DisplayName("setRefreshCookie：secure=true → SameSite=Strict + Secure=true")
    void setRefreshCookie_secureTrue_shouldSetStrictAndSecure() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtils.setRefreshCookie(response, "secure-token", 3600, true, CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);

        Cookie cookie = response.getCookie(CookieUtils.REFRESH_COOKIE_NAME);
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Strict");
    }

    @Test
    @DisplayName("setRefreshCookie：maxAge ≤ 0 使用默认 7 天")
    void setRefreshCookie_zeroMaxAge_shouldUseDefault() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtils.setRefreshCookie(response, "default-age-token", 0, false, CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);

        Cookie cookie = response.getCookie(CookieUtils.REFRESH_COOKIE_NAME);
        assertThat(cookie.getMaxAge()).isEqualTo(CookieUtils.REFRESH_MAX_AGE);
    }

    // ============ clearRefreshCookie ============

    @Test
    @DisplayName("clearRefreshCookie：设置 Max-Age=0 + 空值")
    void clearRefreshCookie_shouldClearCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CookieUtils.clearRefreshCookie(response, CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);

        Cookie cookie = response.getCookie(CookieUtils.REFRESH_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo(CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);
    }

    // ============ getRefreshTokenFromCookie ============

    @Test
    @DisplayName("getRefreshTokenFromCookie：正常读取 cookie 值")
    void getRefreshTokenFromCookie_shouldReturnValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Cookie cookie = new Cookie(CookieUtils.REFRESH_COOKIE_NAME, "my-refresh-token");
        cookie.setPath(CookieUtils.DEFAULT_REFRESH_COOKIE_PATH);
        request.setCookies(cookie);

        assertThat(CookieUtils.getRefreshTokenFromCookie(request))
                .isEqualTo("my-refresh-token");
    }

    @Test
    @DisplayName("getRefreshTokenFromCookie：无 cookie → 返回 null")
    void getRefreshTokenFromCookie_noCookies_shouldReturnNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(CookieUtils.getRefreshTokenFromCookie(request)).isNull();
    }

    @Test
    @DisplayName("getRefreshTokenFromCookie：cookies 存在但无 rt → 返回 null")
    void getRefreshTokenFromCookie_noRtCookie_shouldReturnNull() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Cookie other = new Cookie("other-cookie", "value");
        request.setCookies(other);

        assertThat(CookieUtils.getRefreshTokenFromCookie(request)).isNull();
    }

    @Test
    @DisplayName("getRefreshTokenFromCookie：多个 cookie 中找到 rt")
    void getRefreshTokenFromCookie_multipleCookies_shouldFindRt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Cookie a = new Cookie("a", "1");
        Cookie rt = new Cookie(CookieUtils.REFRESH_COOKIE_NAME, "target-token");
        Cookie b = new Cookie("b", "2");
        request.setCookies(a, rt, b);

        assertThat(CookieUtils.getRefreshTokenFromCookie(request))
                .isEqualTo("target-token");
    }

    // ============ 常量验证 ============

    @Test
    @DisplayName("CookieUtils 常量：rt + /api/auth/ + 604800")
    void constants_shouldMatchContract() {
        assertThat(CookieUtils.REFRESH_COOKIE_NAME).isEqualTo("rt");
        assertThat(CookieUtils.DEFAULT_REFRESH_COOKIE_PATH).isEqualTo("/api/auth/");
        assertThat(CookieUtils.REFRESH_MAX_AGE).isEqualTo(604800);
    }
}
