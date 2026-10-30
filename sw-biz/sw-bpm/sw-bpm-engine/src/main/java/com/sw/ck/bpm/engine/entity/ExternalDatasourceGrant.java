package com.sw.ck.bpm.engine.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 外部数据源引用授权主体（主库表，继承 BaseEntity 走租户隔离）。
 * <p>
 * direction-020：设计人员仅能引用已授权的稳定数据源/查询标识；本表是
 * 数据源管理员维护的授权绑定，主体约定与表单字段权限一致
 * （{@code ROLE} + 角色编码 / {@code USER} + 用户 ID 字符串）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_bpm_ext_datasource_grant")
public class ExternalDatasourceGrant extends BaseEntity {

    /** 主体类型：角色授权 */
    public static final String SUBJECT_ROLE = "ROLE";
    /** 主体类型：用户授权 */
    public static final String SUBJECT_USER = "USER";

    /** 授权的外部数据源 ID */
    @TableField("datasource_id")
    private Long datasourceId;

    /** 主体类型：ROLE / USER */
    @TableField("subject_type")
    private String subjectType;

    /** 主体标识：ROLE=角色编码；USER=用户 ID 字符串 */
    @TableField("subject_key")
    private String subjectKey;
}
