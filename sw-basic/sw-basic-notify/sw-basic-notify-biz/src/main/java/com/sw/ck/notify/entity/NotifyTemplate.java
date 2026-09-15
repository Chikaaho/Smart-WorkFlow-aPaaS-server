package com.sw.ck.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息模板实体（P36 / M05-F02-01）。
 * <p>
 * 一条记录 = 一个可复用通知模板。{@code template_code} 同租户唯一，
 * 是发送与外部调用的稳定标识；创建后不因普通编辑改变身份。
 * {@code tenant_id / 审计列 / deleted / version} 由 MyBatis-Plus 拦截器自动注入。
 * </p>
 *
 * <h3>渲染语义</h3>
 * 标题/正文模板均支持 {@code ${var}} 简单占位符；变量按纯文本替换，
 * 不做表达式求值（方向 §3.2 安全边界）。历史落库通知保存渲染结果，
 * 不随模板后续编辑变化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sw_notify_template")
public class NotifyTemplate extends BaseEntity {

    /** 稳定模板代码，同租户唯一（发送标识，编辑不得变更） */
    @TableField("template_code")
    private String templateCode;

    /** 模板名称（展示用） */
    @TableField("name")
    private String name;

    /** 标题模板，支持 ${var} 占位符 */
    @TableField("title_template")
    private String titleTemplate;

    /** 正文模板，支持 ${var} 占位符 */
    @TableField("content_template")
    private String contentTemplate;

    /** 1=启用 0=停用（停用不得预览/发送） */
    @TableField("enabled")
    private Boolean enabled;

    /** 备注 */
    @TableField("remark")
    private String remark;
    /* I6 扩展字段 */
    @TableField("event_type")
    private String eventType;

    @TableField("channel")
    private String channel;

    @TableField("variables_allowed")
    private String variablesAllowed;

    @TableField("jump_ref")
    private String jumpRef;
}
