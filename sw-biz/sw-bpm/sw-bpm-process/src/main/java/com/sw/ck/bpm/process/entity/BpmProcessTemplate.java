package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程模板（I4 §3.2）：创建流程定义的受控来源。
 * <p>
 * 模板不直接改写已发布定义、运行实例或历史记录；派生定义通过
 * {@code sw_bpm_process_def.source_template_id/source_template_version} 保持可追溯。
 * 管理范围：GLOBAL 全局可见；DEPT 仅本部门管理员可见/可复制。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_process_template")
public class BpmProcessTemplate extends BaseEntity {

    @TableField("name")
    private String name;

    /** 模板分类（自由文本，前端筛选）。 */
    @TableField("category")
    private String category;

    @TableField("description")
    private String description;

    /** 绑定表单 formKey（复制创建定义时校验表单仍存在）。 */
    @TableField("form_key")
    private String formKey;

    /** 模板图 JSON（ProcessGraph 序列化）。 */
    @TableField("graph_json")
    private String graphJson;

    /** 模板版本号（每次图或元数据变更 +1，仅追溯用）。 */
    @TableField("template_version")
    private Integer templateVersion;

    /** ENABLED / DISABLED。 */
    @TableField("status")
    private String status;

    /** GLOBAL / DEPT。 */
    @TableField("scope_type")
    private String scopeType;

    /** scopeType=DEPT 时生效。 */
    @TableField("scope_dept_id")
    private Long scopeDeptId;

    /** 源定义（若模板自某定义抽取）：来源定义 ID。 */
    @TableField("source_def_id")
    private Long sourceDefId;

    /** 源定义版本。 */
    @TableField("source_def_version")
    private Integer sourceDefVersion;
}
