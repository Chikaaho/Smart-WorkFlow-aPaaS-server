package com.sw.ck.agent.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型 DTO（F1 阶段 1）：挂在供应商下的模型，对外展示。
 */
@Data
public class AgentModelDTO {

    private Long id;

    private Long providerId;

    /** 供应商名称（联表展示，列表场景填充） */
    private String providerName;

    /** 供应商 API 格式（联表展示） */
    private String providerApiFormat;

    /** 供应商是否启用（列表据此标注"因供应商停用不可用"） */
    private Boolean providerEnabled;

    private String name;

    private String modelName;

    /** CHAT / EMBEDDING（逗号分隔） */
    private String capabilities;

    private BigDecimal temperature;

    private Integer maxTokens;

    private BigDecimal topP;

    private Integer timeoutSeconds;

    private Integer retryCount;

    private Boolean enabled;

    private String groupKey;

    private Integer sort;

    private LocalDateTime lockedUntil;

    private Integer quotaCooldownSeconds;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
