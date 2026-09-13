package com.sw.ck.openapi.biz.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开放应用（I4 §3.4）：稳定应用身份绑定租户与授权范围。
 * <p>
 * app_secret 仅存 SHA-256 摘要；scope 为 CSV：PROCESS_START,PROCESS_QUERY,TASK_HANDLE；
 * act_as_user_id 为该应用代理发起/办理所绑定的租户内有效用户（服务端代理上下文）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_openapi_app")
public class OpenApiApp extends BaseEntity {

    @TableField("app_id")
    private String appId;

    @TableField("app_name")
    private String appName;

    @TableField("secret_hash")
    private String secretHash;

    @TableField("scopes")
    private String scopes;

    @TableField("status")
    private String status;

    @TableField("act_as_user_id")
    private Long actAsUserId;

    /** 状态回调地址（可空=不订阅回调）。 */
    @TableField("callback_url")
    private String callbackUrl;

    /** 回调签名密钥摘要（回调签名用独立密钥原文不出库，存摘要校验口径同入站）。 */
    @TableField("callback_secret_hash")
    private String callbackSecretHash;
}
