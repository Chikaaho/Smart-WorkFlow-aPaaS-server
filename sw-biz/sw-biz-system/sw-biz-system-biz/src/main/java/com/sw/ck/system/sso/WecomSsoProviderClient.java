package com.sw.ck.system.sso;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 企业微信 SSO Provider 实现（I5）。
 * <p>
 * Web 扫码/网页授权：{@code https://login.work.weixin.qq.com/wwopen/sso/qrConnect}
 * 发起，{@code https://qyapi.weixin.qq.com/cgi-bin/gettoken} 取 corp access_token 后经
 * {@code /cgi-bin/auth/getuserinfo} 用一次性 code 换取成员稳定 userid。
 * secret 仅在服务端出站换票时使用，不进入 URL 查询参数之外的任何输出。
 * </p>
 */
public class WecomSsoProviderClient implements SsoProviderClient {

    private static final Logger log = LoggerFactory.getLogger(WecomSsoProviderClient.class);

    private static final String AUTHORIZE_URL = "https://login.work.weixin.qq.com/wwopen/sso/qrConnect";
    private static final String TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";
    private static final String USERINFO_URL = "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo";

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** corp_access_token 进程内缓存：key = appId，value = [token, expireEpochMs] */
    private final Map<String, Object[]> tokenCache = new ConcurrentHashMap<>();

    @Override
    public String provider() {
        return "WECOM";
    }

    @Override
    public String buildAuthorizeUrl(SsoProviderConfigView config, String redirectUri, String state) {
        String corpId = config.appId();
        String agentId = config.extra().getOrDefault("agentId", "");
        return AUTHORIZE_URL + "?appid=" + urlEncode(corpId)
                + "&agentid=" + urlEncode(agentId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&state=" + urlEncode(state);
    }

    @Override
    public String exchangeExternalId(SsoProviderConfigView config, String code) {
        String accessToken = corpAccessToken(config);
        String url = USERINFO_URL + "?access_token=" + urlEncode(accessToken)
                + "&code=" + urlEncode(code);
        JsonNode root = getJson(url, "企业微信获取成员身份失败");
        // 出错时 errcode 非 0（0/301055 等语义见官方文档）；不回传响应原文
        int errcode = root.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new SsoProviderException("企业微信换票被拒绝: errcode=" + errcode);
        }
        String userId = root.path("userid").asText(null);
        if (userId == null || userId.isBlank()) {
            throw new SsoProviderException("企业微信未返回稳定成员标识");
        }
        return userId;
    }

    private String corpAccessToken(SsoProviderConfigView config) {
        Object[] cached = tokenCache.get(config.appId());
        if (cached != null && (long) cached[1] > System.currentTimeMillis() + 60_000) {
            return (String) cached[0];
        }
        String url = TOKEN_URL + "?corpid=" + urlEncode(config.appId())
                + "&corpsecret=" + urlEncode(config.appSecret());
        JsonNode root = getJson(url, "企业微信获取 access_token 失败");
        int errcode = root.path("errcode").asInt(0);
        if (errcode != 0) {
            throw new SsoProviderException("企业微信凭据被拒绝: errcode=" + errcode);
        }
        String token = root.path("access_token").asText(null);
        long expiresIn = root.path("expires_in").asLong(7200);
        if (token == null || token.isBlank()) {
            throw new SsoProviderException("企业微信未返回 access_token");
        }
        tokenCache.put(config.appId(), new Object[]{token, System.currentTimeMillis() + expiresIn * 1000});
        return token;
    }

    private JsonNode getJson(String url, String failureMessage) {
        try (HttpResponse response = HttpRequest.get(url)
                .timeout(8000)
                .execute()) {
            if (response.getStatus() != 200) {
                throw new SsoProviderException(failureMessage + ": HTTP " + response.getStatus());
            }
            return objectMapper.readTree(response.body());
        } catch (SsoProviderException e) {
            throw e;
        } catch (Exception e) {
            log.warn("{}: {}", failureMessage, e.getClass().getSimpleName());
            throw new SsoProviderException(failureMessage, e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 测试支持：清空 token 缓存 */
    void clearTokenCache() {
        tokenCache.clear();
    }

    /** 仅供测试注入伪 token（避免出站） */
    void stubToken(String appId, String token, long expireEpochMs) {
        tokenCache.put(appId, new Object[]{token, expireEpochMs});
    }

    static Map<String, String> parseExtra(String extraJson) {
        Map<String, String> extra = new HashMap<>();
        if (extraJson == null || extraJson.isBlank()) {
            return extra;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(extraJson);
            node.properties().forEach(entry -> {
                if (entry.getValue() != null && !entry.getValue().isNull()) {
                    extra.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (Exception ignored) {
            // 非法 extra 由保存入口校验拒绝；读取时按空处理
        }
        return extra;
    }
}
