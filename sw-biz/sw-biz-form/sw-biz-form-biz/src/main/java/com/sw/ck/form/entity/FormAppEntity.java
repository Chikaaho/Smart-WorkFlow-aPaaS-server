package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务应用实体 — 对应 {@code sw_form_app} 表（应用中心与表单分类统一）。
 * <p>
 * 应用承担原表单一级分类的管理职责：应用（一级分类）→ 目录（二级分类）→ 表单。
 * {@code sw_form_def.app_id} 指向本表，归属唯一。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_app")
public class FormAppEntity extends FormBaseEntity {

    /** 应用名称 */
    private String name;

    /** 图标标识（前端图标名，可空取默认；ALWAYS 允许清空） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String icon;

    /** 主题色（可空取默认；ALWAYS 允许清空） */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String color;

    /** 排序号（升序） */
    private Integer sortNo;

    /**
     * 可见范围：ALL=本租户全部用户（显式选择）/
     * SPECIFIED=指定用户（{@code sw_form_app_user} 关联集合空=无人可见）。
     */
    private String visibleScopeType;

    /** 状态：ENABLED / DISABLED（停用阻止新增入口，保留数据与历史） */
    private String status;

    /** 来源：MANUAL=后台新建 / LEGACY_CATEGORY=存量分类承接 / LEGACY_UNCATEGORIZED=存量未分类承接 */
    private String source;

    /** 承接来源分类 ID（source=LEGACY_CATEGORY 时有效，可追溯） */
    private Long legacyCategoryId;
}
