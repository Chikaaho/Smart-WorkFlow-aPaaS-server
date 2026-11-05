package com.sw.ck.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentGraphExecutionAcceptedDTO {
    private Long executionId;
    private String status;
}
