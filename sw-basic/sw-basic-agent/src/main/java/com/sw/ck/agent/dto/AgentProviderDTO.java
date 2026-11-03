package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商 DTO（F1 阶段 1）：对外展示（密钥只回脱敏值）。
 */
@Data
public class AgentProviderDTO {

    private Long id;

    private String name;

    /** OPENAI_CHAT / OPENAI_RESPONSES / ANTHROPIC */
    private String apiFormat;

    private String baseUrl;

    /** 密钥脱敏值（未配置密钥时为 null） */
    private String apiKeyMasked;

    private Boolean enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
