package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 应用目录实体 — 对应 {@code sw_form_app_dir} 表（应用内二级分类，单层）。
 * <p>
 * 目录只属于一个应用；目录继承应用可见性，不单独定义可见范围。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_app_dir")
public class FormAppDirEntity extends FormBaseEntity {

    /** 所属应用 ID（sw_form_app.id） */
    private String appId;

    /** 目录名称 */
    private String name;

    /** 排序号（升序） */
    private Integer sortNo;
}
