package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.process.dto.CategoryDTO;
import com.sw.ck.bpm.process.dto.CategorySaveReq;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.service.BpmCategoryService;
import com.sw.ck.common.response.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 流程分类管理控制器（v0.0.2 流程中心·后台管理视角）。
 * <p>
 * 管理操作逐项检查独立权限 {@code workflow:catalog:manage}；
 * 管理身份可维护完整分类，但不自动授予查看他人填报数据或代办审批的权限。
 * </p>
 */
@RestController
@RequestMapping("/workflow/categories")
public class BpmCategoryController {

    private final BpmCategoryService categoryService;

    public BpmCategoryController(BpmCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** 分类列表（管理视角，附事项归属计数）。 */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('workflow:catalog:manage')")
    public R<List<CategoryDTO>> list() {
        return R.ok(categoryService.listCategories());
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('workflow:catalog:manage')")
    public R<CategoryDTO> create(@RequestBody CategorySaveReq req) {
        BpmCategory category = categoryService.createCategory(req);
        return R.ok(CategoryDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .sortNo(category.getSortNo())
                .itemCount(0L)
                .build());
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:catalog:manage')")
    public R<CategoryDTO> update(@PathVariable Long id, @RequestBody CategorySaveReq req) {
        BpmCategory category = categoryService.updateCategory(id, req);
        return R.ok(CategoryDTO.builder()
                .id(category.getId())
                .name(category.getName())
                .sortNo(category.getSortNo())
                .build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:catalog:manage')")
    public R<Void> delete(@PathVariable Long id) {
        categoryService.deleteCategory(id);
        return R.ok();
    }
}
