package com.sw.ck.system.sso;

import com.sw.ck.common.crypto.AesGcmCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A5 加密兼容（sso-admin-config 审查 01）：SsoCredentialCipher 与底层
 * {@link AesGcmCipher} 密文格式逐字兼容——同一密钥下新旧实现互读（历史密文
 * 可读性），不同密钥安全失败（fail closed，不得跨密钥静默解密）。
 */
@DisplayName("A5 SSO 凭据加密器密文兼容")
class SsoCredentialCipherCompatTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String OTHER_KEY;

    static {
        byte[] other = new byte[32];
        other[0] = 1;
        OTHER_KEY = Base64.getEncoder().encodeToString(other);
    }

    @Test
    @DisplayName("同密钥：AesGcmCipher 历史密文可被 SsoCredentialCipher 读取（往返互读）")
    void sameKey_historicalCiphertextReadable() {
        AesGcmCipher legacy = new AesGcmCipher(KEY);
        SsoCredentialCipher current = new SsoCredentialCipher(KEY);
        String historical = legacy.encrypt("historical-provider-secret");
        assertThat(current.decrypt(historical)).isEqualTo("historical-provider-secret");
        assertThat(current.decryptable(historical)).isTrue();
        // 反向：新实现写入的密文同样可被底层实现读取（回退兼容）
        String fresh = current.encrypt("fresh-provider-secret");
        assertThat(legacy.decrypt(fresh)).isEqualTo("fresh-provider-secret");
    }

    @Test
    @DisplayName("不同密钥：解密失败（fail closed），不跨密钥静默解密")
    void differentKey_failsClosed() {
        SsoCredentialCipher current = new SsoCredentialCipher(KEY);
        AesGcmCipher other = new AesGcmCipher(OTHER_KEY);
        String ciphertext = other.encrypt("other-key-secret");
        assertThat(current.decryptable(ciphertext)).isFalse();
        assertThatThrownBy(() -> current.decrypt(ciphertext)).isInstanceOf(RuntimeException.class);
    }
}
