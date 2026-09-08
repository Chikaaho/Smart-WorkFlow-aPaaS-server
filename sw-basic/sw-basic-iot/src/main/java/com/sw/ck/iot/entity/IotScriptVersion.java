package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_script_version 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_script_version")
public class IotScriptVersion extends BaseEntity {

    /** 脚本 ID */
    @TableField("script_id")
    private Long scriptId;

    /** 脚本版本号 */
    @TableField("script_version")
    private Integer scriptVersion;

    /** 源代码 */
    @TableField("source_code")
    private String sourceCode;

    /** DRAFT/PUBLISHED/ARCHIVED */
    @TableField("status")
    private String status;

    /** 发布时间 */
    @TableField("publish_time")
    private LocalDateTime publishTime;

}
