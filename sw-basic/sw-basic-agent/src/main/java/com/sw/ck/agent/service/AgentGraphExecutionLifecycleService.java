package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionLifecycleDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionEventDTO;

import java.util.List;

public interface AgentGraphExecutionLifecycleService {
    AgentGraphExecutionAcceptedDTO submit(Long graphDefId, String input, String idempotencyKey);
    AgentGraphExecutionLifecycleDTO get(Long executionId);
    AgentGraphExecutionLifecycleDTO cancel(Long executionId);
    List<AgentGraphExecutionEventDTO> listEvents(Long executionId, long afterSequence);
}
