package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户工作台布局（v0.0.2 OA，P54）。
 * <p>
 * 每租户每用户一行；{@code layout_json} 保存组件显隐/顺序与常用事项键。
 * 常用事项存事项稳定标识（processKey），可执行性由目录/绑定链路实时校验，
 * 失效事项不成为可执行入口。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user_workspace")
public class SysUserWorkspace extends BaseEntity {

    /** 用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 布局 JSON（组件显隐/顺序 + 常用事项键） */
    @TableField("layout_json")
    private String layoutJson;
}
