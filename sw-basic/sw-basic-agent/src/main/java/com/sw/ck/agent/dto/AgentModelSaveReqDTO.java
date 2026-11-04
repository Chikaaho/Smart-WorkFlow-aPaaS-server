package com.sw.ck.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型新建/编辑请求 DTO（F1 阶段 1，D022-02）。
 * <p>密钥在供应商侧维护，本 DTO 不含任何密钥字段。lockedUntil 为系统运行态，不对外可写。</p>
 */
@Data
public class AgentModelSaveReqDTO {

    private Long providerId;

    /** 显示名称，租户内唯一 */
    private String name;

    /** 模型标识（调用时传给供应商） */
    private String modelName;

    /** 能力：CHAT / EMBEDDING（逗号分隔；本阶段固定 CHAT，EMBEDDING 阶段 3 启用） */
    private String capabilities;

    private BigDecimal temperature;

    private Integer maxTokens;

    private BigDecimal topP;

    private Integer timeoutSeconds;

    private Integer retryCount;

    private Boolean enabled;

    private String remark;

    /** 多Key轮换候选分组标识（D022-05），null=独立配置不参与轮换 */
    private String groupKey;

    /** 组内优先级，数值越小越优先（DB 默认 0） */
    private Integer sort;

    /** 触发限流后的锁定冷却时长（秒，DB 默认 60） */
    private Integer quotaCooldownSeconds;
}
