package com.sw.ck.notify.dto;

import lombok.Data;

/**
 * 消息模板管理 DTO（请求与响应共用，沿用 AgentToolConfigDTO 无 bean-validation 惯例，
 * 校验在 Service 层手动完成）。
 */
@Data
public class NotifyTemplateDTO {

    /** 主键（新建时为空） */
    private Long id;

    /** 稳定模板代码，同租户唯一；创建后编辑不得变更 */
    private String templateCode;

    /** 模板名称 */
    private String name;

    /** 标题模板（${var} 占位符） */
    private String titleTemplate;

    /** 正文模板（${var} 占位符） */
    private String contentTemplate;

    /** 1=启用 0=停用 */
    private Boolean enabled;

    /** 备注 */
    private String remark;


    /* I6 扩展字段 */
    /** 事件类型 */
    private String eventType;
    /** 渠道 */
    private String channel;
    /** 变量白名单（逗号分隔；空=允许全部合法变量名） */
    private String variablesAllowed;
    /** 受控跳转目标引用 */
    private String jumpRef;
}
