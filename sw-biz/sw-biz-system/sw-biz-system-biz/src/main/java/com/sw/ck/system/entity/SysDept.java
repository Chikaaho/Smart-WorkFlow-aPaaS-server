package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部门表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dept")
public class SysDept extends BaseEntity {

    /** 父部门 ID（0 表示根节点） */
    @TableField("parent_id")
    private Long parentId;

    /** 部门名称 */
    @TableField("name")
    private String name;

    /** 部门编码 */
    @TableField("code")
    private String code;

    /** 排序号 */
    @TableField("sort")
    private Integer sort;

    /** 状态：0=正常 1=停用 */
    @TableField("status")
    private Integer status;

    /** 部门负责人用户 ID（稳定用户标识；流程选人经组织权威解析） */
    @TableField("leader_id")
    private Long leaderId;
}
