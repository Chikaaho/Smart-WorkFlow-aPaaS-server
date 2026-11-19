package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import com.sw.ck.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 用户组主记录（租户内扁平虚拟用户集合）。
 * <p>
 * 语义（D112 规划裁定）：非部门树节点、无层级/负责人；业务标识 {@code groupCode}
 * 租户内稳定唯一，创建后不可随意改变；{@code status} 0=启用 1=停用；
 * 逻辑删除（{@code deleted}）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_group")
public class SysUserGroup extends BaseEntity {

    /** 业务标识（租户内唯一，创建后不可随意改变） */
    @TableField("group_code")
    private String groupCode;

    /** 展示名称 */
    @TableField("group_name")
    private String groupName;

    /** 状态：0=启用 1=停用 */
    @TableField("status")
    private Integer status;

    /** 说明 */
    @TableField("remark")
    private String remark;

    /** 成员用户 ID 列表（非持久字段，回填用） */
    @TableField(exist = false)
    private List<Long> memberIds;
}
