package com.sw.ck.agent.service;

import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDetailDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;

import java.util.List;

/**
 * Agent 图执行 Service（M07-F02 Step8 图解释执行引擎第一版 + Step12 执行历史持久化）。
 * <p>
 * 消费 Step7 的图定义（{@code sw_agent_graph_def} 的 {@code graph_json}）：校验图处于
 * 发布态 + 执行前最小校验（方案 §2-D）→ 调 {@code AgentGraphInterpreter} 解释执行。
 * 执行失败以 {@code success=false} 返回（不上抛），与 F01 run() 语义一致。
 * </p>
 * <p>
 * <b>Step12 执行历史</b>：每次进入执行阶段的调用（校验通过后）产生一条持久化执行
 * 记录（成功/失败两路径均落库）+ 节点级执行轨迹明细；查询端点复用
 * {@code agent:model:view} 权限惯例。
 * </p>
 */
public interface AgentGraphExecutionService {

    /**
     * 执行一次已发布图（Step12 起：执行前后包夹持久化写入，成功/失败均落库）。
     *
     * @param graphDefId 图定义 id（不存在/跨租户 → NOT_FOUND，同 Step7 requireEntity）
     * @param input      执行入参文本（初始累积文本，空白 → PARAM_ERROR）
     * @return 执行结果（success/output/errorMessage/latencyMs + 新增 executionId）
     */
    AgentGraphExecuteRespDTO execute(Long graphDefId, String input);

    /**
     * 执行一次已发布图（G1-4 结构化入参/出参）。
     * <p>
     * {@code inputs} 为开始节点参数名 → 值；缺必填参数时在执行记录落库前以
     * {@code success=false} + 明确参数名返回。出参按 END 节点 outputs 声明解析
     * （source = nodeId.var 或全局变量名）。
     * </p>
     *
     * @param graphDefId 图定义 id
     * @param input      旧契约文本入参（可为 null/空白，此时以 inputs 为准）
     * @param inputs     结构化入参（null/空 = 旧契约路径）
     */
    default AgentGraphExecuteRespDTO execute(Long graphDefId, String input,
                                             java.util.Map<String, String> inputs) {
        return execute(graphDefId, input);
    }

    /**
     * 执行历史列表（Step12，分页，create_time 倒序；可按图定义过滤）。
     * 租户隔离经租户拦截器自动生效；不做用户级过滤（设计器/运维视角的租户内全部执行）。
     */
    PageResult<AgentGraphExecutionDTO> pageExecutions(PageParam pageParam, Long graphDefId);

    /**
     * 执行详情（Step12，含 input/output 大字段；不存在/跨租户 → NOT_FOUND）。
     */
    AgentGraphExecutionDetailDTO getExecution(Long executionId);

    /**
     * 节点执行明细（Step12，nodeSeq 升序；执行记录不存在/跨租户 → NOT_FOUND）。
     */
    List<AgentGraphExecutionNodeDTO> listExecutionNodes(Long executionId);
}
