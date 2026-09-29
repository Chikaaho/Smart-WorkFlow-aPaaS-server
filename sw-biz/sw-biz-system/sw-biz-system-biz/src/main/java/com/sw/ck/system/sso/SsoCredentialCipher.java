package com.sw.ck.system.sso;

import com.sw.ck.common.crypto.AesGcmCipher;

/**
 * SSO 凭据加密器——显式依赖绑定（sso-admin-config 方向 §二）。
 * <p>
 * 背景（G4-S 运行诊断实证）：历史实现的 {@code ssoCipher} bean 带
 * {@code @ConditionalOnMissingBean(AesGcmCipher.class)}，与 agent 模块的
 * {@code agentAesGcmCipher} 构成同型条件竞争，实际生效实例由 bean 注册顺序
 * 隐式决定（运行时被 agent 实例顶替，密钥来源退化为 {@code sw.agent.cipher-key}）。
 * </p>
 * <p>
 * 修正：SSO 不再注入共享 {@link AesGcmCipher} bean，改由 SSO 装配工厂以专属
 * 密钥源 {@code sw.security.sso.cipher-key}（环境变量 {@code SW_SSO_CIPHER_KEY}）
 * 显式构造本实例——密钥来源固定、不随任何模块 bean 顺序漂移；密钥缺失启动
 * fail-fast。全局 agent 加密器与其密钥不受影响（兼容已有密文，不更换全局密钥）。
 * SSO 专属密钥与 agent 密钥分离后，SSO 密文需以本密钥加密（部署变更须重加密
 * 既有 SSO Provider 密文，属运维口径，不改动其他模块数据）。
 * </p>
 */
public final class SsoCredentialCipher {

    private final AesGcmCipher delegate;

    public SsoCredentialCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "SSO 凭据加密密钥未配置：必须经外部安全配置注入 sw.security.sso.cipher-key"
                            + "（如环境变量 SW_SSO_CIPHER_KEY），明文凭据不允许落库");
        }
        this.delegate = new AesGcmCipher(base64Key);
    }

    public String encrypt(String plaintext) {
        return delegate.encrypt(plaintext);
    }

    public String decrypt(String ciphertext) {
        return delegate.decrypt(ciphertext);
    }

    /** 密文可解且非占位（配置检查用；不返回明文）。 */
    public boolean decryptable(String ciphertext) {
        try {
            String plain = delegate.decrypt(ciphertext);
            return plain != null && !plain.isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
