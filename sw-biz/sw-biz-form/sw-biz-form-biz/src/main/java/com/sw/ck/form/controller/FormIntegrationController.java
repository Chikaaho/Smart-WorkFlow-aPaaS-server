package com.sw.ck.form.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.form.dto.FormIntegrationCandidateDTO;
import com.sw.ck.form.dto.FormIntegrationInvokeReq;
import com.sw.ck.form.dto.FormIntegrationInvokeResult;
import com.sw.ck.form.service.FormIntegrationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 表单受控集成端点。
 * <p>
 * 候选列表要求同时具备表单设计权限与既有外部工具查看权限，配置表单不自动获得读取或
 * 管理外部工具的权限；运行期调用使用独立的 {@code form:integration:invoke} 最小权限，
 * 并且服务端仍按版本快照、记录操作与能力状态取交集。
 * </p>
 */
@RestController
@RequestMapping("/form/integration")
public class FormIntegrationController {

    private final FormIntegrationService formIntegrationService;

    public FormIntegrationController(FormIntegrationService formIntegrationService) {
        this.formIntegrationService = formIntegrationService;
    }

    /** 设计器可选择的、本租户启用且已分类的能力（不含 URL/方法）。 */
    @GetMapping("/candidates")
    @PreAuthorize("@ss.hasPermi('form:design') and @ss.hasPermi('agent:tool:view')")
    public R<List<FormIntegrationCandidateDTO>> candidates() {
        return R.ok(formIntegrationService.listCandidates());
    }

    /** 运行期受控调用。 */
    @PostMapping("/invoke")
    @PreAuthorize("@ss.hasPermi('form:integration:invoke')")
    public R<FormIntegrationInvokeResult> invoke(@RequestBody FormIntegrationInvokeReq req) {
        return R.ok(formIntegrationService.invoke(req));
    }
}
