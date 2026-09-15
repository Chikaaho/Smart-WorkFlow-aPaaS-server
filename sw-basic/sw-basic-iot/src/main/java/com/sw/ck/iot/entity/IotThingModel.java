package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_thing_model 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_thing_model")
public class IotThingModel extends BaseEntity {

    /** 产品 ID */
    @TableField("product_id")
    private Long productId;

    /** 物模型版本号 */
    @TableField("model_version")
    private Integer modelVersion;

    /** DRAFT/PUBLISHED/ARCHIVED */
    @TableField("status")
    private String status;

    /** 物模型内容（properties/events/actions） */
    @TableField("content_json")
    private String contentJson;

    /** 发布时间 */
    @TableField("publish_time")
    private LocalDateTime publishTime;

}
