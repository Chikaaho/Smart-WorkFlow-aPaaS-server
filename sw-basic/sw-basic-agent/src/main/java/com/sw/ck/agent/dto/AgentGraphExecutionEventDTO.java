package com.sw.ck.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentGraphExecutionEventDTO {
    private Long sequence;
    private String type;
    private String nodeId;
    private String payload;
    private LocalDateTime createdAt;
}
