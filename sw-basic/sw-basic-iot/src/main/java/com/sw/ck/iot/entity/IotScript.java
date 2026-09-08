package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_script 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_script")
public class IotScript extends BaseEntity {

    /** 脚本编码（租户内唯一） */
    @TableField("code")
    private String code;

    /** 脚本名称 */
    @TableField("name")
    private String name;

    /** 语言：JS/JAVA */
    @TableField("language")
    private String language;

    /** 触发类型：MESSAGE/EVENT/RULE/MANUAL */
    @TableField("trigger_type")
    private String triggerType;

    /** 绑定（连接/产品/设备/Topic）JSON */
    @TableField("binding_json")
    private String bindingJson;

    /** 输入结构 */
    @TableField("input_schema")
    private String inputSchema;

    /** 输出结构 */
    @TableField("output_schema")
    private String outputSchema;

    /** DRAFT/PUBLISHED/DISABLED */
    @TableField("status")
    private String status;

    /** 当前草稿版本 */
    @TableField("current_version")
    private Integer currentVersion;

    /** 已发布版本 */
    @TableField("published_version")
    private Integer publishedVersion;

    /** 执行超时毫秒 */
    @TableField("timeout_ms")
    private Integer timeoutMs;

}
