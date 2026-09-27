package com.sw.ck.system.controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 菜单管理条目 VO（V012-BUG-011/017）：菜单管理页数据源。
 * <p>
 * 仅目录/菜单两类（按钮行不下发）；字段为管理页展示与编辑的最小集合。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuManageVO implements Serializable {

    /** 菜单 ID（bigint → string） */
    private String id;

    /** 父菜单 ID；根节点输出 null */
    private String parentId;

    /** 菜单名称（路由 name，只读） */
    private String name;

    /** 显示标题（可编辑） */
    private String title;

    /** 路由路径（只读） */
    private String path;

    /** 图标（可编辑；通用图标库白名单键名） */
    private String icon;

    /** 排序（可编辑） */
    private Integer sort;

    /** 是否隐藏（可编辑） */
    private Boolean hidden;

    /** 0=目录 1=菜单 */
    private Integer menuType;
}
