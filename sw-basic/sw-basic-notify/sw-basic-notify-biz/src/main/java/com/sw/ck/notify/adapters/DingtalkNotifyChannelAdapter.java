package com.sw.ck.notify.adapters;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉工作通知生产渠道适配器（I6）。
 * <p>仅在 {@code sw.notify.channels.DINGTALK.enabled=true} 时装配；目标 userid
 * 由系统按租户权威解析，不接受客户端原始地址旁路。</p>
 */
@Component
@ConditionalOnProperty(prefix = "sw.notify.channels.DINGTALK", name = "enabled", havingValue = "true")
public class DingtalkNotifyChannelAdapter implements NotifyChannelAdapter {

    private static final String TOKEN_URL = "https://oapi.dingtalk.com/gettoken";
    private static final String SEND_URL = "https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2";
    private static final long TOKEN_TTL_MS = 110L * 60L * 1000L;
    private static final NotifyChannel CHANNEL = NotifyChannel.DINGTALK;

    private final NotifyTargetResolver targetResolver;
    private final String appKey;
    private final String appSecret;
    private final String agentId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Object[]> tokenCache = new ConcurrentHashMap<>();

    @Autowired
    public DingtalkNotifyChannelAdapter(NotifyTargetResolver targetResolver,
                                        com.sw.ck.notify.config.NotifyChannelProperties props) {
        this.targetResolver = targetResolver;
        com.sw.ck.notify.config.NotifyChannelProperties.ChannelProps cp = props.getChannels().get("DINGTALK");
        this.appKey = cp.getAppKey();
        this.appSecret = cp.getAppSecret();
        this.agentId = cp.getAgentId();
    }

    @Override
    public Optional<NotifyChannel> channel() {
        return Optional.of(CHANNEL);
    }

    @Override
    public Optional<NotifySendResult> send(NotifySendRequest request) {
        Optional<String> subject = targetResolver
                .resolveProviderSubject(request.getTenantId(), request.getRecipientId(), "DINGTALK")
                .filter(value -> !value.isBlank());
        if (subject.isEmpty()) {
            return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("无法解析接收人钉钉主体，拒绝发送").build());
        }
        String userid = subject.orElseThrow();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agent_id", agentId);
            body.put("userid_list", userid);
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("msgtype", "markdown");
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("title", request.getTitle() == null ? "通知" : request.getTitle());
            md.put("text", (request.getTitle() == null ? "" : request.getTitle())
                    + "\n" + (request.getContent() == null ? "" : request.getContent()));
            msg.put("markdown", md);
            body.put("msg", msg);
            String token = accessToken();
            try (HttpResponse resp = HttpRequest.post(SEND_URL + "?access_token=" + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(mapper.writeValueAsString(body))
                    .timeout(8000)
                    .execute()) {
                JsonNode root = mapper.readTree(resp.body());
                int errCode = root.path("errcode").asInt(-1);
                if (errCode == 0) {
                    return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("SUCCESS")
                            .externalMessageId(root.path("task_id").asText("dingtalk-" + System.nanoTime()))
                            .build());
                }
                return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                        .failureReason("钉钉发送失败: errcode=" + errCode)
                        .build());
            }
        } catch (Exception e) {
            return Optional.of(NotifySendResult.builder().channel(CHANNEL).status("FAILED")
                    .failureReason("钉钉发送异常: " + e.getClass().getSimpleName()).build());
        }
    }

    private String accessToken() {
        Object[] found = tokenCache.get("dingtalk");
        if (found != null && System.currentTimeMillis() < (long) found[1]) {
            return (String) found[0];
        }
        try (HttpResponse resp = HttpRequest.post(TOKEN_URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(mapper.writeValueAsString(Map.of("appkey", appKey, "appsecret", appSecret)))
                .timeout(8000)
                .execute()) {
            JsonNode root = mapper.readTree(resp.body());
            int errCode = root.path("errcode").asInt(-1);
            if (errCode != 0) {
                throw new IllegalStateException("钉钉 token 失败 errcode=" + errCode);
            }
            String token = root.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("钉钉未返回 access_token");
            }
            tokenCache.put("dingtalk", new Object[]{token, System.currentTimeMillis() + TOKEN_TTL_MS});
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("钉钉 token 调用失败: " + e.getClass().getSimpleName(), e);
        }
    }
}
