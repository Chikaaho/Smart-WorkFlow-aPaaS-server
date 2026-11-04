package com.sw.ck.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 供应商（F1 阶段 1，D022-02）：一种 API 格式 + 地址 + 密钥。
 * <p>
 * 模型（{@link AgentModel}）挂在供应商下。API Key 仅以密文落库（AesGcmCipher），
 * {@code apiKeyCipher} 由 {@code @JsonIgnore} 屏蔽，对外只回脱敏值。
 * apiFormat 固定三种：OPENAI_CHAT / OPENAI_RESPONSES / ANTHROPIC（D022-02，不设白名单外格式）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_agent_provider")
public class AgentProvider extends BaseEntity {

    /** API 格式：OPENAI_CHAT / OPENAI_RESPONSES / ANTHROPIC */
    private String apiFormat;

    /** 显示名称，租户内唯一（uk_sw_agent_provider_name (tenant_id, name)） */
    private String name;

    /** 供应商服务地址（含路径前缀，如 https://host/v1） */
    private String baseUrl;

    /** API Key 密文（AesGcmCipher 加密结果，Base64），本地无鉴权网关可为 null */
    @JsonIgnore
    private String apiKeyCipher;

    /** 1=启用 0=停用 */
    private Boolean enabled;

    private String remark;
}
