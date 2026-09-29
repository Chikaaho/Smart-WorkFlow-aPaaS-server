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
     * 用一次性授权 code 换取外部稳定主体标识与厂商可信企业标识。
     *
     * @param redirectUri 发起授权时使用的回调 URL（OAuth 规范：authorize 带了
     *                    redirect_uri 时换票必须原样携带；不需要的 Provider 可忽略）
     * @return externalId=稳定主体标识；enterpriseId=厂商可信企业标识
     *         （钉钉=换票响应 corpId、飞书=tenant_key、企微=应用归属 corpId；可空=该
     *         Provider 响应不含企业字段）
     *
     * @param config 已解密的配置视图
     * @param code   Provider 回调的一次性 code
     * @return 外部主体标识（Provider 官方稳定 userid）
     * @throws SsoProviderException 换票失败（网络/凭据/无效 code）
     */
    ExchangeResult exchangeExternalId(SsoProviderConfigView config, String code, String redirectUri);

    /**
     * 已解密配置视图：secret 仅在服务端换票时使用，不序列化、不进日志。
     */
    record SsoProviderConfigView(String appId, String appSecret, Map<String, String> extra) {
    }

    /**
     * 换票结果：externalId=稳定主体标识；enterpriseId=厂商可信企业标识
     * （取自换票/用户信息响应的官方字段，仅服务端内存使用，不进日志）；
     * mobile=厂商可信接口返回的手机号原文（B 端准入，sso-admin-config）——
     * 必须来自当前已认证主体的厂商服务端响应，不接受回调参数/前端输入；
     * 厂商未返回（权限未开通或字段缺失）时为 {@code null}，由服务端 fail closed。
     */
    record ExchangeResult(String externalId, String enterpriseId, String mobile) {
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
