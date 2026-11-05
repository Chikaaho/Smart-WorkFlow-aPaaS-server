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
    private LocalDateTime createTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cancelRequestedAt;
}
