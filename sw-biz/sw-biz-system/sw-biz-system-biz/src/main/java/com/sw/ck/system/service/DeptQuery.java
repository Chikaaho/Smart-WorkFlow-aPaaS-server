package com.sw.ck.system.service;

import lombok.Data;

/**
 * 部门树条件查询参数。
 * <p>
 * {@code name}：部门名称包含匹配（LIKE %name%），trim 后为空/空白等价于未填写；
 * {@code status}：部门状态 0=正常 1=停用，null 表示不按状态过滤（全部），
 * 非 0/1 的非法值由 Service 层显式拒绝（PARAM_ERROR）。
 * </p>
 */
@Data
public class DeptQuery {

    /** 部门名称（包含匹配，空白视为未填写） */
    private String name;

    /** 部门状态：0=正常 1=停用；null=全部 */
    private Integer status;
    private Long tenantId;
}
