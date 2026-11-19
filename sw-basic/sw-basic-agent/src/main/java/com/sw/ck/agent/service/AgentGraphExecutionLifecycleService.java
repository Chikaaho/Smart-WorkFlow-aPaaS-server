package com.sw.ck.agent.service;

import com.sw.ck.agent.vo.AgentGraphExecutionAcceptedVO;
import com.sw.ck.agent.vo.AgentGraphExecutionLifecycleVO;
import com.sw.ck.agent.vo.AgentGraphExecutionEventVO;
import com.sw.ck.agent.vo.AgentHumanTaskVO;

import java.util.List;
import java.util.Map;

public interface AgentGraphExecutionLifecycleService {
    /**
     * 受理执行（G1-4：支持结构化入参）。
     *
     * @param graphDefId     图定义 id
     * @param input          旧契约单文本入参（写入默认变量 input；与 inputs 至少一个非空）
     * @param inputs         结构化入参（开始节点参数名 → 值；缺必填参数由执行层拒绝）
     * @param idempotencyKey 幂等键（可空）
     */
    AgentGraphExecutionAcceptedVO submit(Long graphDefId, String input,
                                          Map<String, String> inputs, String idempotencyKey);

    /** 旧三参入口（IoT 桥等既有调用方）：等价于 inputs = null。 */
    default AgentGraphExecutionAcceptedVO submit(Long graphDefId, String input, String idempotencyKey) {
        return submit(graphDefId, input, null, idempotencyKey);
    }
    AgentGraphExecutionLifecycleVO get(Long executionId);
    AgentGraphExecutionLifecycleVO cancel(Long executionId);
    List<AgentGraphExecutionEventVO> listEvents(Long executionId, long afterSequence);

    /** 提交人工任务（G3-2）：处理人填写并选择决策后继续执行。 */
    AgentGraphExecutionLifecycleVO submitHumanTask(Long executionId, Long taskId,
                                                    Map<String, String> values,
                                                    String decision);

    /** 待我处理列表（G3-2：按当前用户/角色匹配的 PENDING 人工任务）。 */
    List<AgentHumanTaskVO> listMyHumanTasks();

    /** 指定执行的人工任务列表（执行详情展示等待/已处理）。 */
    List<AgentHumanTaskVO> listExecutionHumanTasks(Long executionId);

    /** 人工任务超时扫描（G3-3：由定时器周期调用）。 */
    void scanHumanTaskTimeouts();
}
