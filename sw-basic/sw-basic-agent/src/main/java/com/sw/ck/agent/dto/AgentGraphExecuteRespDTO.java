package com.sw.ck.agent.dto;

import lombok.Data;

import java.util.Map;

/**
 * 图执行响应 DTO（M07-F02 Step8 + Step12 执行历史）。
 * <p>
 * 语义对齐 F01 {@code AgentOrchestrationRunRespDTO}：图执行运行时错误（条件无匹配且
 * 无默认边 / 步数超限 / 模型或工具调用异常）以 {@code success=false} + 非空
 * {@code errorMessage} 表达，不上抛（与 F01 run() success=false 语义一致）；
 * {@code errorMessage} 只含异常摘要，绝不包含明文 API Key。
 * </p>
 * <p>
 * Step12 追加 {@code executionId}（执行历史记录 id，成功/失败均存在）——纯追加字段，
 * 不改变既有四字段（success/output/errorMessage/latencyMs）语义（方向文档 §3 允许）。
 * </p>
 */
@Data
public class AgentGraphExecuteRespDTO {

    /** 是否执行成功 */
    private boolean success;

    /** 最终输出文本（END 节点 config.inputVar 指定变量的值，缺失/空白 = 默认变量；成功时非空） */
    private String output;

    /** 失败原因摘要（不含明文 API Key） */
    private String errorMessage;

    /** 失败分类（X3：与执行记录 error_category 同源，如 HUMAN_TIMEOUT；成功为空） */
    private String errorCategory;

    /** 执行耗时（毫秒） */
    private long latencyMs;

    /** 执行历史记录 id（Step12，sw_agent_graph_execution 雪花 id，成功/失败均返回） */
    private Long executionId;

    /** 结构化出参（G1-4：END 节点 outputs 声明解析结果；未声明时为空 map） */
    private Map<String, String> outputs;
}
