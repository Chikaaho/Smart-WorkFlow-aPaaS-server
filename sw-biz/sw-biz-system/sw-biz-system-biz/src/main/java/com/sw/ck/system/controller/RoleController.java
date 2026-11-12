package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysRole;
import com.sw.ck.system.entity.SysUser;
import com.sw.ck.system.service.SysRoleService;
import jakarta.validation.Valid;
import lombok.Data;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

/**
 * 角色管理控制器。
 */
@RestController
@RequestMapping("/system/role")
public class RoleController {

    private final SysRoleService sysRoleService;

    public RoleController(SysRoleService sysRoleService) {
        this.sysRoleService = sysRoleService;
    }

    /**
     * 分页查询角色。
     */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public R<PageResult<SysRole>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestBody(required = false) SysRole query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(sysRoleService.page(pageParam, query));
    }

    /**
     * 角色选项（登录即可调用的最小角色选项，供智能体人工节点处理人选择等场景）。
     * 仅返回启用角色的 id/code/name，租户隔离经租户拦截器生效；不暴露菜单/数据范围。
     */
    @GetMapping("/options")
    public R<List<RoleOptionDTO>> options() {
        List<SysRole> roles = sysRoleService.lambdaQuery()
                .eq(SysRole::getStatus, 1)
                .orderByAsc(SysRole::getSort)
                .list();
        return R.ok(roles.stream().map(r -> {
            RoleOptionDTO dto = new RoleOptionDTO();
            dto.setId(r.getId());
            dto.setCode(r.getCode());
            dto.setName(r.getName());
            return dto;
        }).toList());
    }

    /**
     * 获取角色详情。
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public R<SysRole> get(@PathVariable Long id) {
        return R.ok(sysRoleService.getById(id));
    }

    /**
     * 创建角色。
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:role:create')")
    public R<Long> create(@Valid @RequestBody SysRole role) {
        return R.ok(sysRoleService.create(role));
    }

    /**
     * 更新角色。
     */
    @PutMapping
    @PreAuthorize("@ss.hasPermi('system:role:update')")
    public R<Void> update(@Valid @RequestBody SysRole role) {
        sysRoleService.update(role);
        return R.ok();
    }

    /**
     * 删除角色（逻辑删除）。
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:role:delete')")
    public R<Void> delete(@PathVariable Long id) {
        sysRoleService.delete(id);
        return R.ok();
    }

    /**
     * 分页查询角色成员（成员维护反向视图）。
     */
    @GetMapping("/{id}/users")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public R<PageResult<SysUser>> members(@PathVariable Long id,
                                          @RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(sysRoleService.pageMembers(id, pageParam));
    }

    @GetMapping("/{id}/menus")
    @PreAuthorize("@ss.hasPermi('system:role:list')")
    public R<List<Long>> menus(@PathVariable Long id) {
        return R.ok(sysRoleService.listMenuIds(id));
    }

    @PutMapping("/{id}/menus")
    @PreAuthorize("@ss.hasPermi('system:role:update')")
    public R<Void> updateMenus(@PathVariable Long id, @RequestBody List<Long> menuIds) {
        sysRoleService.updateMenuIds(id, menuIds);
        return R.ok();
    }

    /** 角色选项 DTO：id/code/name 最小面 */
    @Data
    public static class RoleOptionDTO {
        private Long id;
        private String code;
        private String name;
    }
}
