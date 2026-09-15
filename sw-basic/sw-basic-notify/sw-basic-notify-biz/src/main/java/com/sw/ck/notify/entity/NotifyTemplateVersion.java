package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模板版本快照（I6）。
 * <p>发布后版本追加且不可被后续编辑静默改写；历史投递固定引用
 * {@code template_id + template_version}，渲染始终来自本表快照。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_template_version")
public class NotifyTemplateVersion extends BaseEntity {

    @TableField("template_id")
    private Long templateId;

    @TableField("template_version")
    private Integer templateVersion;

    @TableField("event_type")
    private String eventType;

    @TableField("channel")
    private String channel;

    @TableField("title_template")
    private String titleTemplate;

    @TableField("content_template")
    private String contentTemplate;

    /** 变量白名单（逗号分隔合法变量名；空=允许全部合法变量名） */
    @TableField("variables_allowed")
    private String variablesAllowed;

    /** 受控跳转目标引用（仅允许登记的稳定目标标识） */
    @TableField("jump_ref")
    private String jumpRef;

    @TableField("status")
    private String status;
}
