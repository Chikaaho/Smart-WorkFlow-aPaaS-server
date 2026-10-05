package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * IoT 命令预约意图（P63）。
 * <p>
 * 流程成功结束后在同一事务内冻结全部执行要素；到点由调度认领下发，
 * 复用既有设备命令幂等/回执/UNKNOWN 语义。状态机：
 * PENDING → DISPATCHING → DISPATCHED；CANCELED / EXPIRED / FAILED 为确定终态。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_command_reservation")
public class IotCommandReservation extends BaseEntity {

    @TableField("process_instance_id")
    private String processInstanceId;

    @TableField("process_def_key")
    private String processDefKey;

    @TableField("def_version")
    private Integer defVersion;

    @TableField("form_key")
    private String formKey;

    @TableField("record_id")
    private String recordId;

    @TableField("device_key")
    private String deviceKey;

    @TableField("product_id")
    private String productId;

    @TableField("device_name")
    private String deviceName;

    @TableField("command_key")
    private String commandKey;

    @TableField("command_type")
    private String commandType;

    @TableField("payload")
    private String payload;

    /** 预约时刻（UTC 绝对时刻；时区语义由 timezone_id + due_local_text 呈现）。 */
    @TableField("due_at_utc")
    private LocalDateTime dueAtUtc;

    @TableField("timezone_id")
    private String timezoneId;

    /** 用户配置的本地时间原文（含时区呈现，不依赖服务器本地时区解释）。 */
    @TableField("due_local_text")
    private String dueLocalText;

    /** 允许迟到秒数（1—3600；业务有效期裁决，非调度精度保证）。 */
    @TableField("late_window_seconds")
    private Integer lateWindowSeconds;

    /** PENDING / DISPATCHING / DISPATCHED / CANCELED / EXPIRED / FAILED。 */
    @TableField("status")
    private String status;

    /** 下发命中的命令 ID（sw_iot_device_command，回执链起点）。 */
    @TableField("command_id")
    private Long commandId;

    /** 认领/下发被拒绝的明确原因（设备失效/无授权等，可查）。 */
    @TableField("reject_reason")
    private String rejectReason;

    @TableField("cancel_by")
    private Long cancelBy;

    @TableField("cancel_reason")
    private String cancelReason;

    @TableField("cancel_time")
    private LocalDateTime cancelTime;
}
