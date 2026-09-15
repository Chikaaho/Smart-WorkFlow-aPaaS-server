package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_product 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_product")
public class IotProduct extends BaseEntity {

    /** 产品编码（租户内唯一） */
    @TableField("code")
    private String code;

    /** 产品名称 */
    @TableField("name")
    private String name;

    /** 默认连接配置 ID */
    @TableField("conn_id")
    private Long connId;

    /** 连接类型：TENCENT / MQTT */
    @TableField("conn_type")
    private String connType;

    /** 物模型状态：DRAFT/PUBLISHED */
    @TableField("model_status")
    private String modelStatus;

    /** 已发布物模型版本 ID */
    @TableField("published_model_id")
    private Long publishedModelId;

    /** 说明 */
    @TableField("description")
    private String description;

}
