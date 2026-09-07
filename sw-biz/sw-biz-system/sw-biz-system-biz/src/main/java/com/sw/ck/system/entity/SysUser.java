package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

/**
 * 系统用户表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /** 用户名 */
    @TableField("username")
    private String username;

    /** 密码（BCrypt 散列）；仅服务端校验使用，任何 HTTP 响应不得回传（R9 脱敏）。 */
    @TableField("password")
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 真实姓名 */
    @TableField("real_name")
    private String realName;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 手机号 */
    @TableField("phone")
    private String phone;

    /** 性别：0=未知 1=男 2=女 */
    @TableField("sex")
    private Integer sex;

    /** 状态：0=正常 1=停用 2=锁定 */
    @TableField("status")
    private Integer status;

    /** 所属部门 ID */
    @TableField("dept_id")
    private Long deptId;

    /** 是否超管：0=否 1=是 */
    @TableField("is_admin")
    private Integer isAdmin;

    /** 头像 URL（V1 DDL 已有 `avatar varchar(200)` 列，此处补映射） */
    @TableField("avatar")
    private String avatar;

    @TableField(exist = false)
    private List<Long> roleIds;

    @TableField(exist = false)
    private List<Long> postIds;
}
