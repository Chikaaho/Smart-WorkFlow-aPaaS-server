package com.sw.ck.bpm.process.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.bpm.process.vo.TemplateDeleteResultVO;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessTemplate;
import com.sw.ck.bpm.process.service.BpmProcessTemplateService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUserHolder;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
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
import com.sw.ck.bpm.process.vo.BpmProcessDefVO;
import com.sw.ck.bpm.process.vo.BpmProcessTemplateVO;

/**
 * 流程模板中心（I4 §3.2）：分类/状态/版本/管理范围集中维护，复制后编辑并发布。
 */
@Log4j2
@RestController
@RequestMapping("/workflow/templates")
public class BpmProcessTemplateController {

    @Autowired
    private BpmProcessTemplateService templateService;

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
    public R<PageResult<BpmProcessTemplateVO>> page(PageParam pageParam,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String status) {
        return R.ok(PageResult.map(templateService.page(pageParam, keyword, category, status), BpmProcessTemplateVO::from));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:view')")
    @GetMapping("/{id}")
    public R<BpmProcessTemplateVO> get(@PathVariable Long id) {
        return R.ok(BpmProcessTemplateVO.from(templateService.get(id)));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:create')")
    @PostMapping
    public R<BpmProcessTemplateVO> create(@RequestBody TemplateSaveRequest request) {
        return R.ok(BpmProcessTemplateVO.from(templateService.create(request.getName(),
                request.getCategory(), request.getDescription(), request.getFormKey(),
                request.getGraphJson(), request.getScopeType(), request.getScopeDeptId(),
                null, null)));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:save')")
    @PutMapping("/{id}")
    public R<BpmProcessTemplateVO> update(@PathVariable Long id,
                                        @RequestBody TemplateSaveRequest request) {
        return R.ok(BpmProcessTemplateVO.from(templateService.update(id, request.getName(),
                request.getCategory(), request.getDescription(), request.getGraphJson(),
                request.getScopeType(), request.getScopeDeptId())));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:save')")
    @PutMapping("/{id}/status/{enabled}")
    public R<BpmProcessTemplateVO> changeStatus(@PathVariable Long id, @PathVariable boolean enabled) {
        return R.ok(BpmProcessTemplateVO.from(templateService.changeStatus(id, enabled)));
    }

    /** 复制后编辑并发布：以模板为受控来源创建真实流程定义（DRAFT），返回 defId 供设计器编辑。 */
    @PreAuthorize("@ss.hasPermi('workflow:template:copy')")
    @PostMapping("/{id}/copy")
    public R<BpmProcessDefVO> copyToDefinition(@PathVariable Long id,
                                             @RequestBody(required = false) TemplateSaveRequest request) {
        String name = request == null || request.getCopyName() == null || request.getCopyName().isBlank()
                ? null : request.getCopyName();
        BpmProcessDef def = templateService.copyToDefinition(id, name);
        log.info("模板复制为定义: templateId={}, defId={}, operator={}",
                id, def.getId(), LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId());
        return R.ok(BpmProcessDefVO.from(def));
    }

    @PreAuthorize("@ss.hasPermi('workflow:template:delete')")
    @DeleteMapping("/{id}")
    public R<TemplateDeleteResultVO> delete(@PathVariable Long id) {
        templateService.delete(id);
        TemplateDeleteResultVO result = new TemplateDeleteResultVO();
        result.setId(id);
        return R.ok(result);
    }
}
