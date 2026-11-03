package com.sw.ck.agent.controller;

import com.sw.ck.agent.dto.AgentProviderDTO;
import com.sw.ck.agent.dto.AgentProviderSaveReqDTO;
import com.sw.ck.agent.service.AgentProviderService;
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
 * 供应商管理 Controller（F1 阶段 1，D022-02）。
 * <p>
 * 权限码沿用模型管理三分：{@code agent:model:view} / {@code agent:model:manage} /
 * {@code agent:model:test}（供应商与模型同属"模型接入"管理面，菜单不变）。
 * </p>
 */
@RestController
@RequestMapping("/agent/providers")
public class AgentProviderController {

    private final AgentProviderService agentProviderService;

    public AgentProviderController(AgentProviderService agentProviderService) {
        this.agentProviderService = agentProviderService;
    }

    /** 分页查询（密钥脱敏） */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<PageResult<AgentProviderDTO>> page(PageParam pageParam,
                                                @RequestParam(required = false) String nameKeyword) {
        return R.ok(agentProviderService.pageProviders(pageParam, nameKeyword));
    }

    /** 启用中的供应商列表（模型编辑下拉用） */
    @GetMapping("/enabled")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<List<AgentProviderDTO>> listEnabled() {
        return R.ok(agentProviderService.listEnabled());
    }

    /** 详情（密钥脱敏） */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:view')")
    public R<AgentProviderDTO> get(@PathVariable Long id) {
        return R.ok(agentProviderService.getById(id));
    }

    /** 新增（apiKey 明文入参，Service 层加密落库） */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Long> create(@RequestBody AgentProviderSaveReqDTO req) {
        return R.ok(agentProviderService.create(req));
    }

    /** 编辑（apiKey 留空保留旧密文） */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Void> update(@PathVariable Long id, @RequestBody AgentProviderSaveReqDTO req) {
        agentProviderService.update(id, req);
        return R.ok();
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('agent:model:manage')")
    public R<Void> delete(@PathVariable Long id) {
        agentProviderService.delete(id);
        return R.ok();
    }
}
