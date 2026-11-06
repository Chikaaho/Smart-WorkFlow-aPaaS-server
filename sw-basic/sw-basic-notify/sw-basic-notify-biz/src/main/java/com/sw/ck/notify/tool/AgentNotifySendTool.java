package com.sw.ck.notify.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 内部工具：按渠道发送通知（0.2.4，通用能力，经工具白名单注册）。
 * <p>
 * 输入（JSON 字符串）：
 * {@code {"channels":["IN_APP","EMAIL"],"recipientId":123,"title":"…","content":"…",
 * "bizType":"SYSTEM","bizId":"…"}}。逐渠道经 {@link NotifyFacade} 发送，
 * 渠道发送结果分开返回（{@code results:[{channel,status,failureReason}]}）；
 * 渠道不可用或失败以结果状态表达，不静默当作成功。收件人必须为同租户有效用户
 * （渠道适配器侧校验）。全部渠道都失败时 {@code success=false}。
 * JSON 解析沿用本模块 Jackson 惯例（口径与渠道适配器一致）。
 * </p>
 */
@Component("agentNotifySendTool")
public class AgentNotifySendTool {

    private static final Logger log = LoggerFactory.getLogger(AgentNotifySendTool.class);

    /** 与既有适配器同款的静态无状态 ObjectMapper。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<NotifyFacade> notifyFacadeProvider;

    public AgentNotifySendTool(ObjectProvider<NotifyFacade> notifyFacadeProvider) {
        this.notifyFacadeProvider = notifyFacadeProvider;
    }

    /** 内部工具统一签名：单字符串入参、字符串出参。 */
    public String execute(String inputJson) {
        JsonNode input;
        try {
            input = JSON.readTree(inputJson == null ? "{}" : inputJson);
        } catch (Exception e) {
            return fail("输入不是合法 JSON: " + e.getMessage());
        }
        NotifyFacade notifyFacade = notifyFacadeProvider.getIfAvailable();
        if (notifyFacade == null) {
            return fail("通知运行时未装配");
        }
        if (LoginUserHolder.get() == null || LoginUserHolder.get().getTenantId() == null) {
            return fail("执行身份缺失（租户上下文不可用）");
        }
        Long tenantId = LoginUserHolder.get().getTenantId();
        JsonNode channels = input.get("channels");
        if (channels == null || !channels.isArray() || channels.isEmpty()) {
            return fail("缺少 channels（至少一个渠道，如 [\"IN_APP\",\"EMAIL\"]）");
        }
        JsonNode recipientNode = input.get("recipientId");
        Long recipientId = longOf(recipientNode);
        if (recipientId == null) {
            return fail("缺少 recipientId");
        }
        String title = textOf(input, "title");
        String content = textOf(input, "content");
        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            return fail("缺少 title 或 content");
        }
        NotifyBizType bizType;
        try {
            bizType = NotifyBizType.valueOf(textOf(input, "bizType") == null
                    ? "SYSTEM" : textOf(input, "bizType"));
        } catch (IllegalArgumentException unknownType) {
            return fail("未知 bizType: " + textOf(input, "bizType"));
        }
        String bizId = textOf(input, "bizId");
        List<Map<String, Object>> results = sendAllChannels(notifyFacade, channels, recipientId,
                title, content, bizType, bizId, tenantId);
        boolean anyDelivered = false;
        for (Map<String, Object> item : results) {
            Object status = item.get("status");
            if (status != null && !"FAILED".equals(status)) {
                anyDelivered = true;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", !results.isEmpty() && anyDelivered);
        result.put("results", results);
        return writeJson(result);
    }

    private List<Map<String, Object>> sendAllChannels(NotifyFacade notifyFacade, JsonNode channels,
                                                      Long recipientId, String title, String content,
                                                      NotifyBizType bizType, String bizId, Long tenantId) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode channelNode : channels) {
            String channelName = channelNode.asText();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("channel", channelName);
            NotifyChannel channel;
            try {
                channel = NotifyChannel.valueOf(channelName);
            } catch (IllegalArgumentException unknownChannel) {
                item.put("status", "FAILED");
                item.put("failureReason", "未知渠道");
                results.add(item);
                continue;
            }
            try {
                // 稳定幂等键：同租户+渠道+业务对象+接收人不重复落行（重复告警合并）
                String idempotencyKey = "agent-tool-" + channel.name() + "-" + bizType.name()
                        + "-" + (bizId == null ? "" : bizId) + "-" + recipientId;
                Optional<NotifySendResult> outcome = notifyFacade.send(NotifySendRequest.builder()
                        .recipientId(recipientId)
                        .title(title)
                        .content(content)
                        .bizType(bizType)
                        .bizId(bizId)
                        .tenantId(tenantId)
                        .channel(channel)
                        .idempotencyKey(idempotencyKey)
                        .build());
                if (outcome.isPresent()) {
                    NotifySendResult sendResult = outcome.get();
                    item.put("status", sendResult.getStatus() == null ? "UNKNOWN" : sendResult.getStatus());
                    item.put("failureReason", sendResult.getFailureReason());
                } else {
                    item.put("status", "FAILED");
                    item.put("failureReason", "无发送结果");
                }
            } catch (Exception e) {
                log.warn("Agent 工具发送通知失败: channel={}, error={}", channelName, e.getMessage());
                item.put("status", "FAILED");
                item.put("failureReason", e.getMessage());
            }
            results.add(item);
        }
        return results;
    }

    private String textOf(JsonNode input, String field) {
        JsonNode node = input.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 接收人 ID 解析：模型/页面参数常把 64 位 ID 以字符串传递，numeric 与数字字符串都接受；
     * 非数字/空值返回 null（由调用方给出明确失败，不猜默认接收人）。
     */
    private static Long longOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"结果序列化失败\"}";
        }
    }

    private String fail(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", error);
        return writeJson(result);
    }
}
