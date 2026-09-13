package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户级第三方 SSO Provider 配置（I5）。
 * <p>
 * app_secret 仅存 AES-256-GCM 密文（{@code app_secret_enc}），任何响应/日志/审计
 * 不得回传明文或密文；provider 取值 WECOM / FEISHU / DINGTALK。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_sso_provider_config")
public class SsoProviderConfig extends BaseEntity {

    /** Provider 标识：WECOM / FEISHU / DINGTALK */
    @TableField("provider")
    private String provider;

    /** 启停：0=停用 1=启用 */
    @TableField("enabled")
    private Integer enabled;

    /** 应用标识（appId/corpId+agentId 组合键的展示侧） */
    @TableField("app_id")
    private String appId;

    /** 应用密钥（AES-256-GCM 密文） */
    @TableField("app_secret_enc")
    private String appSecretEnc;

    /** Provider 特有扩展配置（JSON：agentId/corpId/域名等，不含秘密） */
    @TableField("extra_config")
    private String extraConfig;

    /** 授权回调路径（服务端回调端点路径，用于构造 redirect_uri） */
    @TableField("redirect_path")
    private String redirectPath;
}
