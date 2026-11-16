package com.sw.ck.agent.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图执行详情 DTO（M07 Step12，只读端点）。
 * <p>
 * 含 {@code input/output} 大字段全文（详情端点专用，与列表 DTO 的大字段剥离防线
 * 互补）；节点级明细经独立端点 {@code /{executionId}/nodes} 获取。
 * </p>
 */
@Data
public class AgentGraphExecutionDetailVO {

    /** 执行记录 id（雪花） */
    private Long id;

    /** 图定义 id */
    private Long graphDefId;

    /** 执行时图定义版本快照 */
    private Integer graphDefVersion;

    /** 执行状态：RUNNING / SUCCESS / FAILED */
    private String status;

    /** 执行入参文本 */
    private String input;

    /** 最终输出文本（成功时） */
    private String output;

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

    /** 创建时间（执行发起时间） */
    private LocalDateTime createTime;

    /** 更新时间（终态回写时间） */
    private LocalDateTime updateTime;
}
