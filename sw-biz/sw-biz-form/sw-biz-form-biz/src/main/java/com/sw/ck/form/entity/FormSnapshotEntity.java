package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.sw.ck.form.handler.JsonStringTypeHandler;
import org.apache.ibatis.type.JdbcType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单版本快照实体 — 对应 {@code sw_form_snapshot} 表。
 * <p>
 * 每次发布存一版 definition JSON，用于版本回溯和审核。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_form_snapshot", autoResultMap = true)
public class FormSnapshotEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id */
    private String formId;

    /** 快照版本号（与 sw_form_def.form_version 对齐） */
    private Integer formVersion;

    /** 该版本的完整 definition JSON */
    @TableField(value = "definition", jdbcType = JdbcType.OTHER,
            typeHandler = JsonStringTypeHandler.class)
    private String definition;
}
