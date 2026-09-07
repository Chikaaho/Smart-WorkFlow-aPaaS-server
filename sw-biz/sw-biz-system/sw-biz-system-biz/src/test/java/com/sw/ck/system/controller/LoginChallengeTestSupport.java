package com.sw.ck.system.controller;

import com.sw.ck.system.security.LoginChallengeStore;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * P45 认证测试支撑：测试专用 RSA 密钥对、OAEP 加密辅助与内存挑战存储测试替身。
 * <p>
 * 内存存储替身与 Redis 实现保持相同的原子 consume 语义（remove 成功即消费）；
 * 仅作测试替身，生产权威状态仍唯一落在 Redis 实现。
 */
public final class LoginChallengeTestSupport {

    /** 测试固定 RSA 2048 密钥对（仅测试用） */
    public static final KeyPair TEST_KEY_PAIR = generateKeyPair();
    /** 测试私钥 PKCS#8 PEM（注入 LoginSecurityProperties） */
    public static final String TEST_PRIVATE_KEY_PEM = toPkcs8Pem(TEST_KEY_PAIR.getPrivate());

    private LoginChallengeTestSupport() {
    }

    /** 与前端 WebCrypto RSA-OAEP(SHA-256) 对齐的加密辅助 */
    public static String encryptPassword(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, TEST_KEY_PAIR.getPublic(), new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return Base64.getEncoder().encodeToString(
                    cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("测试加密失败", e);
        }
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toPkcs8Pem(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /** 内存挑战存储测试替身（原子 consume 语义与 Redis 一致） */
    static class InMemoryLoginChallengeStore implements LoginChallengeStore {

        final Map<String, LoginChallengeRecord> records = new ConcurrentHashMap<>();
        final Map<String, Long> failures = new ConcurrentHashMap<>();

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
            return failures.merge(captchaId, 1L, Long::sum);
        }
    }

    /**
     * 可测挑战服务：捕获最近一次生成的验证码原文，供测试按真实签发链路提交登录
     * （答案只经服务端生成路径捕获，不从图像载荷反解——与生产自动化约束一致）。
     */
    static class TestableLoginChallengeService extends com.sw.ck.system.security.LoginChallengeService {

        private String lastCaptchaCode;

        TestableLoginChallengeService(LoginChallengeStore store,
                                      com.sw.ck.system.security.RsaLoginKeyManager keyManager,
                                      com.sw.ck.system.security.LoginSecurityProperties properties,
                                      com.sw.ck.system.security.PngCaptchaRenderer renderer) {
            super(store, keyManager, properties, renderer);
        }

        @Override
        protected String generateCaptcha(int length) {
            lastCaptchaCode = super.generateCaptcha(length);
            return lastCaptchaCode;
        }

        String lastCaptchaCode() {
            return lastCaptchaCode;
        }
    }
}
