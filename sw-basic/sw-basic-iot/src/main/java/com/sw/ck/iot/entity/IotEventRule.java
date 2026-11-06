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

    /** Agent 图联动启用：1/0 */
    @TableField("agent_enabled")
    private Integer agentEnabled;

    /** 目标已发布 Agent 图 key */
    @TableField("agent_graph_key")
    private String agentGraphKey;

    /** Agent 执行输入映射 JSON（source 词汇表同 form_mapping_json，另支持 RECENT_SERIES） */
    @TableField("agent_input_mapping_json")
    private String agentInputMappingJson;

    /** 研判执行失败升级的流程模板 key（空 = 不升级） */
    @TableField("agent_failure_template_key")
    private String agentFailureTemplateKey;

    /** 失败升级通用配置 JSON：{"templateKey"?:string,"formDataExtra"?:object,"notify"?:{channels:[],recipientId?,title?,content?}} */
    @TableField("agent_failure_config_json")
    private String agentFailureConfigJson;

}
