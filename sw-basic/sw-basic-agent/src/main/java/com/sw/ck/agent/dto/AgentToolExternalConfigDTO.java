package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部 HTTP 工具白名单配置 DTO（新增/编辑请求 + 响应共用）。
 * <p>
 * 字段与 {@code sw_agent_tool_external} 表对应；{@code url}/{@code httpMethod}
 * 为白名单值，仅管理员写入，不来自 LLM/用户请求。
 * </p>
 */
@Data
public class AgentToolExternalConfigDTO {

    private Long id;

    /** 工具名（英文下划线），传给 LLM */
    private String name;

    /** 工具描述，传给 LLM */
    private String description;

    /** JSON Schema 字符串，描述入参结构（可为 null） */
    private String inputSchema;

    /** 白名单 URL（完整 URL，含路径） */
    private String url;

    /** HTTP 方法：GET/POST/PUT（默认 POST） */
    private String httpMethod;

    /**
     * 副作用分类：QUERY=查询且无外部写副作用，WRITE=有外部写副作用；
     * 空=未分类（表单运行期不可调用）。
     */
    private String effectScope;

    /** 请求超时（秒），默认 30 */
    private Integer timeoutSeconds;

    /** 1=启用 0=禁用 */
    private Boolean enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
