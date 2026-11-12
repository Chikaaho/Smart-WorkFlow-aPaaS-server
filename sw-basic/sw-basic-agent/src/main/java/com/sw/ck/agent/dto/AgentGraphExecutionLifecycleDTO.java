package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentGraphExecutionLifecycleDTO {
    private Long executionId;
    private Long graphDefId;
    private Integer graphDefVersion;
    private String status;
    private String input;
    private String output;
    private String errorCategory;
    private String errorMessage;
    private Long latencyMs;
    /** 结果执行记录 id（G1-7：前端用它读取节点轨迹 /agent/graph-executions/{id}/nodes） */
    private Long resultExecutionId;
    /** 结构化出参（G1-4：END 节点 outputs 声明解析结果） */
    private java.util.Map<String, String> outputs;
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cancelRequestedAt;
}
