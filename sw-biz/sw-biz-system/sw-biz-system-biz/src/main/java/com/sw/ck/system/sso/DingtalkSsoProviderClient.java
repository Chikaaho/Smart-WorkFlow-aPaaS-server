package com.sw.ck.system.sso;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉 SSO Provider 实现（I5）。
 * <p>
 * 扫码/网页授权：{@code https://login.dingtalk.com/oauth2/auth} 发起（PKCE 可选，
 * state 必带），{@code /v1.0/oauth2/userAccessToken} 以 code 换 user access token，
 * 再经 {@code /v1.0/contact/users/me} 解析 unionId（开放平台稳定主体标识）。
 * </p>
 */
public class DingtalkSsoProviderClient implements SsoProviderClient {

    private static final Logger log = LoggerFactory.getLogger(DingtalkSsoProviderClient.class);

    private static final String AUTHORIZE_URL = "https://login.dingtalk.com/oauth2/auth";
    private static final String USER_TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";
    private static final String USER_INFO_URL = "https://api.dingtalk.com/v1.0/contact/users/me";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String provider() {
        return "DINGTALK";
    }

    @Override
    public String buildAuthorizeUrl(SsoProviderConfigView config, String redirectUri, String state) {
        // G3b：企业模式（extra.enterpriseId 已配置）需请求 corpid scope，否则换票
        // 响应不含 corpId、企业归属无法校验（实测：scope=openid 时 corpId 缺失被拒）
        String scope = config.extra() != null && config.extra().containsKey("enterpriseId")
                ? "openid+corpid" : "openid";
        return AUTHORIZE_URL + "?clientId=" + urlEncode(config.appId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(scope)
                + "&state=" + urlEncode(state)
                + "&prompt=consent";
    }

    @Override
    public SsoProviderClient.ExchangeResult exchangeExternalId(SsoProviderConfigView config, String code, String redirectUri) {
        String userAccessToken;
        String tokenCorpId = null;
        try (HttpResponse response = HttpRequest.post(USER_TOKEN_URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(objectMapper.writeValueAsString(Map.of(
                        "clientId", config.appId(),
                        "clientSecret", config.appSecret(),
                        "code", code,
                        "grantType", "authorization_code")))
                .timeout(8000)
                .execute()) {
            if (response.getStatus() != 200) {
                throw new SsoProviderException("钉钉换票失败: HTTP " + response.getStatus());
            }
            JsonNode root = objectMapper.readTree(response.body());
            userAccessToken = root.path("accessToken").asText(null);
            // 官方换票响应字段：corpId=用户所选企业（G3b 企业归属可信来源）
            tokenCorpId = root.path("corpId").asText(null);
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("钉钉换票异常: {}", e.getClass().getSimpleName());
            throw new SsoProviderException("钉钉换票失败", e);
        }
        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new SsoProviderException("钉钉未返回 user access token");
        }

        try (HttpResponse response = HttpRequest.get(USER_INFO_URL)
                .header("x-acs-dingtalk-access-token", userAccessToken)
                .timeout(8000)
                .execute()) {
            if (response.getStatus() != 200) {
                throw new SsoProviderException("钉钉获取用户信息失败: HTTP " + response.getStatus());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String unionId = root.path("unionId").asText(null);
            if (unionId == null || unionId.isBlank()) {
                throw new SsoProviderException("钉钉未返回稳定主体标识 unionId");
            }
            // 可信手机号（B 端准入）：同一官方 users/me 响应的 mobile 字段；应用未开通
            // Contact.User.Read 或字段缺失时为 null，由服务端准入链 fail closed，
            // 不在本层把缺失放大为换票失败（保持拒绝原因可区分）
            String mobile = root.path("mobile").asText(null);
            return new SsoProviderClient.ExchangeResult(unionId, tokenCorpId,
                    mobile == null || mobile.isBlank() ? null : mobile);
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("钉钉用户信息异常: {}", e.getClass().getSimpleName());
            throw new SsoProviderException("钉钉获取用户信息失败", e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
