package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysDept;
import com.sw.ck.system.service.DeptQuery;
import com.sw.ck.system.service.SysDeptService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理控制器。
 */
@RestController
@RequestMapping("/system/dept")
public class DeptController {

    private final SysDeptService sysDeptService;
    private final com.sw.ck.system.service.ManagementTenantContext tenantContext;

    public DeptController(SysDeptService sysDeptService,
                          com.sw.ck.system.service.ManagementTenantContext tenantContext) {
        this.sysDeptService = sysDeptService;
        this.tenantContext = tenantContext;
    }

    /**
     * 查询部门树。
     * <p>
     * 可选查询参数：{@code name} 部门名称包含匹配（trim 后空白等价于未填写）；
     * {@code status} 部门状态 0=正常 1=停用（非法值显式报错 PARAM_ERROR）。
     * 无参数时返回全量排序列表（前端自行组装树），行为与历史版本完全一致。
     * 筛选结果仅含直接命中节点及其必要祖先路径，按 sort 升序、去重。
     * </p>
     */
    @GetMapping("/tree")
    @PreAuthorize("@ss.hasPermi('system:dept:list')")
    public R<List<SysDept>> tree(@RequestParam(required = false) String name,
                                 @RequestParam(required = false) Integer status,
                                 @RequestParam(required = false) Long tenantId) {
        DeptQuery query = new DeptQuery();
        query.setName(name);
        query.setStatus(status);
        query.setTenantId(tenantContext.readTarget(tenantId));
        List<SysDept> rows = sysDeptService.listTree(query);
        tenantContext.decorateDepartments(rows);
        return R.ok(rows);
    }

    /**
     * 获取部门详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dept:list')")
    public R<SysDept> get(@PathVariable Long id) {
        SysDept dept = sysDeptService.getById(id);
        if (dept != null) tenantContext.decorateDepartments(List.of(dept));
        return R.ok(dept);
    }

    /**
     * 创建部门。
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:dept:create')")
    public R<Long> create(@Valid @RequestBody SysDept dept) {
        dept.setTenantId(tenantContext.createTarget(dept.getTenantId()));
        return R.ok(sysDeptService.create(dept));
    }

    /**
     * 更新部门。
     */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('system:dept:update')")
    public R<Void> update(@Valid @RequestBody SysDept dept) {
        SysDept owner = requireManagementDepartment(dept.getId());
        dept.setTenantId(tenantContext.updateTarget(dept.getTenantId(), owner.getTenantId()));
        sysDeptService.update(dept);
        return R.ok();
    }

    /**
     * 删除部门（逻辑删除，含子部门/在职用户校验）。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dept:delete')")
    public R<Void> delete(@PathVariable Long id) {
        tenantContext.updateTarget(null, requireManagementDepartment(id).getTenantId());
        sysDeptService.delete(id);
        return R.ok();
    }
    private SysDept requireManagementDepartment(Long id) {
        SysDept dept = sysDeptService.getById(id);
        if (dept == null) throw new com.sw.ck.common.exception.BaseException(
                com.sw.ck.common.exception.CommonErrorCode.PARAM_ERROR, "部门不存在或无权访问");
        return dept;
    }
}
