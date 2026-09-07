package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.CatalogItemDTO;
import com.sw.ck.bpm.process.dto.CategoryAssignReq;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.service.BpmCatalogService;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 流程中心事项目录控制器（v0.0.2）。
 * <p>
 * 普通视角（{@code /workflow/catalog/items}）：仅返回本人可见且已发布、存在 active
 * 绑定且表单已发布的事项；分类、数量与搜索均不泄漏不可见事项。
 * 管理视角（{@code /workflow/catalog/admin/*}）逐项检查独立权限。
 * 事项稳定标识为 processKey，前端不靠名称或路由解析绑定。
 * </p>
 */
@RestController
@RequestMapping("/workflow/catalog")
public class BpmCatalogController {

    private final BpmCatalogService catalogService;

    public BpmCatalogController(BpmCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** 普通用户可发起事项目录（categoryId=0 表示未分类兜底）。 */
    @GetMapping("/items")
    public R<PageResult<CatalogItemDTO>> items(PageParam pageParam,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Long categoryId) {
        return R.ok(catalogService.listPortalItems(keyword, categoryId, pageParam));
    }

    /** 普通视角分类聚合数量（仅统计本人可见事项，未分类归入 key 0）。 */
    @GetMapping("/category-counts")
    public R<Map<Long, Long>> categoryCounts() {
        return R.ok(catalogService.portalCategoryCounts());
    }

    /** 普通视角分类列表（仅名称/排序，登录即可用；不泄漏事项级信息）。 */
    @GetMapping("/categories")
    public R<List<BpmCategory>> portalCategories() {
        return R.ok(catalogService.portalCategories());
    }

    /** 普通视角事项详情（校验可见/发布/绑定后返回关联表单 formKey）。 */
    @GetMapping("/items/{processKey}")
    public R<CatalogItemDTO> item(@PathVariable String processKey) {
        return R.ok(catalogService.getPortalItem(processKey));
    }

    /** 管理视角事项列表（含未发布定义）。 */
    @GetMapping("/admin/items")
    @PreAuthorize("@ss.hasPermi('workflow:catalog:manage')")
    public R<PageResult<CatalogItemDTO>> adminItems(PageParam pageParam,
                                                    @RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) Long categoryId) {
        return R.ok(catalogService.listAdminItems(keyword, categoryId, pageParam));
    }

    /** 调整事项分类归属（categoryId=null 移入未分类）。 */
    @PutMapping("/admin/items/{processKey}/category")
    @PreAuthorize("@ss.hasPermi('workflow:catalog:manage')")
    public R<Void> assignCategory(@PathVariable String processKey, @RequestBody CategoryAssignReq req) {
        catalogService.assignCategory(processKey, req == null ? null : req.getCategoryId());
        return R.ok();
    }
}
