package com.sw.ck.openapi.biz.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 出站回调持久任务（Phase 4 可靠业务事件）。
 *
 * <p>回调意图在业务事务内落行（{@code OpenApiCallbackIntentRecorder}），
 * 由提交后加速（{@code OpenApiCallbackListener}）或恢复调度
 * （{@code OpenApiCallbackRecoveryJob}）完成投递：进程在“业务已提交、异步监听未执行”
 * 之间退出仍可恢复；同一 (租户, 应用, 事件, 业务对象) 只有一条任务。</p>
 *
 * <p>状态机：{@code PENDING}（待投递）→ {@code SENDING}（已领取）→
 * {@code SUCCESS}（成功）/ {@code FAILED}（可重试，带 nextRetryTime）/
 * {@code RETRY_EXHAUSTED}（重试预算耗尽的可审计终态）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_openapi_callback_task")
public class OpenApiCallbackTask extends BaseEntity {

    @TableField("app_id")
    private String appId;

    @TableField("event")
    private String event;

    @TableField("biz_ref")
    private String bizRef;

    @TableField("status")
    private String status;

    @TableField("attempts")
    private Integer attempts;

    @TableField("next_retry_time")
    private LocalDateTime nextRetryTime;

    @TableField("last_error")
    private String lastError;

    @TableField("delivered_at")
    private LocalDateTime deliveredAt;
}
