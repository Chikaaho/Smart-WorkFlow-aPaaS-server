package com.sw.ck.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.agent.dto.AgentToolExternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolExternalConfigQuery;
import com.sw.ck.agent.dto.AgentToolInternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolInternalConfigQuery;
import com.sw.ck.agent.service.AgentToolConfigService;
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

/**
 * 工具沙箱配置管理 Controller（M07 Step3）。
 * <p>
 * 权限码三分拆分（方案 §7.2）：{@code agent:tool:view}（列表/详情）/
 * {@code agent:tool:manage}（增/改/删/启用禁用）。写法对齐 {@code AgentModelController}
 * 的 {@code @ss.hasPermi(...)} 模式。不包含 testConnection 端点（外部工具连通性测试
 * 留后续 Step）。
 * </p>
 */
@RestController
@RequestMapping("/agent/tool")
public class AgentToolConfigController {

    @Autowired
    private AgentToolConfigService agentToolConfigService;

    // ==================== 内部工具 ====================

    /** 分页列表 */
    @GetMapping("/internal")
    @PreAuthorize("@ss.hasPermi('agent:tool:view')")
    public R<PageResult<AgentToolInternalConfigDTO>> pageInternal(AgentToolInternalConfigQuery query) {
        return R.ok(agentToolConfigService.pageInternalTools(query));
    }

    /** 详情 */
    @GetMapping("/internal/{id}")
    @PreAuthorize("@ss.hasPermi('agent:tool:view')")
    public R<AgentToolInternalConfigDTO> getInternal(@PathVariable Long id) {
        return R.ok(agentToolConfigService.getInternalTool(id));
    }

    /** 新建 */
    @PostMapping("/internal")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Long> createInternal(@RequestBody AgentToolInternalConfigDTO req) {
        return R.ok(agentToolConfigService.createInternalTool(req));
    }

    /** 修改 */
    @PutMapping("/internal/{id}")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Void> updateInternal(@PathVariable Long id, @RequestBody AgentToolInternalConfigDTO req) {
        agentToolConfigService.updateInternalTool(id, req);
        return R.ok();
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/internal/{id}")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Void> deleteInternal(@PathVariable Long id) {
        agentToolConfigService.deleteInternalTool(id);
        return R.ok();
    }

    /** 启用/禁用 */
    @PutMapping("/internal/{id}/toggle")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Void> toggleInternal(@PathVariable Long id, @RequestParam boolean enabled) {
        agentToolConfigService.toggleInternalTool(id, enabled);
        return R.ok();
    }

    // ==================== 外部工具 ====================

    /** 分页列表 */
    @GetMapping("/external")
    @PreAuthorize("@ss.hasPermi('agent:tool:view')")
    public R<PageResult<AgentToolExternalConfigDTO>> pageExternal(AgentToolExternalConfigQuery query) {
        return R.ok(agentToolConfigService.pageExternalTools(query));
    }

    /** 详情 */
    @GetMapping("/external/{id}")
    @PreAuthorize("@ss.hasPermi('agent:tool:view')")
    public R<AgentToolExternalConfigDTO> getExternal(@PathVariable Long id) {
        return R.ok(agentToolConfigService.getExternalTool(id));
    }

    /** 新建 */
    @PostMapping("/external")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Long> createExternal(@RequestBody AgentToolExternalConfigDTO req) {
        return R.ok(agentToolConfigService.createExternalTool(req));
    }

    /** 修改 */
    @PutMapping("/external/{id}")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Void> updateExternal(@PathVariable Long id, @RequestBody AgentToolExternalConfigDTO req) {
        agentToolConfigService.updateExternalTool(id, req);
        return R.ok();
    }

    /** 删除（逻辑删除） */
    @DeleteMapping("/external/{id}")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Void> deleteExternal(@PathVariable Long id) {
        agentToolConfigService.deleteExternalTool(id);
        return R.ok();
    }

    /** 启用/禁用 */
    @PutMapping("/external/{id}/toggle")
    @PreAuthorize("@ss.hasPermi('agent:tool:manage')")
    public R<Void> toggleExternal(@PathVariable Long id, @RequestParam boolean enabled) {
        agentToolConfigService.toggleExternalTool(id, enabled);
        return R.ok();
    }
}
