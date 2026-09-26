package com.sw.ck.notify.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.api.NotifyTargetResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * 飞书卡片生产渠道适配器（I6）。
 * <p>仅在 {@code sw.notify.channels.FEISHU.enabled=true} 时装配；目标 open_id 由
 * 系统按租户权威解析；不接受客户端原始地址旁路。出站走官方 open API。</p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.notify.channels.FEISHU", name = "enabled", havingValue = "true")
public class FeishuNotifyChannelAdapter implements NotifyChannelAdapter {

    private static final String APP_TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal";
    private static final String SEND_URL = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id";
    private static final long TOKEN_TTL_MS = 50L * 60L * 1000L;
    private static final NotifyChannel CHANNEL = NotifyChannel.FEISHU;

    private final NotifyTargetResolver targetResolver;
    private final String appId;
    private final String appSecret;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object[]> tokenCache = new ConcurrentHashMap<>();

    @Autowired
    public FeishuNotifyChannelAdapter(NotifyTargetResolver targetResolver,
                                      com.sw.ck.notify.config.NotifyChannelProperties props) {
        this.targetResolver = targetResolver;
        com.sw.ck.notify.config.NotifyChannelProperties.ChannelProps cp = props.getChannels().get("FEISHU");
        this.appId = cp.getAppId();
        this.appSecret = cp.getFeishuAppSecret();
    }

    @Override
    public Optional<NotifyChannel> channel() {
        return Optional.of(CHANNEL);
    }

    @Override
    public Optional<NotifySendResult> send(NotifySendRequest request) {
        Optional<String> subject = targetResolver
                .resolveProviderSubject(request.getTenantId(), request.getRecipientId(), "FEISHU")
                .filter(value -> !value.isBlank());
        if (subject.isEmpty()) {
            return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("无法解析接收人飞书主体，拒绝发送").build());
        }
        return Optional.of(sendCard(subject.orElseThrow(), request.getTitle(), request.getContent(),
                request.getLinkType(), request.getLinkId()));
    }

    /** 受控发送（供测试与恢复重试复用）。 */
    public NotifySendResult sendCard(String openId, String title, String content, String linkType, String linkId) {
        String token;
        try {
            token = tenantAccessToken();
        } catch (Exception e) {
            return NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("飞书 token 失败: " + e.getClass().getSimpleName()).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receive_id", openId);
        body.put("msg_type", "interactive");
        body.put("content", mapToJson(buildCard(title, content, linkType, linkId)));
        try {
            HttpResponse resp = HttpRequest.post(SEND_URL)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(body.isEmpty() ? "{}" : mapper.writeValueAsString(body))
                    .timeout(8000)
                    .execute();
            return handleSendResponse(resp);
        } catch (Exception e) {
            return NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("飞书发送异常: " + e.getClass().getSimpleName()).build();
        }
    }

    private NotifySendResult handleSendResponse(HttpResponse resp) {
        int httpCode = resp.getStatus();
        try {
            JsonNode root = mapper.readTree(resp.body());
            int feishuCode = root.path("code").asInt(-1);
            if (httpCode == 200 && feishuCode == 0) {
                String messageId = root.path("data").path("message_id").asText(null);
                return NotifySendResult.builder().channel(CHANNEL).status("SUCCESS")
                        .externalMessageId(messageId == null ? ("feishu-" + System.nanoTime())
                                : messageId)
                        .build();
            }
            return NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("飞书发送失败: HTTP " + httpCode + " code=" + feishuCode)
                    .build();
        } catch (JsonProcessingException parse) {
            return NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("飞书响应解析失败")
                    .build();
        }
    }

    /** 卡片体：markdown 主体与单个受控跳转按钮，不接受任意 URL。 */
    private Map<String, Object> buildCard(String title, String content, String linkType, String linkId) {
        Map<String, Object> card = new LinkedHashMap<>();
        List<Map<String, Object>> elements = new ArrayList<>();
        Map<String, Object> div = new LinkedHashMap<>();
        div.put("tag", "markdown");
        div.put("content", (title == null ? "" : title) + "\n" + (content == null ? "" : content));
        elements.add(div);
        // 受控跳转：仅服务端深链打开入口，标签只承载对象类型而非任意 URL
        if (linkType != null && linkId != null) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("tag", "action");
            Map<String, Object> button = new LinkedHashMap<>();
            button.put("tag", "button");
            button.put("text", Map.of("tag", "plain_text", "content", "查看详情"));
            button.put("type", "default");
            action.put("actions", List.of(button));
            elements.add(action);
        }
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("elements", elements);
        return card;
    }

    private String mapToJson(Map<String, Object> v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("卡片序列化失败", e);
        }
    }

    private String tenantAccessToken() {
        Object[] found = tokenCache.get("feishu");
        if (found != null && System.currentTimeMillis() < (long) found[1]) {
            return (String) found[0];
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("飞书 token 序列化失败", e);
        }
        try {
            HttpResponse resp = HttpRequest.post(APP_TOKEN_URL)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(json)
                    .timeout(8000)
                    .execute();
            JsonNode root = mapper.readTree(resp.body());
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("飞书 token 失败 code=" + code);
            }
            String token = root.path("app_access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("飞书未返回 app_access_token");
            }
            tokenCache.put("feishu", new Object[]{token, System.currentTimeMillis() + TOKEN_TTL_MS});
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("飞书 token 调用失败: " + e.getClass().getSimpleName(), e);
        }
    }
}

