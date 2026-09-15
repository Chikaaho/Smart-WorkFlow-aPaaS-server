package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_process_trigger 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_process_trigger")
public class IotProcessTrigger extends BaseEntity {

    /** 规则 ID */
    @TableField("rule_id")
    private Long ruleId;

    /** 脚本 ID（脚本发起时） */
    @TableField("script_id")
    private Long scriptId;

    /** 设备 ID */
    @TableField("device_id")
    private Long deviceId;

    /** 幂等键（租户内唯一） */
    @TableField("idempotent_key")
    private String idempotentKey;

    /** PENDING/SUCCESS/FAILED */
    @TableField("status")
    private String status;

    /** 流程实例 ID */
    @TableField("process_instance_id")
    private String processInstanceId;

    /** 表单快照 JSON */
    @TableField("form_snapshot")
    private String formSnapshot;

    /** 失败原因 */
    @TableField("error")
    private String error;

    /** 触发时间 */
    @TableField("trigger_time")
    private LocalDateTime triggerTime;

}
