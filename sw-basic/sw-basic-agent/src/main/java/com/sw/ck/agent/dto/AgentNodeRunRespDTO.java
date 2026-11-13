package com.sw.ck.agent.dto;

import lombok.Data;

/** 单节点运行结果（G1-8）：调试语义，不落正式执行记录。 */
@Data
public class AgentNodeRunRespDTO {

    /** 是否成功 */
    private boolean success;

    /** 节点状态（SUCCESS / FAILED） */
    private String status;

    /** 节点输出文本（LLM 回答 / 工具返回 / 检索摘要） */
    private String output;

    /** 失败原因（不含密钥） */
    private String errorMessage;

    /** 节点耗时（毫秒） */
    private long latencyMs;
}
