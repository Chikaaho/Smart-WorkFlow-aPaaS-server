package com.sw.ck.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图执行历史列表项/摘要 DTO（M07 Step12，只读端点）。
 * <p>
 * 不含 {@code input/output} 大字段（编译期防线，对齐 {@code pageDefs} 剥离 graphJson
 * 先例）；{@code errorMessage} 为失败原因摘要（实际为短文本，列表展示失败原因有用），
 * 大文本全文经详情端点获取。
 * </p>
 */
@Data
public class AgentGraphExecutionVO {

    /** 执行记录 id（雪花） */
    private Long id;

    /** 图定义 id */
    private Long graphDefId;

    /** 执行时图定义版本快照 */
    private Integer graphDefVersion;

    /** 执行状态：RUNNING / SUCCESS / FAILED */
    private String status;

    /** 错误分类（失败时） */
    private String errorCategory;

    /** 失败原因摘要（不含明文 API Key） */
    private String errorMessage;

    /** 整次执行耗时（毫秒） */
    private Long latencyMs;

    /** 本次图执行全部 LLM 节点输入 Token 汇总（未知时为 null） */
    private Long inputTokens;

    /** 本次图执行全部 LLM 节点输出 Token 汇总（未知时为 null） */
    private Long outputTokens;
    /** 业务来源（1.2.0 E1-7：PROCESS/FORM/DIRECT；受理行为 null 时按 DIRECT） */
    private String businessSource;

    /** 创建时间（执行发起时间） */
    private LocalDateTime createTime;
}
