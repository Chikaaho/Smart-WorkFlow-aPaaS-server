package com.sw.ck.agent.dto;

import lombok.Data;

/**
 * 图执行节点明细 DTO（M07 Step12，只读端点）。
 * <p>
 * 对应解释器 {@code NodeExecutionTrace} 采集的节点级轨迹：全局步序/并行分支标识/
 * 节点类型/节点级耗时/变量表快照（JSON 字符串）。nodeSeq 升序返回。
 * </p>
 */
@Data
public class AgentGraphExecutionNodeDTO {

    /** 本次执行内全局访问步序（1-based，含 END） */
    private Integer nodeSeq;

    /** 并行分支标识（"0" 根路径；FORK 按出边顺序追加下标） */
    private String branchId;

    /** 图节点 id */
    private String nodeId;

    /** 节点类型（START/LLM/TOOL/CONDITION/LOOP/FORK/JOIN/END） */
    private String nodeType;

    /** 节点级耗时（毫秒） */
    private Long nodeLatencyMs;

    /** 该节点执行后的变量表快照（JSON） */
    private String variableSnapshot;

    /** 该节点 LLM 调用的输入 Token（非 LLM 节点或供应商未返回时为 null） */
    private Long inputTokens;

    /** 该节点 LLM 调用的输出 Token（非 LLM 节点或供应商未返回时为 null） */
    private Long outputTokens;

    /** 该节点内工具调用轨迹（JSON 数组：tool/arguments/result，无工具调用时为 null） */
    private String toolCalls;

    /** 该节点真实输入（LLM=实际发送的用户消息；TOOL=工具入参文本） */
    private String input;

    /** 该节点真实输出（LLM=模型最终回答；TOOL=工具返回文本） */
    private String output;

    /** 节点开始时间 */
    private java.time.LocalDateTime startTime;

    /** 节点结束时间 */
    private java.time.LocalDateTime endTime;

    /** 节点执行状态：SUCCESS / FAILED */
    private String status;

    /** 子图节点：子执行记录 id（G3-4 父子跳转） */
    private Long childExecutionId;

    /** 节点失败原因（成功时为 null） */
    private String errorMessage;
}
