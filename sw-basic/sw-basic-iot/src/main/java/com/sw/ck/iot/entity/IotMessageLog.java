package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_message_log 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_message_log")
public class IotMessageLog extends BaseEntity {

    /** 连接 ID */
    @TableField("conn_id")
    private Long connId;

    /** 设备 ID */
    @TableField("device_id")
    private Long deviceId;

    /** 主题 */
    @TableField("topic")
    private String topic;

    /** 方向：UP/DOWN */
    @TableField("direction")
    private String direction;

    /** 消息 ID */
    @TableField("message_id")
    private String messageId;

    /** 去重键 */
    @TableField("dedup_key")
    private String dedupKey;

    /** 载荷 */
    @TableField("payload")
    private String payload;

    /** 解析类型 */
    @TableField("payload_type")
    private String payloadType;

    /** RECEIVED/PARSED/FAILED/IGNORED/DUPLICATED */
    @TableField("parse_status")
    private String parseStatus;

    /** 解析错误 */
    @TableField("parse_error")
    private String parseError;

    /** QoS */
    @TableField("qos")
    private Integer qos;

}
