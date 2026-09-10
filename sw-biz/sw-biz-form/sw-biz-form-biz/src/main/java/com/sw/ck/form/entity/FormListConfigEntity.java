package com.sw.ck.form.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 表单列表展示配置实体 — 对应 {@code sw_form_list_config} 表（I2，方向 §4.5）。
 * <p>
 * 每个表单一行（form_id 唯一）；config_json 存储：
 * 列字段与顺序、可用筛选、默认排序与允许动作。
 * 刷新、重登、PC/移动与不同授权用户按服务端回读稳定生效。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_form_list_config")
public class FormListConfigEntity extends FormBaseEntity {

    /** 关联 sw_form_def.id（唯一） */
    private String formId;

    /** 列表配置 JSON（columns/filters/defaultSort/actions） */
    private String configJson;
}
