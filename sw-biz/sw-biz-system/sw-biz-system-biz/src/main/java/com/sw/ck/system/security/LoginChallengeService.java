package com.sw.ck.system.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * 登录挑战服务（P45）。
 * <p>
 * 负责挑战的签发（验证码图像 + UUID + 公钥 + 服务器时间 + 有效期）与验证辅助；
 * 权威状态全部落在 {@link LoginChallengeStore}（跨实例 Redis）。
 * 业务有效期（{@code challengeTtlSeconds}，默认 300 秒）与记录保留期
 * （{@code recordRetentionSeconds}，默认 600 秒）分离：有效期内内容匹配可登录；
 * 有效期外但记录仍在保留期内 → 稳定返回 2102「验证码已过期」；
 * 保留期结束后记录消亡 → 按挑战不存在返回 2101。答案仅以服务端密钥参与的
 * HMAC-SHA256 摘要落存储（Redis 泄露不能凭摘要枚举恢复答案），响应载荷为
 * {@link PngCaptchaRenderer} 渲染的 PNG 位图（无独立答案字段）。
 */
@Component
public class LoginChallengeService {

    private final LoginChallengeStore challengeStore;
    private final RsaLoginKeyManager rsaKeyManager;
    private final LoginSecurityProperties properties;
    private final PngCaptchaRenderer captchaRenderer;
    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] digestSecretBytes;

    @Autowired
    public LoginChallengeService(LoginChallengeStore challengeStore,
                                 RsaLoginKeyManager rsaKeyManager,
                                 LoginSecurityProperties properties,
                                 PngCaptchaRenderer captchaRenderer) {
        this.challengeStore = challengeStore;
        this.rsaKeyManager = rsaKeyManager;
        this.properties = properties;
        this.captchaRenderer = captchaRenderer;
        // 服务端密钥参与答案摘要：未配置即 fail-fast，无密钥摘要不允许上线
        String secret = properties.getDigestSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "登录验证码摘要密钥未配置：必须经外部安全配置注入 sw.security.login.digest-secret"
                            + "（如环境变量 SW_LOGIN_DIGEST_SECRET），无密钥摘要不允许上线");
        }
        this.digestSecretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 挑战签发结果（登录前置响应体；captchaImage 为 SVG data URL，不含独立答案字段） */
    public record ChallengeView(String captchaImage, String captchaId, String publicKey,
                                String keyVersion, long expiresIn, long serverTime) {
    }

    /**
     * 签发新挑战：生成验证码原文与 UUID，权威状态（摘要+密钥版本+生成时间）以记录保留期落 Redis。
     * 新挑战使用当前生效密钥版本；新挑战不会复活已过期或已消费挑战（UUID 由服务端生成）。
     */
    public ChallengeView create() {
        String captcha = generateCaptcha(properties.getCaptchaLength());
        String captchaId = UUID.randomUUID().toString();
        long createdAtEpochMs = System.currentTimeMillis();
        challengeStore.save(captchaId, RedisLoginChallengeStore.record(
                        hmacDigest(normalizeCaptcha(captcha)),
                        rsaKeyManager.activeKeyVersion(), createdAtEpochMs),
                Duration.ofSeconds(properties.getRecordRetentionSeconds()));
        return new ChallengeView(captchaRenderer.render(captcha), captchaId,
                rsaKeyManager.activePublicKeyBase64(), rsaKeyManager.activeKeyVersion(),
                properties.getChallengeTtlSeconds(), createdAtEpochMs);
    }

    /**
     * 步骤 1+2：验证码记录与内容、有效期校验。
     *
     * @return 校验通过返回挑战记录；失败抛出对应 {@link AuthErrorCode} 语义的异常
     */
    public LoginChallengeStore.LoginChallengeRecord verifyCaptcha(String captchaId, String captcha) {
        if (captchaId == null || captchaId.isBlank() || captcha == null || captcha.isBlank()) {
            throw new AuthException(AuthErrorCode.CAPTCHA_ERROR);
        }
        LoginChallengeStore.LoginChallengeRecord record = challengeStore.find(captchaId);
        if (record == null) {
            throw new AuthException(AuthErrorCode.CAPTCHA_ERROR);
        }
        if (!MessageDigest.isEqual(record.captchaDigest().getBytes(StandardCharsets.UTF_8),
                hmacDigest(normalizeCaptcha(captcha)).getBytes(StandardCharsets.UTF_8))) {
            long failures = challengeStore.recordCaptchaFailure(captchaId,
                    Duration.ofSeconds(properties.getRecordRetentionSeconds()));
            if (failures > properties.getCaptchaFailLimit()) {
                // 超过有限失败次数：即使记录仍在保留期内也立即作废
                challengeStore.delete(captchaId);
            }
            throw new AuthException(AuthErrorCode.CAPTCHA_ERROR);
        }
        // 业务有效期判定：记录仍在（保留期 > 有效期）才能稳定区分 2102 与 2101
        if (record.createdAtEpochMs() + properties.getChallengeTtlSeconds() * 1000 < System.currentTimeMillis()) {
            challengeStore.delete(captchaId);
            throw new AuthException(AuthErrorCode.CAPTCHA_EXPIRED);
        }
        return record;
    }

    /**
     * 客户端机器时间校验（步骤 3）：timestamp 必须是 Unix epoch 毫秒且与服务器绝对差不超过容忍窗口。
     * 客户端时间只用于时钟异常提示，不承担防重放。
     */
    public void verifyClientTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw new AuthException(AuthErrorCode.CLIENT_TIME_ABNORMAL);
        }
        long clientMillis;
        try {
            clientMillis = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new AuthException(AuthErrorCode.CLIENT_TIME_ABNORMAL);
        }
        long diff = Math.abs(System.currentTimeMillis() - clientMillis);
        if (diff > properties.getClientTimeToleranceMillis()) {
            throw new AuthException(AuthErrorCode.CLIENT_TIME_ABNORMAL);
        }
    }

    /**
     * 原子消费挑战（步骤 4 的裁决动作）。
     *
     * @return false 表示挑战已被并发/重复请求消费，本次不得进入密码认证
     */
    public boolean consume(String captchaId) {
        return challengeStore.consume(captchaId);
    }

    protected String generateCaptcha(int length) {
        String charset = properties.getCaptchaCharset();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(charset.charAt(secureRandom.nextInt(charset.length())));
        }
        return sb.toString();
    }

    /**
     * 答案的服务端密钥认证摘要：HMAC-SHA256(digestSecret, normalizedAnswer)。
     * Redis 权威记录只保留该摘要——摘要不可逆，且无服务端密钥时枚举字符集组合
     * 无法与记录比对，Redis 泄露不能恢复答案。
     */
    private String hmacDigest(String normalizedAnswer) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(digestSecretBytes, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(normalizedAnswer.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 验证码比较前统一小写去空白 */
    public static String normalizeCaptcha(String captcha) {
        return captcha.trim().toLowerCase();
    }

    private static final java.util.HexFormat HexFormat = java.util.HexFormat.of();

    /** 认证业务异常：code 为稳定机器错误码，message 为外显提示 */
    public static class AuthException extends RuntimeException {
        private final int code;

        public AuthException(AuthErrorCode errorCode) {
            super(errorCode.getMessage());
            this.code = errorCode.getCode();
        }

        public int getCode() {
            return code;
        }
    }
}
