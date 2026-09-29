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

/**
 * 飞书 SSO Provider 实现（I5）。
 * <p>
 * 网页授权：{@code https://open.feishu.cn/open-apis/authen/v1/authorize} 发起，
 * {@code accounts.feishu.cn/oauth/v3/token} 以 code + 客户端凭据换取（官方 2026-08 现行契约）
 * user_access_token，再经 {@code /open-apis/authen/v1/user_info} 解析 open_id
 * （租户内稳定主体标识）。
 * </p>
 */
public class FeishuSsoProviderClient implements SsoProviderClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuSsoProviderClient.class);

    private static final String AUTHORIZE_URL = "https://open.feishu.cn/open-apis/authen/v1/authorize";
    private static final String TOKEN_URL = "https://accounts.feishu.cn/oauth/v3/token";
    private static final String USER_INFO_URL = "https://open.feishu.cn/open-apis/authen/v1/user_info";
    private static final String APP_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal";
    private static final String CONTACT_USER_URL = "https://open.feishu.cn/open-apis/contact/v3/users/";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String provider() {
        return "FEISHU";
    }

    @Override
    public String buildAuthorizeUrl(SsoProviderConfigView config, String redirectUri, String state) {
        // response_type=code 为官方授权 URL 必带固定值（iteration-10 文档对照 G8-DOC-FEISHU 补齐）
        return AUTHORIZE_URL + "?app_id=" + urlEncode(config.appId())
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&state=" + urlEncode(state);
    }

    @Override
    public SsoProviderClient.ExchangeResult exchangeExternalId(SsoProviderConfigView config, String code, String redirectUri) {
        // v3 oauth/token（官方现行）：POST application/x-www-form-urlencoded，
        // grant_type=authorization_code；authorize 带 redirect_uri 时换票必须原样携带
        String userAccessToken;
        try (HttpResponse response = HttpRequest.post(TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=" + urlEncode("authorization_code")
                        + "&client_id=" + urlEncode(config.appId())
                        + "&client_secret=" + urlEncode(config.appSecret())
                        + "&code=" + urlEncode(code)
                        + "&redirect_uri=" + urlEncode(redirectUri == null ? "" : redirectUri))
                .timeout(8000)
                .execute()) {
            if (response.getStatus() != 200) {
                throw new SsoProviderException("飞书换票失败: HTTP " + response.getStatus());
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code0 = root.path("code").asInt(0);
            if (code0 != 0) {
                throw new SsoProviderException("飞书换票被拒绝: code=" + code0);
            }
            // v3 成功响应为平铺字段（历史 v2 为 data 包装），两者兼容解析
            userAccessToken = root.path("access_token").asText(null);
            if (userAccessToken == null || userAccessToken.isBlank()) {
                userAccessToken = root.path("data").path("access_token").asText(null);
            }
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("飞书换票异常: {}", e.getClass().getSimpleName());
            throw new SsoProviderException("飞书换票失败", e);
        }
        if (userAccessToken == null || userAccessToken.isBlank()) {
            throw new SsoProviderException("飞书未返回 user_access_token");
        }

        try (HttpResponse response = HttpRequest.get(USER_INFO_URL)
                .header("Authorization", "Bearer " + userAccessToken)
                .timeout(8000)
                .execute()) {
            if (response.getStatus() != 200) {
                throw new SsoProviderException("飞书获取用户信息失败: HTTP " + response.getStatus());
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code0 = root.path("code").asInt(0);
            if (code0 != 0) {
                throw new SsoProviderException("飞书获取用户信息被拒绝: code=" + code0);
            }
            String openId = root.path("data").path("open_id").asText(null);
            if (openId == null || openId.isBlank()) {
                throw new SsoProviderException("飞书未返回稳定主体标识 open_id");
            }
            // 官方 user_info 字段：tenant_key=用户所属租户（G3b 企业归属可信来源）
            String tenantKey = root.path("data").path("tenant_key").asText(null);
            // 可信手机号（B 端准入）：authen/user_info 不含手机号，需以应用身份调
            // contact/v3/users/{open_id}（scope contact:user.base:readonly）；权限未开通
            // 或调用失败时返回 null，由服务端准入链 fail closed，不在本层放大为换票失败
            String mobile = fetchMobileQuietly(config, openId);
            return new SsoProviderClient.ExchangeResult(openId, tenantKey, mobile);
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("飞书用户信息异常: {}", e.getClass().getSimpleName());
            throw new SsoProviderException("飞书获取用户信息失败", e);
        }
    }

    /**
     * 以应用身份读取联系人手机号；任何失败（权限缺失/网络/解析）都静默降级为
     * {@code null}——准入链据此拒绝并记录脱敏审计，不回传失败原因细节给用户。
     */
    private String fetchMobileQuietly(SsoProviderConfigView config, String openId) {
        try {
            String appToken;
            try (HttpResponse response = HttpRequest.post(APP_TOKEN_URL)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(objectMapper.writeValueAsString(Map.of(
                            "app_id", config.appId(),
                            "app_secret", config.appSecret())))
                    .timeout(8000)
                    .execute()) {
                if (response.getStatus() != 200) {
                    log.info("飞书应用凭据获取失败: HTTP {}", response.getStatus());
                    return null;
                }
                JsonNode root = objectMapper.readTree(response.body());
                if (root.path("code").asInt(-1) != 0) {
                    log.info("飞书应用凭据被拒绝: code={}", root.path("code").asInt(-1));
                    return null;
                }
                appToken = root.path("app_access_token").asText(null);
            }
            if (appToken == null || appToken.isBlank()) {
                return null;
            }
            try (HttpResponse response = HttpRequest.get(CONTACT_USER_URL + urlEncode(openId)
                            + "?user_id_type=open_id")
                    .header("Authorization", "Bearer " + appToken)
                    .timeout(8000)
                    .execute()) {
                if (response.getStatus() != 200) {
                    log.info("飞书联系人读取失败: HTTP {}", response.getStatus());
                    return null;
                }
                JsonNode root = objectMapper.readTree(response.body());
                if (root.path("code").asInt(-1) != 0) {
                    log.info("飞书联系人读取被拒绝: code={}", root.path("code").asInt(-1));
                    return null;
                }
                String mobile = root.path("data").path("user").path("mobile").asText(null);
                return mobile == null || mobile.isBlank() ? null : mobile;
            }
        } catch (Exception e) {
            log.info("飞书手机号读取异常: {}", e.getClass().getSimpleName());
            return null;
        }
    }


    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

}
