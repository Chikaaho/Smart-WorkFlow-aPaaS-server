package com.sw.ck.openapi.biz.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 入站防重放随机串（I4 §3.4）：唯一键 (app_id, nonce)，过期清理。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_openapi_nonce")
public class OpenApiNonce extends BaseEntity {

    @TableField("app_id")
    private String appId;

    @TableField("nonce")
    private String nonce;

    @TableField("expire_at")
    private LocalDateTime expireAt;
}
