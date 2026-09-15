package com.sw.ck.iot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IoT 模块加密配置。
 * <p>
 * cipher-key 与 sw.external-datasource.cipher-key 同构共享（默认 {@code SW_CIPHER_KEY}）。
 * 密钥不进代码、不进库、不进日志。
 * </p>
 */
@ConfigurationProperties(prefix = "sw.iot.cipher")
public class IotCipherProperties {

    /** AES-256-GCM 密钥（Base64 编码）。 */
    private String cipherKey = System.getenv("SW_CIPHER_KEY");

    public String getCipherKey() {
        return cipherKey;
    }

    public void setCipherKey(String cipherKey) {
        this.cipherKey = cipherKey;
    }
}
