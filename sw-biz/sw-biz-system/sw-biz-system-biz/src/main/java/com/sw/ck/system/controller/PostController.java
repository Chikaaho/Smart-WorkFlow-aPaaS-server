package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysPost;
import com.sw.ck.system.service.SysPostService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 岗位管理控制器。
 */
@RestController
@RequestMapping("/system/post")
public class PostController {

    private final SysPostService sysPostService;

    public PostController(SysPostService sysPostService) {
        this.sysPostService = sysPostService;
    }

    /**
     * 分页查询岗位。
     */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:post:list')")
    public R<PageResult<SysPost>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestBody(required = false) SysPost query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(sysPostService.page(pageParam, query));
    }

    /**
     * 获取岗位详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:post:list')")
    public R<SysPost> get(@PathVariable Long id) {
        return R.ok(sysPostService.getById(id));
    }

    /**
     * 创建岗位。
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:post:create')")
    public R<Long> create(@Valid @RequestBody SysPost post) {
        return R.ok(sysPostService.create(post));
    }

    /**
     * 更新岗位。
     */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('system:post:update')")
    public R<Void> update(@Valid @RequestBody SysPost post) {
        sysPostService.update(post);
        return R.ok();
    }

    /**
     * 删除岗位（逻辑删除）。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:post:delete')")
    public R<Void> delete(@PathVariable Long id) {
        sysPostService.delete(id);
        return R.ok();
    }
}
