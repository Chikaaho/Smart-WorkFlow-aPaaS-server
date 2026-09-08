package com.sw.ck.iot.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * sw_iot_event_rule 实体。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_iot_event_rule")
public class IotEventRule extends BaseEntity {

    /** 规则编码（租户内唯一） */
    @TableField("code")
    private String code;

    /** 规则名称 */
    @TableField("name")
    private String name;

    /** 设备 ID */
    @TableField("device_id")
    private Long deviceId;

    /** PROPERTY_CHANGED/THRESHOLD/EVENT_OCCUR/ONLINE/OFFLINE */
    @TableField("rule_type")
    private String ruleType;

    /** 条件 JSON */
    @TableField("condition_json")
    private String conditionJson;

    /** 防抖毫秒 */
    @TableField("debounce_ms")
    private Long debounceMs;

    /** 冷却毫秒 */
    @TableField("cooldown_ms")
    private Long cooldownMs;

    /** 连续次数 */
    @TableField("continuous_count")
    private Integer continuousCount;

    /** 关联脚本 ID */
    @TableField("script_id")
    private Long scriptId;

    /** 流程模板 key */
    @TableField("process_template_key")
    private String processTemplateKey;

    /** 表单字段映射 JSON */
    @TableField("form_mapping_json")
    private String formMappingJson;

    /** 流程联动启用：1/0 */
    @TableField("process_enabled")
    private Integer processEnabled;

    /** DRAFT/PUBLISHED/DISABLED */
    @TableField("status")
    private String status;

    /** 规则版本 */
    @TableField("rule_version")
    private Integer ruleVersion;

    /** 最近触发时间 */
    @TableField("last_fired_time")
    private LocalDateTime lastFiredTime;

}
