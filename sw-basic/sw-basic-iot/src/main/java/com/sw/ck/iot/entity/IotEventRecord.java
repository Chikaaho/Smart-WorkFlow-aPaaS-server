package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_event_record 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_event_record")
public class IotEventRecord extends BaseEntity {

    /** 设备 ID */
    @TableField("device_id")
    private Long deviceId;

    /** 事件标识 */
    @TableField("event_id")
    private String eventId;

    /** 脚本执行/消息链路关联 ID。 */
    @TableField("correlation_id")
    private String correlationId;

    /** 事件载荷 */
    @TableField("payload")
    private String payload;

    /** 发生时间 */
    @TableField("occur_time")
    private LocalDateTime occurTime;

}
