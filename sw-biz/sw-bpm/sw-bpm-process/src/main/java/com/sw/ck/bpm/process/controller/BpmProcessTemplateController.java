package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessTemplate;
import com.sw.ck.bpm.process.service.BpmProcessTemplateService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUserHolder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
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

import java.util.Map;

/**
 * 流程模板中心（I4 §3.2）：分类/状态/版本/管理范围集中维护，复制后编辑并发布。
 */
@Slf4j
@RestController
@RequestMapping("/workflow/templates")
public class BpmProcessTemplateController {

    private final BpmProcessTemplateService templateService;

    public BpmProcessTemplateController(BpmProcessTemplateService templateService) {
        this.templateService = templateService;
    }

    @Data
    public static class TemplateSaveRequest {
        private String name;
        private String category;
        private String description;
        private String formKey;
        /** 图 JSON 字符串（可选：创建空模板时可缺省） */
        private String graphJson;
        private String scopeType;
        private Long scopeDeptId;
        /** 复制创建定义时的新定义名称（缺省用模板名） */
        private String copyName;
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:list')")
    @GetMapping
    public R<PageResult<BpmProcessTemplate>> page(PageParam pageParam,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String status) {
        return R.ok(templateService.page(pageParam, keyword, category, status));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:view')")
    @GetMapping("/{id}")
    public R<BpmProcessTemplate> get(@PathVariable Long id) {
        return R.ok(templateService.get(id));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:create')")
    @PostMapping
    public R<BpmProcessTemplate> create(@RequestBody TemplateSaveRequest request) {
        return R.ok(templateService.create(request.getName(), request.getCategory(),
                request.getDescription(), request.getFormKey(), request.getGraphJson(),
                request.getScopeType(), request.getScopeDeptId(), null, null));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:save')")
    @PutMapping("/{id}")
    public R<BpmProcessTemplate> update(@PathVariable Long id,
                                        @RequestBody TemplateSaveRequest request) {
        return R.ok(templateService.update(id, request.getName(), request.getCategory(),
                request.getDescription(), request.getGraphJson(), request.getScopeType(),
                request.getScopeDeptId()));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:save')")
    @PutMapping("/{id}/status/{enabled}")
    public R<BpmProcessTemplate> changeStatus(@PathVariable Long id, @PathVariable boolean enabled) {
        return R.ok(templateService.changeStatus(id, enabled));
    }

    /** 复制后编辑并发布：以模板为受控来源创建真实流程定义（DRAFT），返回 defId 供设计器编辑。 */
    @PreAuthorize("@ss.hasPermi('workflow:template:copy')")
    @PostMapping("/{id}/copy")
    public R<BpmProcessDef> copyToDefinition(@PathVariable Long id,
                                             @RequestBody(required = false) TemplateSaveRequest request) {
        String name = request == null || request.getCopyName() == null || request.getCopyName().isBlank()
                ? null : request.getCopyName();
        BpmProcessDef def = templateService.copyToDefinition(id, name);
        log.info("模板复制为定义: templateId={}, defId={}, operator={}",
                id, def.getId(), LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId());
        return R.ok(def);
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:delete')")
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable Long id) {
        templateService.delete(id);
        return R.ok(Map.of("id", id));
    }
}
