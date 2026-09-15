package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_script_exec 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_script_exec")
public class IotScriptExec extends BaseEntity {

    /** 脚本 ID */
    @TableField("script_id")
    private Long scriptId;

    /** 脚本版本号 */
    @TableField("script_version")
    private Integer scriptVersion;

    /** 触发来源 */
    @TableField("trigger_source")
    private String triggerSource;

    /** 触发引用（消息/规则 ID 等） */
    @TableField("trigger_ref")
    private String triggerRef;

    /** 关联设备 */
    @TableField("device_ref")
    private String deviceRef;

    /** SUCCESS/FAILED/TIMEOUT */
    @TableField("status")
    private String status;

    /** 输入快照 */
    @TableField("input_json")
    private String inputJson;

    /** 输出快照 */
    @TableField("output_json")
    private String outputJson;

    /** 错误信息 */
    @TableField("error")
    private String error;

    /** 耗时毫秒 */
    @TableField("duration_ms")
    private Long durationMs;

    /** 是否真实副作用：1/0 */
    @TableField("side_effect")
    private Integer sideEffect;

    /** 幂等键 */
    @TableField("idempotent_key")
    private String idempotentKey;

    /** 执行链路关联 ID。 */
    @TableField("correlation_id")
    private String correlationId;

}
