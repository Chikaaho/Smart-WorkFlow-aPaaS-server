package com.sw.ck.system.security;

import com.sw.ck.system.controller.LoginChallengeTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H1：验证码答案摘要必须是服务端密钥参与的 HMAC，不能退化为可离线枚举的普通摘要。 */
@DisplayName("登录挑战摘要保护")
class LoginChallengeServiceTest {

    @Test
    @DisplayName("Redis 记录摘要不等于无密钥 SHA-256，正确答案仍可由服务端验证")
    void digest_shouldRequireServerSecret() {
        LoginSecurityProperties properties = properties("unit-test-digest-secret");
        CapturingStore store = new CapturingStore();
        CapturingService service = new CapturingService(store, properties);

        LoginChallengeService.ChallengeView challenge = service.create();

        assertThat(service.verifyCaptcha(challenge.captchaId(), service.answer)).isNotNull();
        assertThat(store.records.get(challenge.captchaId()).captchaDigest())
                .isNotEqualTo(sha256(service.answer));
        assertThat(store.records.get(challenge.captchaId()).captchaDigest()).hasSize(64);
    }

    @Test
    @DisplayName("未配置摘要密钥时启动期拒绝装配")
    void missingDigestSecret_shouldFailFast() {
        LoginSecurityProperties properties = properties("");
        CapturingStore store = new CapturingStore();

        assertThatThrownBy(() -> new LoginChallengeService(
                store,
                new RsaLoginKeyManager(properties),
                properties,
                new PngCaptchaRenderer()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("摘要密钥未配置");
    }

    private LoginSecurityProperties properties(String digestSecret) {
        LoginSecurityProperties properties = new LoginSecurityProperties();
        properties.setRsaPrivateKey(LoginChallengeTestSupport.TEST_PRIVATE_KEY_PEM);
        properties.setRsaKeyVersion("v1");
        properties.setDigestSecret(digestSecret);
        return properties;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class CapturingService extends LoginChallengeService {
        private String answer;

        private CapturingService(CapturingStore store, LoginSecurityProperties properties) {
            super(store, new RsaLoginKeyManager(properties), properties, new PngCaptchaRenderer());
        }

        @Override
        protected String generateCaptcha(int length) {
            answer = super.generateCaptcha(length);
            return answer;
        }
    }

    private static final class CapturingStore implements LoginChallengeStore {
        private final Map<String, LoginChallengeRecord> records = new ConcurrentHashMap<>();

        @Override
        public void save(String captchaId, LoginChallengeRecord record, Duration ttl) {
            records.put(captchaId, record);
        }

        @Override
        public LoginChallengeRecord find(String captchaId) {
            return records.get(captchaId);
        }

        @Override
        public boolean consume(String captchaId) {
            return records.remove(captchaId) != null;
        }

        @Override
        public void delete(String captchaId) {
            records.remove(captchaId);
        }

        @Override
        public long recordCaptchaFailure(String captchaId, Duration ttl) {
            return 1;
        }
    }
}
