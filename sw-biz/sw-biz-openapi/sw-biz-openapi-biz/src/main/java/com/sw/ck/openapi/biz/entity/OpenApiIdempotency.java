package com.sw.ck.openapi.biz.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 入站幂等键（I4 §3.4）：唯一键 (app_id, idem_key)，登记业务结果引用。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_openapi_idempotency")
public class OpenApiIdempotency extends BaseEntity {

    @TableField("app_id")
    private String appId;

    @TableField("idem_key")
    private String idemKey;

    @TableField("result_ref")
    private String resultRef;
}
