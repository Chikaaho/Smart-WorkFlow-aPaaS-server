package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * P64 阶段Ⅱ（A07）源岗位→受托岗位委托关系（组织域通用配置）。
 * <p>
 * scope_type：ORG（显式组织默认范围）/ DEPT（精确部门范围，优先于 ORG）；
 * status：ENABLED / DISABLED。每个源岗位/适用范围最多一条有效关系，
 * 自委托/循环/跨租户/越权/同优先级重叠在配置与生效时由服务层拒绝。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_post_delegate")
public class SysPostDelegate extends BaseEntity {

    /** 源岗位 ID（sys_post.id，同租户）。 */
    @TableField("source_post_id")
    private Long sourcePostId;

    /** 受托岗位 ID（sys_post.id，同租户）。 */
    @TableField("target_post_id")
    private Long targetPostId;

    /** 适用范围：ORG / DEPT。 */
    @TableField("scope_type")
    private String scopeType;

    /** 精确部门 ID（scope_type=DEPT 时必填；ORG 时为空）。 */
    @TableField("dept_id")
    private Long deptId;

    /** ENABLED / DISABLED。 */
    @TableField("status")
    private String status;

    /** 备注。 */
    @TableField("remark")
    private String remark;

    public static final String SCOPE_ORG = "ORG";
    public static final String SCOPE_DEPT = "DEPT";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
}
