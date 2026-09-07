package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import org.apache.ibatis.type.JdbcType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单配置/样式实体 — 对应 {@code sw_form_config} 表。
 * <p>
 * 每物理表（主表单 + 每个 TABLE 子表）占一行。
 * definition 字段存储该表的字段样式/控件布局/校验规则 schema（JSON）。
 * </p>
 *
 * <h3>行模型</h3>
 * <ul>
 *   <li>主表单行：{@code table_name = sw_form_{nanoId}}，{@code parent_table = null}</li>
 *   <li>子表行：{@code table_name = sw_form_table_{nanoId}}，{@code parent_table = 主表单 table_name}</li>
 *   <li>查询主表单的子表：{@code WHERE parent_table = ?}</li>
 *   <li>REFERENCE 关系不在此表开列，留在引用方 definition JSON 中</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_config", autoResultMap = true)
public class FormConfigEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id */
    private String formId;

    /**
     * 物理表名（唯一 key）。
     * <p>
     * 主表单：{@code sw_form_{nanoId}}；子表：{@code sw_form_table_{nanoId}}。
     * 发布时由 FormDefServiceImpl 回填。
     * </p>
     */
    private String tableName;

    /**
     * 父表 table_name（子表行填写，指向主表单的 table_name）。
     * <p>
     * 主表单/被引用表单此行留空（null）。
     * 用于查询主表单的所有子表：{@code SELECT * FROM sw_form_config WHERE parent_table = ?}
     * </p>
     */
    private String parentTable;

    /** 表单定义 JSON（控件布局/样式/校验规则） */
    @TableField(value = "definition", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String definition;
}
