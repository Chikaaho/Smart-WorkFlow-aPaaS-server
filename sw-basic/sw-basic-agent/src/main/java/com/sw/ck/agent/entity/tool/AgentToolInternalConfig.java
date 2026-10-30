package com.sw.ck.agent.entity.tool;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sw.ck.agent.typehandler.BooleanSmallintTypeHandler;
import com.sw.ck.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内部工具白名单配置（M07 Step3 工具沙箱）。
 * <p>
 * 安全边界：{@code beanName}/{@code methodName} 为白名单值，仅由管理员写入本表；
 * LLM/用户运行时只能传工具名（{@code name}），名称 → (beanName, methodName) 映射
 * 在 {@code AgentToolCallbackFactory} 内部完成，杜绝任意 bean 访问（禁 RCE）。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sw_agent_tool_internal", autoResultMap = true)
public class AgentToolInternalConfig extends BaseEntity {

    /** 工具名（英文下划线），传给 LLM */
    private String name;

    /** 工具描述，传给 LLM */
    private String description;

    /** JSON Schema 字符串，描述入参结构（可为 null，由 inputType 生成兜底 schema） */
    private String inputSchema;

    /** Spring bean 名称（白名单值） */
    private String beanName;

    /** 方法名（白名单值，约定签名 String execute(String params)） */
    private String methodName;

    /** 1=启用 0=禁用（PG smallint 列，按 0/1 写入） */
    @TableField(value = "enabled", typeHandler = BooleanSmallintTypeHandler.class)
    private Boolean enabled;

    private String remark;
}
