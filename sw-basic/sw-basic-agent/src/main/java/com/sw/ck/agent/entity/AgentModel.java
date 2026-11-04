package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型（F1 阶段 1，D022-02）：挂在供应商（{@link AgentProvider}）下。
 * <p>
 * 采样参数/超时/重试与多 Key 轮换字段（groupKey/sort/lockedUntil/quotaCooldownSeconds）
 * 沿用 M07-Step5 语义，从 sw_agent_model_config 平移到本表（D022-03 迁移保持 id 不变，
 * 旧 agentModelConfigId 引用直接按 id 解析到本表行）。
 * capabilities：CHAT / EMBEDDING（逗号分隔，阶段 3 启用 EMBEDDING）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_model")
public class AgentModel extends BaseEntity {

    /** 所属供应商 id（sw_agent_provider.id） */
    private Long providerId;

    /** 显示名称，租户内唯一（uk_sw_agent_model_tenant_name (tenant_id, name)） */
    private String name;

    /** 模型标识（如 deepseek/deepseek-v4.1-flash，调用时传给供应商） */
    private String modelName;

    /** 能力：CHAT / EMBEDDING（逗号分隔） */
    private String capabilities;

    private BigDecimal temperature;

    private Integer maxTokens;

    private BigDecimal topP;

    /** 请求超时（秒），默认 30 */
    private Integer timeoutSeconds;

    /** 重试次数，默认 0 */
    private Integer retryCount;

    /** 1=启用 0=停用 */
    private Boolean enabled;

    /** 多Key轮换候选分组标识，null=独立配置不参与轮换 */
    private String groupKey;

    /** 组内优先级，数值越小优先级越高 */
    private Integer sort;

    /** 限流临时锁定至该时间点，null 或已过期=可用（惰性过期，无清理任务） */
    private LocalDateTime lockedUntil;

    /** 触发限流后的锁定冷却时长（秒），默认 60 */
    private Integer quotaCooldownSeconds;

    private String remark;
}
