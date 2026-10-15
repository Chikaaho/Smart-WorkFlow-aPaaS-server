package com.sw.ck.form.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 应用新建/更新请求。
 * <p>
 * {@code visibleScopeType}：ALL=本租户全部用户（必须显式选择）/
 * SPECIFIED=指定用户（{@code userIds} 为空表示无人可见，不得误作全员授权）。
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

    /** 指定可见用户集合（SPECIFIED 时生效；空集=无人可见） */
    private List<Long> userIds;
}
