package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程定义发布版本（I3 §4.3）。
 * <p>
 * 每次发布冻结一条不可变行：graph_json、节点配置、表单版本与函数版本全部冻结。
 * PUBLISHED 行不得被原地覆盖；SUSPENDED 仅禁止新实例；DISABLED 仅作业务下线标记。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_process_def_version")
public class BpmProcessDefVersion extends BaseEntity {

    /** 所属流程定义 ID（sw_bpm_process_def.id）。 */
    @TableField("def_id")
    private Long defId;

    /** 图版本号（单调递增，发布时冻结）。 */
    @TableField("graph_version")
    private Integer graphVersion;

    /** 版本状态：PUBLISHED / SUSPENDED / DISABLED。 */
    @TableField("status")
    private String status;

    /** 流程名称（发布时快照）。 */
    @TableField("name")
    private String name;

    /** 绑定表单 formKey（发布时快照）。 */
    @TableField("form_key")
    private String formKey;

    /** 表单版本（发布时冻结的等价引用）。 */
    @TableField("form_version")
    private String formVersion;

    /** 节点函数版本映射 JSON（funcKey → version；发布时冻结）。 */
    @TableField("function_versions")
    private String functionVersions;

    /** 流程图 JSON（发布时冻结，不再修改）。 */
    @TableField("graph_json")
    private String graphJson;

    /** Flowable 部署 ID。 */
    @TableField("deployment_id")
    private String deploymentId;

    /** Flowable 流程定义 ID。 */
    @TableField("process_definition_id")
    private String processDefinitionId;

    /** 发布人。 */
    @TableField("published_by")
    private Long publishedBy;

    /** 发布时间。 */
    @TableField("published_at")
    private java.time.LocalDateTime publishedAt;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getGraphJson() {
        return graphJson;
    }
}
