package com.sw.ck.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 内部工具白名单配置 DTO（新增/编辑请求 + 响应共用）。
 * <p>
 * 字段与 {@code sw_agent_tool_internal} 表对应；{@code beanName}/{@code methodName}
 * 为白名单值，仅管理员写入，不来自 LLM/用户请求。
 * </p>
 */
@Data
public class AgentToolInternalConfigVO {

    private Long id;

    /** 工具名（英文下划线），传给 LLM */
    private String name;

    /** 工具描述，传给 LLM */
    private String description;

    /** JSON Schema 字符串，描述入参结构（可为 null） */
    private String inputSchema;

    /** Spring bean 名称（白名单值） */
    private String beanName;

    /** 方法名（白名单值，约定签名 String execute(String params)） */
    private String methodName;

    /** 1=启用 0=禁用 */
    private Boolean enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
