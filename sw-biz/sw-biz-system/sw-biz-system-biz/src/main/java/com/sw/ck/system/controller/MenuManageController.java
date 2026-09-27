package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.service.MenuManageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 菜单管理控制器（V012-BUG-011/017）：管理端菜单树读取与受控更新。
 * <p>
 * 权限 {@code system:menu:manage}（超管旁路）；普通角色授权经角色-菜单管理显式操作。
 * </p>
 */
@RestController
@RequestMapping("/system/menu")
public class MenuManageController {

    private final MenuManageService menuManageService;

    public MenuManageController(MenuManageService menuManageService) {
        this.menuManageService = menuManageService;
    }

    /** 全量目录/菜单条目（按钮不下发）。 */
    @GetMapping("/manage-items")
    @PreAuthorize("@ss.hasPermi('system:menu:manage')")
    public R<List<MenuManageVO>> manageItems() {
        return R.ok(menuManageService.manageItems());
    }

    /** 受控更新：title/icon/sort/hidden（请求体字段可缺省，null 跳过）。 */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:menu:manage')")
    public R<Void> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        String title = body.get("title") == null ? null : String.valueOf(body.get("title"));
        String icon = body.get("icon") == null ? null : String.valueOf(body.get("icon"));
        Integer sort = body.get("sort") == null ? null : Integer.valueOf(String.valueOf(body.get("sort")));
        Boolean hidden = body.get("hidden") == null ? null : Boolean.valueOf(String.valueOf(body.get("hidden")));
        menuManageService.updatePermitted(id, title, icon, sort, hidden);
        return R.ok(null);
    }
}
