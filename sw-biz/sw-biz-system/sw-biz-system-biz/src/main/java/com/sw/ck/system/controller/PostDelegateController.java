package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysPostDelegate;
import com.sw.ck.system.service.PositionDelegateService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * P64 阶段Ⅱ（A07）岗位委托管理控制器（后台组织域通用配置）。
 * <p>
 * 配置与变更可审计（审计列 + 操作日志沿平台既有切面）；启停即生效/失效，
 * 已冻结的运行名单沿既有参与人快照，不受后来变更影响。
 * </p>
 */
@RestController
@RequestMapping("/system/post-delegate")
public class PostDelegateController {

    private final PositionDelegateService positionDelegateService;

    public PostDelegateController(PositionDelegateService positionDelegateService) {
        this.positionDelegateService = positionDelegateService;
    }

    /**
     * 分页查询委托关系。
     */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:postDelegate:list')")
    public R<PageResult<SysPostDelegate>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestBody(required = false) SysPostDelegate query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(positionDelegateService.page(pageParam, query));
    }

    /**
     * 创建委托关系（自委托/循环/跨租户/越权/同优先级重叠配置拒绝）。
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:postDelegate:create')")
    public R<Long> create(@RequestBody SysPostDelegate delegate) {
        return R.ok(positionDelegateService.create(delegate));
    }

    /**
     * 更新委托关系（全量校验 + 生效链校验）。
     */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('system:postDelegate:update')")
    public R<Void> update(@RequestBody SysPostDelegate delegate) {
        positionDelegateService.update(delegate);
        return R.ok();
    }

    /**
     * 启停委托关系（启用时重新执行重叠与循环校验）。
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("@ss.hasPermi('system:postDelegate:update')")
    public R<Void> changeStatus(@PathVariable Long id, @RequestParam String status) {
        positionDelegateService.changeStatus(id, status);
        return R.ok();
    }

    /**
     * 删除委托关系（逻辑删除）。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:postDelegate:delete')")
    public R<Void> delete(@PathVariable Long id) {
        positionDelegateService.delete(id);
        return R.ok();
    }
}
