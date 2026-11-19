package com.sw.ck.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * AES-256-GCM 对称加密工具，用于外部数据源凭据加密。
 * <p>
 * <b>密钥来源:</b> 环境变量 {@code SW_CIPHER_KEY}（Base64 编码的 32 字节密钥，
 * 或 16/24 字节自动升级）。密钥不进代码、不进库、不进日志。
 * </p>
 * <p>
 * <b>密文格式:</b> {@code base64(12-byte IV || ciphertext-with-128-bit-tag)}。
 * 每次加密使用随机 IV，相同明文每次产不同密文。
 * </p>
 */
public final class AesGcmCipher {

    /** AES-GCM 推荐 IV 长度 */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度 */
    private static final int TAG_LENGTH = 128;
    /** 目标密钥长度（AES-256） */
    private static final int KEY_LENGTH = 32;
    /** 算法常量 */
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    /**
     * @param base64Key Base64 编码的 AES 密钥（16/24/32 字节，不足 32 自动补零至 256-bit）
     * @throws IllegalArgumentException 密钥为 null 或长度不合法
     */
    public AesGcmCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("AES cipher key must not be null or blank. "
                    + "Set SW_CIPHER_KEY environment variable with a base64-encoded 32-byte key.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AES cipher key is not valid base64: " + base64Key.substring(0, Math.min(8, base64Key.length())) + "...", e);
        }
        if (keyBytes.length < 16) {
            throw new IllegalArgumentException("AES cipher key too short: " + keyBytes.length + " bytes, minimum 16");
        }
        if (keyBytes.length > 32) {
            throw new IllegalArgumentException("AES cipher key too long: " + keyBytes.length + " bytes, maximum 32");
        }
        // 统一补齐至 256-bit
        if (keyBytes.length < KEY_LENGTH) {
            byte[] padded = new byte[KEY_LENGTH];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        this.secureRandom = new SecureRandom();
    }

    /**
     * 加密明文密码，返回 Base64 密文。
     *
     * @param plainText 明文密码
     * @return base64(IV || ciphertext)
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            throw new IllegalArgumentException("Password must not be null");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // IV + ciphertext 拼接后 Base64 编码
            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM encryption failed", e);
        }
    }

    /**
     * 解密密文，返回明文密码。
     *
     * @param cipherText base64(IV || ciphertext) 格式的密文
     * @return 明文密码
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            throw new IllegalArgumentException("Cipher text must not be null");
        }
        if (cipherText.isEmpty()) {
            return ""; // 空密码
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length < IV_LENGTH + 1) {
                throw new IllegalArgumentException("Invalid cipher text: too short");
            }

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            byte[] ciphertext = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("AES-GCM decryption failed", e);
        }
    }

    /**
     * 将密码脱敏显示（保留前 2 后 2 字符，中间用 **** 替代）。
     */
    public static String mask(String password) {
        if (password == null || password.isEmpty()) {
            return "***";
        }
        if (password.length() <= 4) {
            return "****";
        }
        return password.substring(0, 2) + "****" + password.substring(password.length() - 2);
    }
}
