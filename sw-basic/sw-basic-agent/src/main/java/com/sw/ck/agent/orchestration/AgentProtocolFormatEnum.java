package com.sw.ck.agent.orchestration;

import lombok.Getter;

/**
 * Agent 上游协议格式契约（SUPPORTED_FORMATS 白名单词汇）。
 */
@Getter
public enum AgentProtocolFormatEnum {

    OPENAI_CHAT("OPENAI_CHAT", "OpenAI Chat Completions"),
    OPENAI_RESPONSES("OPENAI_RESPONSES", "OpenAI Responses"),
    ANTHROPIC("ANTHROPIC", "Anthropic Messages"),
    ;

    private final String code;
    private final String label;

    AgentProtocolFormatEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
