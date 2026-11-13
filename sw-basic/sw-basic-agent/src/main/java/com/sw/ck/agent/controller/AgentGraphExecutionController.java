package com.sw.ck.agent.controller;

import com.sw.ck.agent.dto.AgentGraphExecutionDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDetailDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO;
import com.sw.ck.agent.dto.AgentNodeRunReqDTO;
import com.sw.ck.agent.dto.AgentNodeRunRespDTO;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 图执行历史查询 Controller（M07 Step12 执行历史持久化，三个只读端点）。
 * <p>
 * 权限码沿用 {@code agent:model:view}（只读操作，不新增权限码，对齐 D51 三段拆分与
 * F04 会话查询先例）；响应统一 {@code R<T>} 包装；租户隔离经租户拦截器自动生效。
 * </p>
 */
@RestController
@RequestMapping("/agent/graph-executions")
public class AgentGraphExecutionController {

    private final AgentGraphExecutionService agentGraphExecutionService;

    public AgentGraphExecutionController(AgentGraphExecutionService agentGraphExecutionService) {
        this.agentGraphExecutionService = agentGraphExecutionService;
    }

    /** 执行历史列表（分页，create_time 倒序；可按图定义 id 过滤；不含 input/output 大字段） */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<PageResult<AgentGraphExecutionDTO>> page(PageParam pageParam,
                                                      @RequestParam(required = false) Long graphDefId) {
        return R.ok(agentGraphExecutionService.pageExecutions(pageParam, graphDefId));
    }

    /** 执行详情（含 input/output 大字段；执行记录不存在/跨租户 → 404 语义） */
    @GetMapping("/{executionId}")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<AgentGraphExecutionDetailDTO> detail(@PathVariable Long executionId) {
        return R.ok(agentGraphExecutionService.getExecution(executionId));
    }

    /** 节点执行明细（nodeSeq 升序；执行记录不存在/跨租户 → 404 语义） */
    @GetMapping("/{executionId}/nodes")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentGraphExecutionNodeDTO>> nodes(@PathVariable Long executionId) {
        return R.ok(agentGraphExecutionService.listExecutionNodes(executionId));
    }

    /**
     * 单节点运行（G1-8 调试语义）：对已发布图快照中的 LLM/TOOL/KNOWLEDGE 节点
     * 单独执行一次，不产生正式执行记录、不修改图定义。
     */
    @PostMapping("/node-run")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentNodeRunRespDTO> runNode(@RequestBody AgentNodeRunReqDTO request) {
        Long graphDefId = request.getGraphDefId();
        if (graphDefId == null) {
            throw new com.sw.ck.common.exception.BaseException(
                    com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR, "graphDefId 不能为空");
        }
        return R.ok(agentGraphExecutionService.runNode(graphDefId, request.getNodeId(),
                request.getInputs()));
    }
}
