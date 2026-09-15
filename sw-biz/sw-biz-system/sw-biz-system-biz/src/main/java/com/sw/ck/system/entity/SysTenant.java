package com.sw.ck.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 租户表（I5）。sys_tenant 为全局表（无租户隔离语义），查询必须挂起租户过滤。
 * <p>
 * status：0=启用 1=停用；expire_time 为空表示永不过期。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    /** 租户名称 */
    @TableField("name")
    private String name;

    /** 租户编码（全局唯一） */
    @TableField("code")
    private String code;

    /** 状态：0=启用 1=停用 */
    @TableField("status")
    private Integer status;

    /** 描述 */
    @TableField("description")
    private String description;

    /** 联系人 */
    @TableField("contact_name")
    private String contactName;

    /** 联系电话 */
    @TableField("contact_phone")
    private String contactPhone;

    /** 联系邮箱 */
    @TableField("contact_email")
    private String contactEmail;

    /** 有效期截止；null=永不过期 */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /** 绑定域名（休眠字段，I5 不启用解析） */
    @TableField("domain_name")
    private String domainName;
}
