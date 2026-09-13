package com.sw.ck.openapi.biz.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 出站回调投递日志（I4 §3.4）：失败可查，重试逐次落行，重复事件去重。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_openapi_callback_log")
public class OpenApiCallbackLog extends BaseEntity {

    @TableField("app_id")
    private String appId;

    @TableField("event")
    private String event;

    @TableField("biz_ref")
    private String bizRef;

    @TableField("url")
    private String url;

    @TableField("attempt")
    private Integer attempt;

    /** SUCCESS / FAILED */
    @TableField("status")
    private String status;

    @TableField("response_summary")
    private String responseSummary;

    @TableField("delivered_at")
    private LocalDateTime deliveredAt;
}
