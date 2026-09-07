package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单定义实体 — 对应 {@code sw_form_def} 表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_def")
public class FormDefEntity extends FormBaseEntity {

    /** 表单业务标识（唯一，如 leave_request） */
    private String formKey;

    /** 表单名称 */
    private String name;

    /** 用户自定义逻辑表名 */
    private String logicalTableName;

    /** 状态：DRAFT / PUBLISHED */
    private String status;

    /** 发布后回填的动态宽表物理名 */
    private String physicalTableName;

    /** 表单版本号（每次发布递增） */
    private Integer formVersion;

    /** 表单描述 */
    private String description;

    /**
     * 发起可见范围，JSON 格式为 {"userIds":[...]}；null/空表示当前租户内全部用户。
     * 仅控制业务发起可见性，不授予表单管理或数据读取权限。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String visibilityScope;

    /**
     * TABLE 字段名 → 子表物理名映射 JSON。
     * <p>
     * 发布时由 {@code FormDefServiceImpl.publish()} 回填，
     * 格式：{@code {"fieldName":"sw_form_table_abc123","fieldName2":"sw_form_table_def456"}}。
     * 表单提交时根据此映射定位子表写入。
     * </p>
     */
    private String subTableMapping;
}
