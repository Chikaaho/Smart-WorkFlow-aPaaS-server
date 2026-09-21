package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.service.UserWorkspaceService;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.service.WorkspaceCardTypeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作台布局控制器（v0.0.2 OA，P54）。
 * <p>
 * 登录用户个人配置：读取（无配置/失效回落默认布局）、保存（卡片类型白名单 + 体积上限）、
 * 恢复默认。配置按当前租户及用户持久化，两用户配置独立。
 * </p>
 */
@RestController
@RequestMapping({"/system/workspace", "/workspace"})
public class UserWorkspaceController {

    private final UserWorkspaceService workspaceService;
    private final WorkspaceCardTypeService cardTypeService;

    public UserWorkspaceController(UserWorkspaceService workspaceService,
                                   WorkspaceCardTypeService cardTypeService) {
        this.workspaceService = workspaceService;
        this.cardTypeService = cardTypeService;
    }

    /** 当前用户布局（custom=false 表示默认布局）。 */
    @GetMapping("/layout")
    public R<Map<String, Object>> layout() {
        return R.ok(workspaceService.getLayout());
    }

    /** 保存当前用户布局。 */
    @PutMapping("/layout")
    public R<Void> saveLayout(@RequestBody Map<String, Object> layout) {
        workspaceService.saveLayout(layout);
        return R.ok();
    }

    /** 恢复默认布局。 */
    @DeleteMapping("/layout")
    public R<Void> resetLayout() {
        workspaceService.resetLayout();
        return R.ok();
    }

    /** 当前租户启用的卡片类型，供工作台安全注册表消费。 */
    @GetMapping("/card-types")
    public R<List<SysWorkspaceCardType>> cardTypes() {
        return R.ok(cardTypeService.listAvailable());
    }

    /** 后台卡片类型维护列表。 */
    @GetMapping("/card-types/manage")
    @PreAuthorize("@ss.hasPermi('workflow:workspace:manage')")
    public R<List<SysWorkspaceCardType>> manageList() {
        return R.ok(cardTypeService.listAll());
    }

    @PostMapping("/card-types")
    @PreAuthorize("@ss.hasPermi('workflow:workspace:manage')")
    public R<Long> createCardType(@RequestBody SysWorkspaceCardType cardType) {
        return R.ok(cardTypeService.create(cardType));
    }

    @PutMapping("/card-types/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:workspace:manage')")
    public R<Void> updateCardType(@PathVariable Long id, @RequestBody SysWorkspaceCardType cardType) {
        cardType.setId(id);
        cardTypeService.update(cardType);
        return R.ok();
    }

    @DeleteMapping("/card-types/{id}")
    @PreAuthorize("@ss.hasPermi('workflow:workspace:manage')")
    public R<Void> deleteCardType(@PathVariable Long id) {
        cardTypeService.delete(id);
        return R.ok();
    }
}
