package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionLifecycleDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionEventDTO;

import java.util.List;

public interface AgentGraphExecutionLifecycleService {
    /**
     * 受理执行（G1-4：支持结构化入参）。
     *
     * @param graphDefId     图定义 id
     * @param input          旧契约单文本入参（写入默认变量 input；与 inputs 至少一个非空）
     * @param inputs         结构化入参（开始节点参数名 → 值；缺必填参数由执行层拒绝）
     * @param idempotencyKey 幂等键（可空）
     */
    AgentGraphExecutionAcceptedDTO submit(Long graphDefId, String input,
                                          java.util.Map<String, String> inputs, String idempotencyKey);

    /** 旧三参入口（IoT 桥等既有调用方）：等价于 inputs = null。 */
    default AgentGraphExecutionAcceptedDTO submit(Long graphDefId, String input, String idempotencyKey) {
        return submit(graphDefId, input, null, idempotencyKey);
    }
    AgentGraphExecutionLifecycleDTO get(Long executionId);
    AgentGraphExecutionLifecycleDTO cancel(Long executionId);
    List<AgentGraphExecutionEventDTO> listEvents(Long executionId, long afterSequence);
}
