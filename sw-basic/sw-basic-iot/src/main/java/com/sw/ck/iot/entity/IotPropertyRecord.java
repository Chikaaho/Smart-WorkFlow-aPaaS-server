package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_property_record 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_property_record")
public class IotPropertyRecord extends BaseEntity {

    /** 设备 ID */
    @TableField("device_id")
    private Long deviceId;

    /** 属性标识 */
    @TableField("property_id")
    private String propertyId;

    /** 新值 JSON */
    @TableField("value_json")
    private String valueJson;

    /** 前值 JSON */
    @TableField("pre_value_json")
    private String preValueJson;

    /** 上报时间 */
    @TableField("report_time")
    private LocalDateTime reportTime;

}
