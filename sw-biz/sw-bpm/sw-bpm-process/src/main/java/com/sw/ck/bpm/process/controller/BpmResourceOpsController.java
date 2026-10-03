package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.BpmResourceOpsViews;
import com.sw.ck.bpm.process.entity.BpmResourcePolicy;
import com.sw.ck.bpm.process.service.ResourcePolicyService;
import com.sw.ck.bpm.process.service.impl.BpmResourceOpsService;
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
 * P62 资源保障运维入口（复用现有流程运维域，不新增外部路由）。
 * <p>
 * 权限边界（服务端强制，不靠前端过滤）：查看 {@code workflow:resource:view}
 * 仅本租户数据；策略修改/启停/跨租户运维需独立管理权限
 * {@code workflow:resource:manage}。资源策略修改经版本化策略行留操作者与
 * 前后值审计（create_time/update_time/update_by + 版本行追加保留）。
 * </p>
 */
@RestController
@RequestMapping("/workflow/resource")
public class BpmResourceOpsController {

    private final ResourcePolicyService policyService;
    private final BpmResourceOpsService opsService;

    public BpmResourceOpsController(ResourcePolicyService policyService,
                                    BpmResourceOpsService opsService) {
        this.policyService = policyService;
        this.opsService = opsService;
    }

    // ==================== 策略（RG01：服务端策略配置/授权及发布校验） ====================

    @GetMapping("/policy")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<List<BpmResourcePolicy>> listPolicies() {
        return R.ok(policyService.listAll());
    }

    @GetMapping("/policy/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<BpmResourcePolicy> getPolicy(@PathVariable Long id) {
        return R.ok(policyService.getById(id));
    }

    /** 创建新策略版本（DRAFT、默认关闭；非法额度在保存与启用检查中均被拒绝）。 */
    @PostMapping("/policy")
    @PreAuthorize("@ss.hasPermi('workflow:resource:manage')")
    public R<BpmResourcePolicy> createPolicy(@RequestBody BpmResourcePolicy policy) {
        return R.ok(policyService.create(policy));
    }

    /** 启用检查 + 启用（保留份额/消费者/预算相容性检查失败明确拒绝并留审计）。 */
    @PostMapping("/policy/{id}/enable")
    @PreAuthorize("@ss.hasPermi('workflow:resource:manage')")
    public R<BpmResourcePolicy> enablePolicy(@PathVariable Long id,
                                             @RequestBody(required = false) PolicyRemarkRequest request) {
        return R.ok(policyService.enable(id, request == null ? null : request.remark()));
    }

    @PostMapping("/policy/{id}/disable")
    @PreAuthorize("@ss.hasPermi('workflow:resource:manage')")
    public R<BpmResourcePolicy> disablePolicy(@PathVariable Long id) {
        return R.ok(policyService.disable(id));
    }

    /** 停新受理开关：TRUE 后新受理明确拒绝，已有工作按原合同结算。 */
    @PostMapping("/policy/{id}/stop-acceptance")
    @PreAuthorize("@ss.hasPermi('workflow:resource:manage')")
    public R<BpmResourcePolicy> stopAcceptance(@PathVariable Long id,
                                               @RequestBody StopAcceptanceRequest request) {
        return R.ok(policyService.stopAcceptance(id, request != null && Boolean.TRUE.equals(request.stop())));
    }

    /** 启用检查预检（不落库；供启用前校验与画像页面展示）。 */
    @PostMapping("/policy/{id}/check")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<List<String>> checkPolicy(@PathVariable Long id) {
        return R.ok(policyService.checkEnablement(policyService.getById(id)));
    }

    // ==================== 画像与积压（RG04/RG05） ====================

    @GetMapping("/profile")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<BpmResourceOpsViews.RuntimeProfile> profile() {
        return R.ok(opsService.profile());
    }

    @GetMapping("/backlog/summary")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<BpmResourceOpsViews.BacklogSummary> backlogSummary() {
        return R.ok(opsService.backlogSummary());
    }

    @GetMapping("/backlog/commands")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<BpmResourceOpsViews.CommandPage> backlogCommands(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceClass,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Integer policyVersion) {
        return R.ok(opsService.backlogCommands(page, size, tenantId, status, resourceClass,
                channel, policyVersion));
    }

    @GetMapping("/backlog/commands/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<BpmResourceOpsViews.CommandDetail> commandDetail(@PathVariable long id) {
        return R.ok(opsService.commandDetail(id));
    }

    @GetMapping("/rejects")
    @PreAuthorize("@ss.hasPermi('workflow:resource:view')")
    public R<BpmResourceOpsViews.RejectPage> rejects(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long tenantId) {
        return R.ok(opsService.rejects(page, size, tenantId));
    }

    // ==================== 请求体 ====================

    public record PolicyRemarkRequest(String remark) {
    }

    public record StopAcceptanceRequest(Boolean stop) {
    }

}
