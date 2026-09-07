package com.sw.ck.bpm.engine.translator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.GraphValidationError;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.notify.api.NotifyChannel;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 通知节点：统一参与人/模板/渠道配置，经 NotifyFacade 发送。 */
@Component
public class NotificationNodeTranslator extends ServiceTaskNodeTranslator {
    public NotificationNodeTranslator(ObjectMapper objectMapper) { super(objectMapper); }
    @Override public String type() { return "NOTIFICATION"; }
    @Override protected String delegateBean() { return "notificationNodeDelegate"; }
    @Override protected String nodeName() { return "通知"; }

    @Override
    public List<GraphValidationError> validateConfig(GraphElement node) {
        List<GraphValidationError> errors = super.validateConfig(node);
        if (!errors.isEmpty()) return errors;
        Map<String, Object> config = node.getConfig();
        String channel = text(config.get("channel"), "IN_APP");
        if (Arrays.stream(NotifyChannel.values()).noneMatch(item -> item.name().equals(channel))) {
            return List.of(error(node, "通知渠道不合法: " + channel));
        }
        if (blank(config.get("title")) || blank(config.get("content"))) {
            return List.of(error(node, "通知标题和正文不能为空"));
        }
        String strategy = failureStrategy(config);
        if (!List.of("BLOCK", "CONTINUE").contains(strategy)) {
            return List.of(error(node, "失败策略不合法: " + strategy));
        }
        return List.of();
    }

    private String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value).trim();
    }

    private boolean blank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }

    private String failureStrategy(Map<String, Object> config) {
        return text(config.get("failureStrategy"), "BLOCK").toUpperCase();
    }

    private GraphValidationError error(GraphElement node, String message) {
        return GraphValidationError.builder().elementId(node.getId())
                .errorCode(BpmErrorCode.NODE_CONFIG_INVALID.getCode()).message(message).build();
    }
}
