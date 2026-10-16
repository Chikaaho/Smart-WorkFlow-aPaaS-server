package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 应用新建/更新请求。
 * <p>
 * {@code visibleScopeType}：ALL=本租户全部用户（必须显式选择）/
 * SPECIFIED=按维度指定（各维度集合全空表示无人可见，不得误作全员授权）。
 * 0.1.7 #1：SPECIFIED 支持用户/部门/角色/岗位/租户多维度，任一维度命中即可见。
 * </p>
 */
@Data
public class FormAppSaveReq implements Serializable {

    /** 应用名称（必填，同租户内不重名） */
    private String name;

    /** 图标标识（可空取默认） */
    private String icon;

    /** 主题色（可空取默认） */
    private String color;

    /** 排序号（可空按 0） */
    private Integer sortNo;

    /** 可见范围：ALL / SPECIFIED（缺省 SPECIFIED） */
    private String visibleScopeType;

    /** 指定可见用户集合（SPECIFIED 时生效；USER 维度） */
    private List<Long> userIds;

    /** 指定可见部门集合（0.1.7 #1；DEPT 维度，按用户主属部门命中） */
    private List<Long> deptIds;

    /** 指定可见角色 code 集合（0.1.7 #1；ROLE 维度，与 LoginUser.roles 同口径） */
    private List<String> roleCodes;

    /** 指定可见岗位集合（0.1.7 #1；POST 维度，按 sys_user_post 关联命中） */
    private List<Long> postIds;

    /** 指定可见租户集合（0.1.7 #1；TENANT 维度，整租户可见） */
    private List<Long> tenantIds;
}
