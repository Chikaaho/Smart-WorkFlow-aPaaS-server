package com.sw.ck.bpm.process.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流程中心可发起事项目录项。
 * <p>
 * 稳定标识为 {@code itemKey = processKey}；展示名称为流程定义名称；
 * 关联表单经服务端解析唯一合法绑定，前端不靠名称/路由解析绑定。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogItemDTO {

    /** 稳定事项标识（= processKey） */
    private String itemKey;

    /** 事项展示名称（流程定义名称） */
    private String name;

    /** 关联表单 formKey（服务端从绑定解析） */
    private String formKey;

    /** 归属分类（null=未分类） */
    private Long categoryId;

    /** 流程定义状态：PUBLISHED / DRAFT（仅管理视角可见 DRAFT） */
    private String status;

    /** 绑定表单是否已发布（管理视角展示发布状态用） */
    private Boolean formPublished;

    /** 是否存在 active 绑定（发起路径实际可用） */
    private Boolean bindingActive;
}
