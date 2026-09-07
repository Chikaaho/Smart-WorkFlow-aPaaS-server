package com.sw.ck.system.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.sw.ck.system.controller.LoginChallengeTestSupport.TEST_PRIVATE_KEY_PEM;
import static com.sw.ck.system.controller.LoginChallengeTestSupport.encryptPassword;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RsaLoginKeyManager} 测试：无默认私钥 fail-fast、密钥强度校验、
 * OAEP(SHA-256) 解密往返、密钥版本绑定（不信任未知版本）。
 */
@DisplayName("登录 RSA 密钥管理")
class RsaLoginKeyManagerTest {

    private LoginSecurityProperties props(String pem, String version) {
        LoginSecurityProperties properties = new LoginSecurityProperties();
        properties.setRsaPrivateKey(pem);
        properties.setRsaKeyVersion(version);
        return properties;
    }

    @Test
    @DisplayName("未配置私钥 → 启动期 fail-fast，不允许默认私钥")
    void constructor_withoutKey_shouldFailFast() {
        assertThatThrownBy(() -> new RsaLoginKeyManager(props("", "v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("登录 RSA 私钥未配置");
    }

    @Test
    @DisplayName("垃圾私钥 → 解析失败 fail-fast")
    void constructor_withGarbageKey_shouldFailFast() {
        assertThatThrownBy(() -> new RsaLoginKeyManager(props("not-a-pem", "v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    @DisplayName("解密往返：OAEP(SHA-256) 加密的密文可被正确解密")
    void decrypt_roundtrip() throws Exception {
        RsaLoginKeyManager manager = new RsaLoginKeyManager(props(TEST_PRIVATE_KEY_PEM, "v1"));

        String plaintext = manager.decrypt("v1", encryptPassword("admin123安全密码!"));

        assertThat(plaintext).isEqualTo("admin123安全密码!");
    }

    @Test
    @DisplayName("未知密钥版本 → 拒绝解密（不信任客户端指定版本）")
    void decrypt_unknownVersion_shouldReject() {
        RsaLoginKeyManager manager = new RsaLoginKeyManager(props(TEST_PRIVATE_KEY_PEM, "v1"));

        assertThatThrownBy(() -> manager.decrypt("v0", encryptPassword("x")))
                .isInstanceOf(RsaLoginKeyManager.PasswordDecryptException.class)
                .hasMessageContaining("未知密钥版本");
    }

    @Test
    @DisplayName("非 Base64 密文 → 拒绝解密，不降级明文")
    void decrypt_invalidBase64_shouldReject() {
        RsaLoginKeyManager manager = new RsaLoginKeyManager(props(TEST_PRIVATE_KEY_PEM, "v1"));

        assertThatThrownBy(() -> manager.decrypt("v1", "plaintext-password"))
                .isInstanceOf(RsaLoginKeyManager.PasswordDecryptException.class);
    }

    @Test
    @DisplayName("公钥派生：activePublicKeyBase64 为合法 X.509 SPKI，2048 位密钥明文上限 190 字节")
    void activePublicKey_shouldBeValidSpki() throws Exception {
        RsaLoginKeyManager manager = new RsaLoginKeyManager(props(TEST_PRIVATE_KEY_PEM, "v1"));

        byte[] spki = java.util.Base64.getDecoder().decode(manager.activePublicKeyBase64());
        java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
        java.security.PublicKey publicKey =
                factory.generatePublic(new java.security.spec.X509EncodedKeySpec(spki));

        assertThat(publicKey.getAlgorithm()).isEqualTo("RSA");
        assertThat(manager.maxPlaintextBytes()).isEqualTo(190);
        assertThat(manager.activeKeyVersion()).isEqualTo("v1");
    }
}
