package com.sw.ck.agent.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AgentGraphExecutionAcceptedVO {
    private Long executionId;
    private String status;
}
