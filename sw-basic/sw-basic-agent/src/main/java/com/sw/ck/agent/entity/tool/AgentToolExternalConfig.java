package com.sw.ck.agent.entity.tool;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.agent.typehandler.BooleanSmallintTypeHandler;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 外部 HTTP 工具白名单配置（M07 Step3 工具沙箱）。
 * <p>
 * 安全边界：{@code url}/{@code httpMethod} 为白名单值，仅由管理员写入本表；
 * LLM/用户运行时只能传工具名（{@code name}），名称 → (url, httpMethod) 映射
 * 在 {@code AgentToolCallbackFactory} 内部完成，禁止任意 URL 调用（禁 SSRF）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_agent_tool_external", autoResultMap = true)
public class AgentToolExternalConfig extends BaseEntity {

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
     * 空=未分类（表单运行期不可调用，须由配置者显式登记）。
     */
    private String effectScope;

    /** 请求超时（秒），默认 30（参照 ChatModelFactory 模式从 DB 读） */
    private Integer timeoutSeconds;

    /** 1=启用 0=禁用（PG smallint 列，按 0/1 写入） */
    @TableField(value = "enabled", typeHandler = BooleanSmallintTypeHandler.class)
    private Boolean enabled;

    private String remark;
}
