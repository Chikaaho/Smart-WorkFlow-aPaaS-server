package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionLifecycleDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionEventDTO;
import com.sw.ck.agent.dto.AgentHumanTaskDTO;

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

    /** 提交人工任务（G3-2）：处理人填写并选择决策后继续执行。 */
    AgentGraphExecutionLifecycleDTO submitHumanTask(Long executionId, Long taskId,
                                                    java.util.Map<String, String> values,
                                                    String decision);

    /** 待我处理列表（G3-2：按当前用户/角色匹配的 PENDING 人工任务）。 */
    List<AgentHumanTaskDTO> listMyHumanTasks();

    /** 指定执行的人工任务列表（执行详情展示等待/已处理）。 */
    List<AgentHumanTaskDTO> listExecutionHumanTasks(Long executionId);

    /** 人工任务超时扫描（G3-3：由定时器周期调用）。 */
    void scanHumanTaskTimeouts();
}
