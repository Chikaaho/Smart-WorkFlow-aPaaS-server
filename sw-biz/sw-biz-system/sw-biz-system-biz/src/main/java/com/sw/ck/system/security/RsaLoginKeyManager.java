package com.sw.ck.system.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * RSA 登录密码密钥管理器（P45）。
 * <p>
 * - 仅从 {@link LoginSecurityProperties} 读取外部注入的 PKCS#8 私钥，无任何默认可用密钥；
 *   未配置或密钥强度不足 2048 位时启动期 fail-fast。
 * - 加密算法与前端 WebCrypto 对齐：RSA-OAEP，摘要 SHA-256，MGF1 也必须显式指定 SHA-256
 *  （SunJCE 对 {@code OAEPWithSHA-256AndMGF1Padding} 默认 MGF1 用 SHA-1，与 WebCrypto 不兼容，
 *   必须用 {@link OAEPParameterSpec} 显式声明）。
 * - 解密一律以挑战绑定的密钥版本为准，不信任客户端任意指定版本。
 */
@Slf4j
@Component
public class RsaLoginKeyManager {

    private static final int MIN_KEY_BITS = 2048;
    /** OAEP(SHA-256) 下 2048 位密钥的单次加密明文上限：modulus - 2*hashLen - 2 */
    private static final int MAX_PLAINTEXT_BYTES = 190;

    private final LoginSecurityProperties properties;

    private final Map<String, PrivateKey> privateKeys = new HashMap<>();
    private final Map<String, String> publicKeysBase64 = new HashMap<>();

    public RsaLoginKeyManager(LoginSecurityProperties properties) {
        this.properties = properties;
        // 构造期完成密钥装载与 fail-fast 校验（不依赖 @PostConstruct，保证任何装配方式下都不会带空密钥表上线）
        init();
    }

    void init() {
        Map<String, String> configured = new HashMap<>(properties.getRsaExtraKeys());
        String activeVersion = properties.getRsaKeyVersion();
        if (configured.containsKey(activeVersion)) {
            throw new IllegalStateException(
                    "登录 RSA 密钥配置冲突：版本 " + activeVersion + " 同时出现在 rsa-extra-keys 与 rsa-key-version");
        }
        configured.put(activeVersion, properties.getRsaPrivateKey());

        boolean anyConfigured = false;
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            String version = entry.getKey();
            String pem = entry.getValue() == null ? "" : entry.getValue().trim();
            if (pem.isEmpty()) {
                if (version.equals(activeVersion)) {
                    throw new IllegalStateException(
                            "登录 RSA 私钥未配置：必须经外部安全配置注入 sw.security.login.rsa-private-key"
                                    + "（如环境变量 SW_LOGIN_RSA_PRIVATE_KEY），不允许默认私钥上线");
                }
                // 轮换窗口外的空条目（env 占位）直接忽略
                continue;
            }
            anyConfigured = true;
            PrivateKey privateKey = parsePrivateKey(pem);
            int bits = keyBits(privateKey);
            if (bits < MIN_KEY_BITS) {
                throw new IllegalStateException("登录 RSA 私钥强度不足：版本 " + version
                        + " 为 " + bits + " 位，要求 >= " + MIN_KEY_BITS);
            }
            privateKeys.put(version, privateKey);
            publicKeysBase64.put(version, derivePublicKeyBase64(privateKey));
        }
        if (!anyConfigured) {
            throw new IllegalStateException(
                    "登录 RSA 私钥未配置：必须经外部安全配置注入 sw.security.login.rsa-private-key"
                            + "（如环境变量 SW_LOGIN_RSA_PRIVATE_KEY），不允许默认私钥上线");
        }
        log.info("登录 RSA 密钥装载完成: activeVersion={}, versions={}", activeVersion, privateKeys.keySet());
    }

    /** 当前生效版本标识（新挑战一律绑定该版本） */
    public String activeKeyVersion() {
        return properties.getRsaKeyVersion();
    }

    /** 当前生效版本的公钥（Base64 编码的 X.509 SubjectPublicKeyInfo，前端 importKey('spki', ...) 直接可用） */
    public String activePublicKeyBase64() {
        return publicKeysBase64.get(properties.getRsaKeyVersion());
    }

    /**
     * 按挑战绑定的密钥版本解密 Base64 密文，返回 UTF-8 明文。
     *
     * @throws PasswordDecryptException 版本未知、密文非法或解密失败（统一收敛为“密码错误”语义）
     */
    public String decrypt(String keyVersion, String base64Ciphertext) throws PasswordDecryptException {
        PrivateKey privateKey = keyVersion == null ? null : privateKeys.get(keyVersion);
        if (privateKey == null) {
            throw new PasswordDecryptException("未知密钥版本: " + keyVersion);
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(base64Ciphertext);
        } catch (IllegalArgumentException e) {
            throw new PasswordDecryptException("密文不是合法 Base64");
        }
        if (ciphertext.length != keyBits(privateKey) / 8) {
            throw new PasswordDecryptException("密文长度与密钥不匹配");
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            // MGF1 显式 SHA-256：与 WebCrypto RSA-OAEP(SHA-256) 对齐（见类注释）
            cipher.init(Cipher.DECRYPT_MODE, privateKey, new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PasswordDecryptException("解密失败: " + e.getClass().getSimpleName());
        }
    }

    /** 明文长度上限（密码不得截断，超限必须安全拒绝） */
    public int maxPlaintextBytes() {
        return MAX_PLAINTEXT_BYTES;
    }

    /** SHA-256 摘要（小写 hex）——验证码权威存储只落摘要不落原文 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private PrivateKey parsePrivateKey(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("登录 RSA 私钥解析失败（要求 PKCS#8 PEM）", e);
        }
    }

    private int keyBits(PrivateKey privateKey) {
        try {
            return ((java.security.interfaces.RSAPrivateKey) privateKey).getModulus().bitLength();
        } catch (ClassCastException e) {
            throw new IllegalStateException("登录私钥必须是 RSA 私钥", e);
        }
    }

    private String derivePublicKeyBase64(PrivateKey privateKey) {
        try {
            java.security.interfaces.RSAPrivateCrtKey crt =
                    (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
            PublicKey publicKey = factory.generatePublic(new java.security.spec.RSAPublicKeySpec(
                    crt.getModulus(), crt.getPublicExponent()));
            return Base64.getEncoder().encodeToString(publicKey.getEncoded());
        } catch (ClassCastException e) {
            throw new IllegalStateException("登录 RSA 私钥必须为 CRT 结构以便派生公钥", e);
        } catch (Exception e) {
            throw new IllegalStateException("登录 RSA 公钥派生失败", e);
        }
    }

    /** 解密失败异常：不携带明文/密文内容，避免敏感信息进入日志 */
    public static class PasswordDecryptException extends Exception {
        public PasswordDecryptException(String message) {
            super(message);
        }
    }
}
