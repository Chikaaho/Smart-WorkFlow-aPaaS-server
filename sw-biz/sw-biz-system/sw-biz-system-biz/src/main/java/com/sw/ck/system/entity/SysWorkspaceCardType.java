package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 租户级工作台卡片类型定义。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_workspace_card_type")
public class SysWorkspaceCardType extends BaseEntity {

    /** 稳定的卡片类型编码，用户布局通过它关联卡片类型。 */
    @TableField("type_code")
    private String typeCode;

    /** 卡片展示名称。 */
    @TableField("display_name")
    private String displayName;

    /** 前端安全注册表中的渲染器键，不是组件路径。 */
    @TableField("renderer_key")
    private String rendererKey;

    /** 卡片默认元数据 JSON。 */
    @TableField("metadata_json")
    private String metadataJson;

    /** 默认宽度：1=半宽，2=整行。 */
    @TableField("default_span")
    private Integer defaultSpan;

    /** 默认排序。 */
    @TableField("default_order")
    private Integer defaultOrder;

    /** 状态：0=启用，1=停用。 */
    @TableField("status")
    private Integer status;
}
