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
 * 飞书 SSO Provider 实现（I5）。
 * <p>
 * 网页授权：{@code https://open.feishu.cn/open-apis/authen/v1/authorize} 发起，
 * {@code /open-apis/authen/v2/oauth/token} 以 app_access_token + code 换取
 * user_access_token，再经 {@code /open-apis/authen/v1/user_info} 解析 open_id
 * （租户内稳定主体标识）。
 * </p>
 */
public class FeishuSsoProviderClient implements SsoProviderClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuSsoProviderClient.class);

    private static final String AUTHORIZE_URL = "https://open.feishu.cn/open-apis/authen/v1/authorize";
    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/authen/v2/oauth/token";
    private static final String APP_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal";
    private static final String USER_INFO_URL = "https://open.feishu.cn/open-apis/authen/v1/user_info";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Object[]> appTokenCache = new ConcurrentHashMap<>();

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
    public String exchangeExternalId(SsoProviderConfigView config, String code) {
        String appAccessToken = appAccessToken(config);
        // v2 oauth/token：POST JSON，grant_type=authorization_code
        String userAccessToken;
        try (HttpResponse response = HttpRequest.post(TOKEN_URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(objectMapper.writeValueAsString(Map.of(
                        "grant_type", "authorization_code",
                        "client_id", config.appId(),
                        "client_secret", config.appSecret(),
                        "code", code)))
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
            userAccessToken = root.path("data").path("access_token").asText(null);
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
            return openId;
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("飞书用户信息异常: {}", e.getClass().getSimpleName());
            throw new SsoProviderException("飞书获取用户信息失败", e);
        }
    }

    private String appAccessToken(SsoProviderConfigView config) {
        Object[] cached = appTokenCache.get(config.appId());
        if (cached != null && (long) cached[1] > System.currentTimeMillis() + 60_000) {
            return (String) cached[0];
        }
        try (HttpResponse response = HttpRequest.post(APP_TOKEN_URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(objectMapper.writeValueAsString(Map.of(
                        "app_id", config.appId(),
                        "app_secret", config.appSecret())))
                .timeout(8000)
                .execute()) {
            if (response.getStatus() != 200) {
                throw new SsoProviderException("飞书获取 app_access_token 失败: HTTP " + response.getStatus());
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code0 = root.path("code").asInt(0);
            if (code0 != 0) {
                throw new SsoProviderException("飞书凭据被拒绝: code=" + code0);
            }
            String token = root.path("app_access_token").asText(null);
            long expire = root.path("expire").asLong(7200);
            if (token == null || token.isBlank()) {
                throw new SsoProviderException("飞书未返回 app_access_token");
            }
            appTokenCache.put(config.appId(), new Object[]{token, System.currentTimeMillis() + expire * 1000});
            return token;
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("飞书 app token 异常: {}", e.getClass().getSimpleName());
            throw new SsoProviderException("飞书获取 app_access_token 失败", e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 测试支持：清空 token 缓存 */
    void clearTokenCache() {
        appTokenCache.clear();
    }
}
