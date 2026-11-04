package com.sw.ck.agent.controller;

import com.sw.ck.agent.dto.AgentModelDTO;
import com.sw.ck.agent.dto.AgentModelSaveReqDTO;
import com.sw.ck.agent.dto.AgentModelTestConnectionRespDTO;
import com.sw.ck.agent.service.AgentModelService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型管理 Controller（F1 阶段 1，D022-02）。
 * <p>
 * 权限码三分（沿用）：{@code agent:model:view}（查询）/
 * {@code agent:model:manage}（增/改/删）/ {@code agent:model:test}（连通性测试，
 * 独立权限码：涉及真实出站模型调用）。
 * </p>
 */
@RestController
@RequestMapping("/agent/models")
public class AgentModelController {

    private final AgentModelService agentModelService;

    public AgentModelController(AgentModelService agentModelService) {
        this.agentModelService = agentModelService;
    }

    /** 分页查询（可按供应商过滤） */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<PageResult<AgentModelDTO>> page(PageParam pageParam,
                                             @RequestParam(required = false) Long providerId,
                                             @RequestParam(required = false) String nameKeyword) {
        return R.ok(agentModelService.pageModels(pageParam, providerId, nameKeyword));
    }

    /** 启用中的模型列表（图设计器 LLM 节点选择用） */
    @GetMapping("/enabled")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentModelDTO>> listEnabled(
            @RequestParam(required = false) String capability) {
        return R.ok(agentModelService.listEnabled(capability));
    }

    /** 详情 */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<AgentModelDTO> get(@PathVariable Long id) {
        return R.ok(agentModelService.getById(id));
    }

    /** 新增 */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Long> create(@RequestBody AgentModelSaveReqDTO req) {
        return R.ok(agentModelService.create(req));
    }

    /** 编辑 */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Void> update(@PathVariable Long id, @RequestBody AgentModelSaveReqDTO req) {
        agentModelService.update(id, req);
        return R.ok();
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Void> delete(@PathVariable Long id) {
        agentModelService.delete(id);
        return R.ok();
    }

    /** 连通性测试（真实最小调用：成功才判可用） */
    @PostMapping("/{id}/test-connection")
    @PreAuthorize("@ss.hasPermi('agent:model:test')")
    public R<AgentModelTestConnectionRespDTO> testConnection(@PathVariable Long id) {
        return R.ok(agentModelService.testConnection(id));
    }
}
