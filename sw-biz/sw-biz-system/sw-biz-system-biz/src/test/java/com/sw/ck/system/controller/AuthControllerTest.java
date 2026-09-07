package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.model.TokenResponse;
import com.sw.ck.system.security.LoginChallengeService;
import com.sw.ck.system.security.LoginChallengeStore;
import com.sw.ck.system.security.LoginSecurityProperties;
import com.sw.ck.system.security.RsaLoginKeyManager;
import com.sw.ck.system.service.RefreshTokenService;
import com.sw.ck.system.service.SysUserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sw.ck.system.controller.LoginChallengeTestSupport.TEST_PRIVATE_KEY_PEM;
import static com.sw.ck.system.controller.LoginChallengeTestSupport.encryptPassword;

/**
 * {@link AuthController} 单元测试（P45 固定校验顺序）。
 * <p>
 * 覆盖登录链四类前置分支及其顺序、挑战一次性消费、账号状态与 refresh 轮换回归。
 * 挑战权威状态使用内存测试替身（与 Redis 实现相同的原子 consume 语义）。
 */
class AuthControllerTest {

    private static final int CAPTCHA_ERROR = 2101;
    private static final int CAPTCHA_EXPIRED = 2102;
    private static final int CLIENT_TIME_ABNORMAL = 2103;
    private static final int PASSWORD_ERROR = 2104;

    private final SysUserService sysUserService = mock(SysUserService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final UserDetailsProvider userDetailsProvider = mock(UserDetailsProvider.class);
    private final JwtProperties jwtProperties = mock(JwtProperties.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final LoginUserLoader loginUserLoader = mock(LoginUserLoader.class);
    private final LoginChallengeTestSupport.InMemoryLoginChallengeStore challengeStore =
            new LoginChallengeTestSupport.InMemoryLoginChallengeStore();

    private final MockHttpServletResponse mockResponse = new MockHttpServletResponse();
    private final RsaLoginKeyManager keyManager = new RsaLoginKeyManager(testProps());
    private final LoginChallengeTestSupport.TestableLoginChallengeService challengeService =
            new LoginChallengeTestSupport.TestableLoginChallengeService(
                    challengeStore, keyManager, testProps(),
                    new com.sw.ck.system.security.PngCaptchaRenderer());
    private final AuthController controller = new AuthController(
            userDetailsProvider, passwordEncoder, jwtTokenProvider, sysUserService,
            jwtProperties, refreshTokenService, loginUserLoader,
            challengeService, keyManager);

    private LoginSecurityProperties testProps() {
        LoginSecurityProperties props = new LoginSecurityProperties();
        props.setRsaPrivateKey(TEST_PRIVATE_KEY_PEM);
        props.setRsaKeyVersion("v1");
        props.setDigestSecret("test-digest-secret");
        return props;
    }

    @BeforeEach
    void setUp() {
        when(jwtProperties.getAccessExpireSeconds()).thenReturn(900L);
        when(jwtProperties.getRefreshExpireSeconds()).thenReturn(604800L);
        when(refreshTokenService.createRefreshToken(anyLong(), anyLong(), anyLong()))
                .thenReturn("test-refresh-token-raw-64-chars-hex");
        challengeStore.records.clear();
        challengeStore.failures.clear();
    }

    /** 签发挑战并提交登录（全链：挑战 → 五字段请求） */
    private R<TokenResponse> loginViaChallenge(String username, String rawPassword, String captchaOverride,
                                               String timestampOverride) {
        LoginChallengeService.ChallengeView challenge = controller.challenge().getData();
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername(username);
        request.setPassword(encryptPassword(rawPassword));
        request.setCaptcha(captchaOverride != null ? captchaOverride : challengeService.lastCaptchaCode());
        request.setCaptchaId(challenge.captchaId());
        request.setTimestamp(timestampOverride != null ? timestampOverride : String.valueOf(System.currentTimeMillis()));
        return controller.login(request, mockResponse);
    }

    private SysUser activeUser(String username, String rawPassword) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setTenantId(0L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setStatus(0);
        return user;
    }

    // ==================== Happy path ====================

    @Test
    @DisplayName("Happy path：挑战通过 + 密码正确 → code=0 + TokenResponse + rt cookie")
    void login_withValidCredentials_shouldReturnToken() {
        when(sysUserService.getByUsername("admin")).thenReturn(activeUser("admin", "admin123"));
        when(jwtTokenProvider.generateToken(1L)).thenReturn("test-jwt-token");

        R<TokenResponse> result = loginViaChallenge("admin", "admin123", null, null);

        assertThat(result.getCode()).as("成功码应为 0").isZero();
        assertThat(result.getData()).as("TokenResponse 不应为 null").isNotNull();
        assertThat(result.getData().getAccessToken()).isEqualTo("test-jwt-token");
        assertThat(result.getData().getExpiresIn()).isEqualTo(900);
        assertThat(mockResponse.getCookie("rt")).as("应设置 rt cookie").isNotNull();
        assertThat(mockResponse.getCookie("rt").getValue()).isEqualTo("test-refresh-token-raw-64-chars-hex");
    }

    // ==================== 固定校验顺序：四类分支 ====================

    @Test
    @DisplayName("验证码内容错误 → 2101 验证码错误，不进入密码认证")
    void login_withWrongCaptcha_shouldReturnCaptchaError() {
        R<TokenResponse> result = loginViaChallenge("admin", "admin123", "zzzz", null);

        assertThat(result.getCode()).isEqualTo(CAPTCHA_ERROR);
        assertThat(result.getMsg()).isEqualTo("验证码错误");
        assertThat(mockResponse.getCookie("rt")).isNull();
        // 挑战仍在（内容错误允许同一挑战内有限重试）
        assertThat(challengeStore.records).hasSize(1);
    }

    @Test
    @DisplayName("验证码 UUID 不存在 → 2101 验证码错误")
    void login_withUnknownCaptchaId_shouldReturnCaptchaError() {
        R<TokenResponse> result = loginViaChallenge("admin", "admin123", null, null);
        // 直接用不存在的 UUID 再次提交（模拟伪造 UUID）
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("admin");
        request.setPassword(encryptPassword("admin123"));
        request.setCaptcha("aaaa");
        request.setCaptchaId("not-exist-uuid");
        request.setTimestamp(String.valueOf(System.currentTimeMillis()));
        R<TokenResponse> replay = controller.login(request, mockResponse);

        assertThat(result.getCode()).isNotZero();
        assertThat(replay.getCode()).isEqualTo(CAPTCHA_ERROR);
    }

    @Test
    @DisplayName("验证码内容匹配但挑战过期（>5 分钟）→ 2102 验证码已过期")
    void login_withExpiredChallenge_shouldReturnCaptchaExpired() {
        LoginChallengeService.ChallengeView challenge = controller.challenge().getData();
        // 将挑战生成时间回拨 6 分钟（内容摘要不变）
        LoginChallengeStore.LoginChallengeRecord original = challengeStore.records.get(challenge.captchaId());
        challengeStore.records.put(challenge.captchaId(), new LoginChallengeStore.LoginChallengeRecord(
                original.captchaDigest(), original.keyVersion(), System.currentTimeMillis() - 360_000));

        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("admin");
        request.setPassword(encryptPassword("admin123"));
        request.setCaptcha(challengeService.lastCaptchaCode());
        request.setCaptchaId(challenge.captchaId());
        request.setTimestamp(String.valueOf(System.currentTimeMillis()));
        R<TokenResponse> result = controller.login(request, mockResponse);

        assertThat(result.getCode()).isEqualTo(CAPTCHA_EXPIRED);
        assertThat(result.getMsg()).isEqualTo("验证码已过期");
        // 过期挑战已作废
        assertThat(challengeStore.records).isEmpty();
    }

    @Test
    @DisplayName("记录保留期结束后 → 2101 按挑战不存在处理")
    void login_beyondRetention_shouldReturnCaptchaError() {
        LoginChallengeService.ChallengeView challenge = controller.challenge().getData();
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("admin");
        request.setPassword(encryptPassword("admin123"));
        request.setCaptcha(challengeService.lastCaptchaCode());
        request.setCaptchaId(challenge.captchaId());
        request.setTimestamp(String.valueOf(System.currentTimeMillis()));
        // 内存替身不模拟 TTL 消亡：删除记录等价于真实 Redis 保留期（600s）过后记录已消亡
        challengeStore.records.remove(challenge.captchaId());
        R<TokenResponse> gone = controller.login(request, mockResponse);
        assertThat(gone.getCode()).isEqualTo(CAPTCHA_ERROR);
        assertThat(gone.getMsg()).isEqualTo("验证码错误");
    }

    @Test
    @DisplayName("客户端时间超窗 → 2103 机器时间异常，且不进入密码认证")
    void login_withSkewedClientTime_shouldReturnTimeAbnormal() {
        when(sysUserService.getByUsername("admin")).thenReturn(activeUser("admin", "admin123"));

        // 时间偏差 10 分钟（> 3 分钟窗口）
        String skewed = String.valueOf(System.currentTimeMillis() - 600_000);
        R<TokenResponse> result = loginViaChallenge("admin", "admin123", null, skewed);

        assertThat(result.getCode()).isEqualTo(CLIENT_TIME_ABNORMAL);
        assertThat(result.getMsg()).isEqualTo("机器时间异常");
        // 顺序证明：时间异常发生在消费之前，挑战仍在
        assertThat(challengeStore.records).hasSize(1);
        verify(sysUserService, never()).getByUsername(anyString());
    }

    @Test
    @DisplayName("密码错误 → 2104 密码错误，且挑战已被消费")
    void login_withWrongPassword_shouldReturnPasswordError() {
        when(sysUserService.getByUsername("admin")).thenReturn(activeUser("admin", "admin123"));

        R<TokenResponse> result = loginViaChallenge("admin", "wrong-password", null, null);

        assertThat(result.getCode()).isEqualTo(PASSWORD_ERROR);
        assertThat(result.getMsg()).isEqualTo("密码错误");
        // 顺序证明：密码认证发生在消费之后，挑战已消费
        assertThat(challengeStore.records).isEmpty();
    }

    @Test
    @DisplayName("用户不存在 → 2104 密码错误（统一语义，不暴露账号差异）")
    void login_withUnknownUser_shouldReturnPasswordError() {
        when(sysUserService.getByUsername("unknown")).thenReturn(null);

        R<TokenResponse> result = loginViaChallenge("unknown", "any-password", null, null);

        assertThat(result.getCode()).isEqualTo(PASSWORD_ERROR);
        assertThat(result.getMsg()).isEqualTo("密码错误");
    }

    @Test
    @DisplayName("非法密文 → 2104 密码错误（不降级明文）")
    void login_withInvalidCiphertext_shouldReturnPasswordError() {
        LoginChallengeService.ChallengeView challenge = controller.challenge().getData();
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("admin");
        request.setPassword("not-a-rsa-ciphertext");
        request.setCaptcha(challengeService.lastCaptchaCode());
        request.setCaptchaId(challenge.captchaId());
        request.setTimestamp(String.valueOf(System.currentTimeMillis()));

        R<TokenResponse> result = controller.login(request, mockResponse);

        assertThat(result.getCode()).isEqualTo(PASSWORD_ERROR);
    }

    // ==================== 一次性消费 / 防重放 ====================

    @Test
    @DisplayName("相同挑战重复提交 → 第二次 2101，不得再次进入密码认证")
    void login_replaySameChallenge_shouldBeRejected() {
        when(sysUserService.getByUsername("admin")).thenReturn(activeUser("admin", "admin123"));
        when(jwtTokenProvider.generateToken(1L)).thenReturn("test-jwt-token");

        LoginChallengeService.ChallengeView challenge = controller.challenge().getData();
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("admin");
        request.setPassword(encryptPassword("admin123"));
        request.setCaptcha(challengeService.lastCaptchaCode());
        request.setCaptchaId(challenge.captchaId());
        request.setTimestamp(String.valueOf(System.currentTimeMillis()));

        R<TokenResponse> first = controller.login(request, mockResponse);
        R<TokenResponse> second = controller.login(request, mockResponse);

        assertThat(first.getCode()).as("首次提交应成功").isZero();
        assertThat(second.getCode()).as("重复提交应被拒绝").isEqualTo(CAPTCHA_ERROR);
        verify(jwtTokenProvider, org.mockito.Mockito.times(1)).generateToken(1L);
    }

    // ==================== 账号状态 ====================

    @Test
    @DisplayName("停用用户（status=1）→ 401 账号已停用，不下发 cookie")
    void login_withDisabledUser_shouldReturnFailure() {
        SysUser user = activeUser("disabled", "correct-password");
        user.setStatus(1);
        when(sysUserService.getByUsername("disabled")).thenReturn(user);

        R<TokenResponse> result = loginViaChallenge("disabled", "correct-password", null, null);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMsg()).isEqualTo("账号已停用");
        assertThat(mockResponse.getCookie("rt")).isNull();
    }

    @Test
    @DisplayName("锁定用户（status=2）→ 401 账号已锁定")
    void login_withLockedUser_shouldReturnFailure() {
        SysUser user = activeUser("locked", "correct-password");
        user.setStatus(2);
        when(sysUserService.getByUsername("locked")).thenReturn(user);

        R<TokenResponse> result = loginViaChallenge("locked", "correct-password", null, null);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMsg()).isEqualTo("账号已锁定");
    }

    // ==================== Cookie Path（P45 第一断点回归） ====================

    @Test
    @DisplayName("生产前缀路径下 cookie Path 覆盖 /sw-server/api/auth/")
    void login_prodPath_shouldSetCookiePathToPublicPrefix() throws Exception {
        java.lang.reflect.Field pathField = AuthController.class.getDeclaredField("cookiePath");
        pathField.setAccessible(true);
        pathField.set(controller, "/sw-server/api/auth/");
        java.lang.reflect.Field secureField = AuthController.class.getDeclaredField("cookieSecure");
        secureField.setAccessible(true);
        secureField.set(controller, true);

        when(sysUserService.getByUsername("admin")).thenReturn(activeUser("admin", "admin123"));
        when(jwtTokenProvider.generateToken(1L)).thenReturn("test-jwt-token");

        R<TokenResponse> result = loginViaChallenge("admin", "admin123", null, null);

        assertThat(result.getCode()).isZero();
        Cookie cookie = mockResponse.getCookie("rt");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).as("cookie Path 必须覆盖公开 API 前缀").isEqualTo("/sw-server/api/auth/");
        assertThat(cookie.getSecure()).as("生产应带 Secure").isTrue();
        assertThat(cookie.isHttpOnly()).as("必须 HttpOnly").isTrue();
    }

    // ==================== refresh 回归 ====================

    @Test
    @DisplayName("停用用户 refresh → 401 + 账号已停用 + 新轮换 token 已撤销 + cookie 已清除")
    void refresh_withDisabledUser_shouldRejectAndRevokeNewToken() {
        String newRawToken = "new-refresh-token-raw-64-chars-hex";
        when(refreshTokenService.rotateRefreshToken(anyString(), anyLong()))
                .thenReturn(new RefreshTokenService.RefreshTokenRotation(1L, 0L, newRawToken));
        SysUser user = new SysUser();
        user.setId(1L);
        user.setStatus(1);
        when(sysUserService.getById(1L)).thenReturn(user);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setCookies(new Cookie("rt", "old-refresh-token-raw-64-chars-hex"));

        R<TokenResponse> result = controller.refresh(servletRequest, mockResponse);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMsg()).isEqualTo("账号已停用");
        verify(refreshTokenService).revokeRefreshToken(newRawToken);
        assertThat(mockResponse.getCookie("rt")).isNotNull();
        assertThat(mockResponse.getCookie("rt").getValue()).isEmpty();
        assertThat(mockResponse.getCookie("rt").getMaxAge()).isZero();
    }

    @Test
    @DisplayName("锁定用户 refresh → 401 + 账号已锁定 + 新轮换 token 已撤销")
    void refresh_withLockedUser_shouldRejectAndRevokeNewToken() {
        String newRawToken = "new-refresh-token-raw-64-chars-hex";
        when(refreshTokenService.rotateRefreshToken(anyString(), anyLong()))
                .thenReturn(new RefreshTokenService.RefreshTokenRotation(1L, 0L, newRawToken));
        SysUser user = new SysUser();
        user.setId(1L);
        user.setStatus(2);
        when(sysUserService.getById(1L)).thenReturn(user);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setCookies(new Cookie("rt", "old-refresh-token-raw-64-chars-hex"));

        R<TokenResponse> result = controller.refresh(servletRequest, mockResponse);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMsg()).isEqualTo("账号已锁定");
        verify(refreshTokenService).revokeRefreshToken(newRawToken);
    }

    @Test
    @DisplayName("refresh 用户已不存在（逻辑删除）→ 401 + 账号已停用 + 新轮换 token 已撤销")
    void refresh_withDeletedUser_shouldRejectAndRevokeNewToken() {
        String newRawToken = "new-refresh-token-raw-64-chars-hex";
        when(refreshTokenService.rotateRefreshToken(anyString(), anyLong()))
                .thenReturn(new RefreshTokenService.RefreshTokenRotation(1L, 0L, newRawToken));
        when(sysUserService.getById(1L)).thenReturn(null);

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setCookies(new Cookie("rt", "old-refresh-token-raw-64-chars-hex"));

        R<TokenResponse> result = controller.refresh(servletRequest, mockResponse);

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getMsg()).isEqualTo("账号已停用");
        verify(refreshTokenService).revokeRefreshToken(newRawToken);
    }

    @Test
    @DisplayName("正常用户 refresh → 新 access token + 新 refresh cookie，不撤销新 token")
    void refresh_withActiveUser_shouldReturnNewTokens() {
        String newRawToken = "new-refresh-token-raw-64-chars-hex";
        when(refreshTokenService.rotateRefreshToken(anyString(), anyLong()))
                .thenReturn(new RefreshTokenService.RefreshTokenRotation(1L, 0L, newRawToken));
        SysUser user = new SysUser();
        user.setId(1L);
        user.setStatus(0);
        when(sysUserService.getById(1L)).thenReturn(user);
        when(jwtTokenProvider.generateToken(1L)).thenReturn("test-jwt-token-refreshed");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setCookies(new Cookie("rt", "old-refresh-token-raw-64-chars-hex"));

        R<TokenResponse> result = controller.refresh(servletRequest, mockResponse);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getAccessToken()).isEqualTo("test-jwt-token-refreshed");
        assertThat(result.getData().getExpiresIn()).isEqualTo(900);
        assertThat(mockResponse.getCookie("rt").getValue()).isEqualTo(newRawToken);
        verify(refreshTokenService, never()).revokeRefreshToken(anyString());
    }
}
