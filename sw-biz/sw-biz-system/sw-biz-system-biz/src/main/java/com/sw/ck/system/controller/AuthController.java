package com.sw.ck.system.controller;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.response.R;
import com.sw.ck.security.cache.LoginUserLoader;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.security.jwt.JwtProperties;
import com.sw.ck.security.jwt.JwtTokenProvider;
import com.sw.ck.security.spi.UserDetailsProvider;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.model.TokenResponse;
import com.sw.ck.system.security.AuthErrorCode;
import com.sw.ck.system.security.LoginChallengeService;
import com.sw.ck.system.security.LoginChallengeStore;
import com.sw.ck.system.security.RsaLoginKeyManager;
import com.sw.ck.system.service.RefreshTokenService;
import com.sw.ck.system.service.SysUserService;
import com.sw.ck.system.util.CookieUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器：登录挑战、登录、刷新 token、登出。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserDetailsProvider userDetailsProvider;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserService sysUserService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final LoginUserLoader loginUserLoader;
    private final LoginChallengeService loginChallengeService;
    private final RsaLoginKeyManager rsaLoginKeyManager;

    @Value("${sw.security.cookie.secure:false}")
    private boolean cookieSecure;

    /** refresh cookie 的浏览器可见 Path；生产按公开 API 前缀配置（如 /sw-server/api/） */
    @Value("${sw.security.cookie.path:/api/auth/}")
    private String cookiePath;

    public AuthController(UserDetailsProvider userDetailsProvider,
                          PasswordEncoder passwordEncoder,
                          JwtTokenProvider jwtTokenProvider,
                          SysUserService sysUserService,
                          JwtProperties jwtProperties,
                          RefreshTokenService refreshTokenService,
                          LoginUserLoader loginUserLoader,
                          LoginChallengeService loginChallengeService,
                          RsaLoginKeyManager rsaLoginKeyManager) {
        this.userDetailsProvider = userDetailsProvider;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.sysUserService = sysUserService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
        this.loginUserLoader = loginUserLoader;
        this.loginChallengeService = loginChallengeService;
        this.rsaLoginKeyManager = rsaLoginKeyManager;
    }

    /**
     * 登录前置挑战（P45）：一次性签发验证码、RSA 公钥、密钥版本、有效期与服务器时间。
     * <p>
     * 验证码原文仅在本次响应中出现；服务端权威状态（摘要、密钥版本、生成时间、失败计数、
     * 消费状态）全部落在跨实例共享 Redis，TTL 默认 5 分钟。
     *
     * @return 验证码展示内容、验证码 UUID、Base64 SPKI 公钥、密钥版本、有效期秒数、服务器 epoch 毫秒
     */
    @GetMapping("/challenge")
    public R<LoginChallengeService.ChallengeView> challenge() {
        return R.ok(loginChallengeService.create());
    }

    /**
     * 账号密码登录（P45 固定校验顺序）。
     * <p>
     * 请求字段：账号、RSA-OAEP(SHA-256) 密文密码、验证码内容、客户端 epoch 毫秒 timestamp、
     * 验证码 UUID。服务端按固定顺序校验，前一步未通过时不得进入后续校验：
     * <ol>
     *   <li>验证码记录与内容 → “验证码错误”(2101)</li>
     *   <li>验证码有效期（5 分钟）→ “验证码已过期”(2102)</li>
     *   <li>客户端机器时间（±3 分钟）→ “机器时间异常”(2103)</li>
     *   <li>原子消费挑战（并发/重复提交最多一个通过）→ RSA 解密 + 账号密码认证；
     *       密文非法/解密失败/账号不存在/密码不匹配统一返回“密码错误”(2104)</li>
     * </ol>
     * 登录成功后同时下发 access token（响应体 data.accessToken）和
     * httpOnly refresh cookie（Set-Cookie: rt=...），前端按双 token 模式管理。
     */
    @PostMapping("/login")
    public R<TokenResponse> login(@RequestBody LoginRequest request,
                                   HttpServletResponse response) {
        // 1. 验证码记录与内容（UUID 缺失/挑战不存在/内容不匹配 → 验证码错误）
        LoginChallengeStore.LoginChallengeRecord record;
        try {
            record = loginChallengeService.verifyCaptcha(request.getCaptchaId(), request.getCaptcha());
        } catch (LoginChallengeService.AuthException e) {
            log.warn("登录前置校验失败(1): code={}, captchaIdHash={}", e.getCode(),
                    RsaLoginKeyManager.sha256Hex(String.valueOf(request.getCaptchaId())));
            return R.fail(e.getCode(), e.getMessage());
        }

        // 2. 验证码有效期（已在 verifyCaptcha 内按生成时间 + TTL 判定，异常同样收敛到 2102）
        //    （verifyCaptcha 抛出的 2102 已在上方 catch 返回，此处到达即内容匹配且未过期）

        // 3. 客户端机器时间（±3 分钟；timestamp 只用于时钟异常提示，不承担防重放）
        try {
            loginChallengeService.verifyClientTime(request.getTimestamp());
        } catch (LoginChallengeService.AuthException e) {
            log.warn("登录前置校验失败(3): code={}", e.getCode());
            return R.fail(e.getCode(), e.getMessage());
        }

        // 4. 原子消费挑战：并发/重复提交下最多一个请求进入密码认证；无论密码最终正确与否，
        //    挑战均已消费，下一次登录必须取得新挑战
        if (!loginChallengeService.consume(request.getCaptchaId())) {
            log.warn("登录挑战已被消费或并发落败, captchaIdHash={}",
                    RsaLoginKeyManager.sha256Hex(String.valueOf(request.getCaptchaId())));
            return R.fail(AuthErrorCode.CAPTCHA_ERROR.getCode(), AuthErrorCode.CAPTCHA_ERROR.getMessage());
        }

        // 5. RSA 解密（按挑战绑定的密钥版本；失败统一收敛“密码错误”，不暴露差异）
        String plainPassword;
        try {
            plainPassword = rsaLoginKeyManager.decrypt(record.keyVersion(), request.getPassword());
        } catch (RsaLoginKeyManager.PasswordDecryptException e) {
            log.warn("密码解密失败: {}", e.getMessage());
            return R.fail(AuthErrorCode.PASSWORD_ERROR.getCode(), AuthErrorCode.PASSWORD_ERROR.getMessage());
        }
        if (plainPassword.length() > rsaLoginKeyManager.maxPlaintextBytes()) {
            return R.fail(AuthErrorCode.PASSWORD_ERROR.getCode(), AuthErrorCode.PASSWORD_ERROR.getMessage());
        }

        // 6. 账号与密码认证（沿用既有语义：账号不存在/密码不匹配统一“密码错误”）
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return R.fail(AuthErrorCode.PASSWORD_ERROR.getCode(), AuthErrorCode.PASSWORD_ERROR.getMessage());
        }
        log.info("用户登录: {}", request.getUsername());
        SysUser user = sysUserService.getByUsername(request.getUsername());
        if (user == null) {
            return R.fail(AuthErrorCode.PASSWORD_ERROR.getCode(), AuthErrorCode.PASSWORD_ERROR.getMessage());
        }
        if (!passwordEncoder.matches(plainPassword, user.getPassword())) {
            return R.fail(AuthErrorCode.PASSWORD_ERROR.getCode(), AuthErrorCode.PASSWORD_ERROR.getMessage());
        }

        // 7. 校验账号状态（0=正常 1=停用 2=锁定；null/未知值按停用处理，拒绝签发 token）
        String statusDenyMessage = statusDenyMessage(user.getStatus());
        if (statusDenyMessage != null) {
            log.warn("用户 {} 登录被拒绝: {}", request.getUsername(), statusDenyMessage);
            return R.fail(401, statusDenyMessage);
        }

        // 8. 签发 access token
        String accessToken = jwtTokenProvider.generateToken(user.getId());

        // 9. 生成 refresh token → 写 DB + 设 httpOnly cookie
        String refreshToken = refreshTokenService.createRefreshToken(
                user.getId(), user.getTenantId(), jwtProperties.getRefreshExpireSeconds());
        CookieUtils.setRefreshCookie(response, refreshToken,
                (int) jwtProperties.getRefreshExpireSeconds(), cookieSecure, cookiePath);

        log.info("用户 {} 登录成功, userId={}", request.getUsername(), user.getId());
        return R.ok(new TokenResponse(accessToken, jwtProperties.getAccessExpireSeconds()));
    }

    /**
     * 刷新 access token（使用 refresh token cookie）。
     * <p>
     * 校验 refresh cookie → 轮换（旧撤销 + 新签发）→ 下发新 refresh cookie + 新 access token。
     * 检测到重放时撤销该用户全部会话。
     */
    @PostMapping("/refresh")
    public R<TokenResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        // 1. 从 cookie 读取 refresh token
        String rawToken = CookieUtils.getRefreshTokenFromCookie(request);
        if (rawToken == null || rawToken.isEmpty()) {
            return R.fail(401, "未提供 refresh token");
        }
        // 2. 校验 + 轮换
        try {
            RefreshTokenService.RefreshTokenRotation rotation =
                    refreshTokenService.rotateRefreshToken(rawToken, jwtProperties.getRefreshExpireSeconds());
            // 3. 重载用户并校验账号状态（停用/锁定/已删除账号不得续期：
            //    撤销刚轮换出的新 refresh token + 清除 cookie）
            SysUser user = sysUserService.getById(rotation.userId());
            String statusDenyMessage = user == null ? "账号已停用" : statusDenyMessage(user.getStatus());
            if (statusDenyMessage != null) {
                refreshTokenService.revokeRefreshToken(rotation.newRawToken());
                CookieUtils.clearRefreshCookie(response, cookiePath);
                log.warn("用户 {} refresh 被拒绝: {}", rotation.userId(), statusDenyMessage);
                return R.fail(401, statusDenyMessage);
            }
            // 4. 下发新 refresh cookie
            CookieUtils.setRefreshCookie(response, rotation.newRawToken(),
                    (int) jwtProperties.getRefreshExpireSeconds(), cookieSecure, cookiePath);
            // 5. 签发新 access token
            String newAccessToken = jwtTokenProvider.generateToken(rotation.userId());
            // 6. 返回
            return R.ok(new TokenResponse(newAccessToken, jwtProperties.getAccessExpireSeconds()));
        } catch (BaseException e) {
            // 轮换失败（无效/过期/重放）→ 清 cookie
            CookieUtils.clearRefreshCookie(response, cookiePath);
            return R.fail(e.getCode(), e.getMessage());
        }
    }

    /**
     * 登出（撤销 refresh token + 清除 cookie + 踢出缓存）。
     * <p>
     * 从 cookie 读取 refresh token → 撤销 → 清 cookie → 踢缓存。
     * 无 cookie / token 无效时静默成功（幂等）。
     */
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // 1. 从 cookie 读取 refresh token
        String rawToken = CookieUtils.getRefreshTokenFromCookie(request);
        // 2. 撤销 refresh token（幂等：无 token 时静默成功）
        refreshTokenService.revokeRefreshToken(rawToken);
        // 3. 清除 cookie
        CookieUtils.clearRefreshCookie(response, cookiePath);
        // 4. 如果有当前登录用户，踢出缓存
        try {
            LoginUser currentUser = LoginUserHolder.get();
            if (currentUser != null) {
                loginUserLoader.kickOut(currentUser.getUserId());
            }
        } catch (Exception ignored) {
            // 用户可能未认证（access token 已过期），忽略
        }
        return R.ok();
    }

    /**
     * 修改当前登录用户自己的密码。
     * <p>
     * 校验旧密码 → BCrypt 加密新密码 → 落库 → 踢出该用户缓存
     *（下次请求需重新登录，符合"刷新 = 重登录"语义）。
     *
     * @param request 修改密码请求（oldPassword + newPassword）
     * @return 成功返回 R.ok
     */
    @PostMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        LoginUser current = LoginUserHolder.get();
        if (current == null) {
            return R.fail(401, "未登录");
        }
        SysUser user = sysUserService.getById(current.getUserId());
        if (user == null) {
            return R.fail(401, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            return R.fail(400, "旧密码错误");
        }
        sysUserService.updatePassword(user.getId(), request.getNewPassword());
        loginUserLoader.kickOut(user.getId());
        log.info("用户 {} 已修改密码, userId={}", user.getUsername(), user.getId());
        return R.ok();
    }

    /**
     * 账号状态校验提示。
     * <p>
     * status=0（正常）→ 返回 null（放行）；status=2（锁定）→ "账号已锁定"；
     * 其余（1=停用，以及 null/未知值）→ "账号已停用"。登录与刷新两条链路共用，
     * 保证提示语义一致。
     *
     * @param status 用户状态
     * @return 拒绝提示语；正常状态返回 null
     */
    private String statusDenyMessage(Integer status) {
        if (status == null || status != 0) {
            return status != null && status == 2 ? "账号已锁定" : "账号已停用";
        }
        return null;
    }

    /**
     * 登录请求（P45 五项语义字段）。
     * <p>
     * 字段缺失/格式非法不使用 Bean Validation 返回 400，而是按所属校验阶段返回对应错误语义：
     * captcha/captchaId 缺失 → 验证码错误(2101)；timestamp 非法 → 机器时间异常(2103)；
     * password/username 缺失 → 密码错误(2104)，避免暴露字段级差异。
     */
    @Data
    public static class LoginRequest {

        private String username;
        /** RSA-OAEP(SHA-256) 加密后的 Base64 密文密码 */
        private String password;
        private String captcha;
        private String captchaId;
        /** 客户端提交时的 Unix epoch 毫秒 */
        private String timestamp;
    }

    @Data
    public static class ChangePasswordRequest {

        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }
}
