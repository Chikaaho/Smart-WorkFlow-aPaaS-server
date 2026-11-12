package com.sw.ck.agent.controller;

import com.sw.ck.agent.dto.AgentGraphExecuteReqDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionAcceptedDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionLifecycleDTO;
import com.sw.ck.agent.dto.AgentHumanTaskDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionEventDTO;
import com.sw.ck.agent.service.AgentGraphExecutionLifecycleService;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@RestController
@RequestMapping("/agent/graph-execution-lifecycle")
public class AgentGraphExecutionLifecycleController {
    private final AgentGraphExecutionLifecycleService service;

    public AgentGraphExecutionLifecycleController(AgentGraphExecutionLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/graphs/{graphDefId}/executions")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphExecutionAcceptedDTO> submit(
            @PathVariable Long graphDefId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AgentGraphExecuteReqDTO request) {
        return R.ok(service.submit(graphDefId, request.getInput(), request.getInputs(), idempotencyKey));
    }

    @GetMapping("/executions/{executionId}")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<AgentGraphExecutionLifecycleDTO> get(@PathVariable Long executionId) {
        return R.ok(service.get(executionId));
    }

    @GetMapping("/executions/{executionId}/events")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentGraphExecutionEventDTO>> events(
            @PathVariable Long executionId,
            @RequestParam(defaultValue = "0") long afterSequence) {
        return R.ok(service.listEvents(executionId, afterSequence));
    }

    /** 人工任务：待我处理列表（G3-2） */
    @GetMapping("/human-tasks/mine")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentHumanTaskDTO>> myHumanTasks() {
        return R.ok(service.listMyHumanTasks());
    }

    /** 人工任务：指定执行的任务列表（执行详情展示） */
    @GetMapping("/executions/{executionId}/human-tasks")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentHumanTaskDTO>> executionHumanTasks(@PathVariable Long executionId) {
        return R.ok(service.listExecutionHumanTasks(executionId));
    }

    /** 人工任务提交：填写值 + 决策动作（G3-2） */
    @PostMapping("/executions/{executionId}/human-tasks/{taskId}/submit")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<AgentGraphExecutionLifecycleDTO> submitHumanTask(
            @PathVariable Long executionId,
            @PathVariable Long taskId,
            @RequestBody com.sw.ck.agent.dto.AgentHumanTaskSubmitDTO request) {
        return R.ok(service.submitHumanTask(executionId, taskId,
                request.getValues(), request.getDecision()));
    }

    @PostMapping("/executions/{executionId}/cancel")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<AgentGraphExecutionLifecycleDTO> cancel(@PathVariable Long executionId) {
        return R.ok(service.cancel(executionId));
    }
}
