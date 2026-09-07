package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程定义实体。
 * <p>
 * 存储流程设计器的图模型（ProcessGraph JSON），本刀（cut A）恒为 DRAFT 状态。
 * 发布/部署/Flowable 同步留给 cut B。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_process_def")
public class BpmProcessDef extends BaseEntity {

    /**
     * 流程业务 key（UUID 前缀，全局唯一，发布后冻结）。
     */
    @TableField("process_key")
    private String processKey;

    /**
     * 流程名称。
     */
    @TableField("name")
    private String name;

    /**
     * 绑定表单 formKey。
     */
    @TableField("form_key")
    private String formKey;

    /**
     * 定义版本号（默认 1，本刀不递增）。
     */
    @TableField("def_version")
    private Integer defVersion;

    /**
     * 状态：DRAFT / PUBLISHED。本刀恒 DRAFT。
     */
    @TableField("status")
    private String status;

    /**
     * Flowable 部署 ID（cut B 回填）。
     */
    @TableField("deployment_id")
    private String deploymentId;

    /**
     * Flowable 流程定义 ID（cut B 回填）。
     */
    @TableField("process_definition_id")
    private String processDefinitionId;

    /**
     * 图 JSON 文档（ProcessGraph 序列化）。
     */
    @TableField("graph_json")
    private String graphJson;

    /**
     * 事项归属分类（v0.0.2 流程中心；NULL=未分类兜底）。
     */
    @TableField("category_id")
    private Long categoryId;
}
