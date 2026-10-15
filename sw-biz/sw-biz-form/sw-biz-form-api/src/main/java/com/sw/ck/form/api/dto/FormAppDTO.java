package com.sw.ck.form.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务应用 DTO（应用中心与表单分类统一）。
 * <p>
 * 管理视角含停用应用与全部计数；用户端视角只含启用且当前用户可见的应用，
 * 计数只统计当前用户可用（已发布且可见）的业务表单，不暴露受限表单名称或数量。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormAppDTO implements Serializable {

    /** 应用 ID（承接存量分类为 lc&lt;categoryId&gt;，未分类承接为 uncat-t&lt;tenantId&gt;，新建为 UUID） */
    private String id;

    /** 应用名称 */
    private String name;

    /** 图标标识（前端图标名，可空取默认） */
    private String icon;

    /** 主题色（可空取默认） */
    private String color;

    /** 排序号（升序） */
    private Integer sortNo;

    /** 可见范围：ALL=本租户全部用户 / SPECIFIED=指定用户 */
    private String visibleScopeType;

    /** 指定可见用户集合（visibleScopeType=SPECIFIED 时下发；空集=无人可见） */
    private List<Long> visibleUserIds;

    /** 状态：ENABLED / DISABLED */
    private String status;

    /** 来源：MANUAL / LEGACY_CATEGORY / LEGACY_UNCATEGORIZED（承接追溯） */
    private String source;

    /** 承接来源分类 ID（source=LEGACY_CATEGORY 时有效） */
    private Long legacyCategoryId;

    /** 应用内表单数（管理视角=全部业务表单；用户端视角=当前用户可用表单） */
    private Long formCount;

    /** 应用内目录数 */
    private Long dirCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
