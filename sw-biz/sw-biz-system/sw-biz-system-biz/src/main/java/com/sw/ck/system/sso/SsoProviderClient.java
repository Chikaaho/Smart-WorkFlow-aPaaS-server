package com.sw.ck.system.sso;

import java.util.Map;

/**
 * 第三方 SSO Provider SPI（I5）。
 * <p>
 * 每个 Provider（企业微信/飞书/钉钉）各自实现：构造授权 URL、用一次性 code 向
 * Provider 官方端点换票并解析稳定主体标识。实现不得把 secret/code/token 写入
 * 日志或异常消息；网络失败返回错误语义，不静默降级。
 * </p>
 */
public interface SsoProviderClient {

    /** Provider 标识：WECOM / FEISHU / DINGTALK */
    String provider();

    /**
     * 构造服务端授权发起 URL。
     *
     * @param config      已解密的配置视图（secret 不进入 URL）
     * @param redirectUri 服务端回调完整 URL（白名单校验后传入）
     * @param state       一次性 state
     * @return 授权发起 URL
     */
    String buildAuthorizeUrl(SsoProviderConfigView config, String redirectUri, String state);

    /**
     * 用一次性授权 code 换取外部稳定主体标识。
     *
     * @param config 已解密的配置视图
     * @param code   Provider 回调的一次性 code
     * @return 外部主体标识（Provider 官方稳定 userid）
     * @throws SsoProviderException 换票失败（网络/凭据/无效 code）
     */
    String exchangeExternalId(SsoProviderConfigView config, String code);

    /**
     * 已解密配置视图：secret 仅在服务端换票时使用，不序列化、不进日志。
     */
    record SsoProviderConfigView(String appId, String appSecret, Map<String, String> extra) {
    }

    /** Provider 交互失败（网络/凭据/拒绝），message 不得包含 secret/code 原文。 */
    class SsoProviderException extends RuntimeException {
        public SsoProviderException(String message) {
            super(message);
        }

        public SsoProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
