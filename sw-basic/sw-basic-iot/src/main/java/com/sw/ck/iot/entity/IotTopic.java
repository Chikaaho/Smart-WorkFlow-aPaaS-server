package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_topic 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_topic")
public class IotTopic extends BaseEntity {

    /** 连接配置 ID */
    @TableField("conn_id")
    private Long connId;

    /** 产品 ID */
    @TableField("product_id")
    private Long productId;

    /** 主题 */
    @TableField("topic")
    private String topic;

    /** 方向：UP/DOWN/BOTH */
    @TableField("direction")
    private String direction;

    /** QoS 0/1 */
    @TableField("qos")
    private Integer qos;

    /** retain：1/0 */
    @TableField("retain")
    private Integer retain;

    /** 载荷类型：PROPERTY/EVENT/ACTION_RESULT/RAW */
    @TableField("payload_type")
    private String payloadType;

    /** 映射配置 JSON */
    @TableField("mapping_json")
    private String mappingJson;

    /** 启停：1/0 */
    @TableField("enabled")
    private Integer enabled;

    /** 备注 */
    @TableField("remark")
    private String remark;

}
