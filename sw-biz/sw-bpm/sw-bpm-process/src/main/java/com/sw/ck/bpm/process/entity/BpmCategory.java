package com.sw.ck.bpm.process.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 流程分类（v0.0.2 流程中心，单层）。
 * <p>
 * 租户内单层分类：名称 + 排序。删除约束（有事项归属须先解除）由服务层校验。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_category")
public class BpmCategory extends BaseEntity {

    /** 分类名称（租户内唯一由服务层校验） */
    @TableField("name")
    private String name;

    /** 排序号（小在前） */
    @TableField("sort_no")
    private Integer sortNo;
}
