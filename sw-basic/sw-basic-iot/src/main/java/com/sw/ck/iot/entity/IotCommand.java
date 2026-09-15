package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_command 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_command")
public class IotCommand extends BaseEntity {

    /** 设备 ID */
    @TableField("device_id")
    private Long deviceId;

    /** 连接 ID */
    @TableField("conn_id")
    private Long connId;

    /** Provider：MQTT/TENCENT */
    @TableField("provider")
    private String provider;

    /** 能力类型：SET_PROPERTY/INVOKE_ACTION/PUBLISH */
    @TableField("capability_type")
    private String capabilityType;

    /** 能力标识 */
    @TableField("capability_id")
    private String capabilityId;

    /** 参数快照 JSON */
    @TableField("params_json")
    private String paramsJson;

    /** PENDING/BROKER_ACK/DEVICE_REPLY/SUCCESS/FAILED/TIMEOUT */
    @TableField("status")
    private String status;

    /** 结果 JSON */
    @TableField("result_json")
    private String resultJson;

    /** 失败原因 */
    @TableField("error")
    private String error;

    /** 幂等键 */
    @TableField("idempotent_key")
    private String idempotentKey;

    /** 端到端关联标识；设备回执必须原样带回。 */
    @TableField("correlation_id")
    private String correlationId;

    /** 来源：MANUAL/SCRIPT/RULE/FLOW */
    @TableField("source_type")
    private String sourceType;

    /** 来源引用 */
    @TableField("source_ref")
    private String sourceRef;

    /** 流程实例 ID */
    @TableField("flow_instance_id")
    private String flowInstanceId;

    /** 流程任务 ID */
    @TableField("flow_task_id")
    private String flowTaskId;

    /** QoS */
    @TableField("qos")
    private Integer qos;

    /** 发送时间 */
    @TableField("sent_time")
    private LocalDateTime sentTime;

    /** 设备回复时间 */
    @TableField("reply_time")
    private LocalDateTime replyTime;

    /** 超时毫秒 */
    @TableField("timeout_ms")
    private Integer timeoutMs;

}
