package com.sw.ck.system.controller;

import org.springframework.beans.factory.annotation.Autowired;

import com.sw.ck.common.response.R;
import com.sw.ck.system.vo.WorkspaceLayoutVO;
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
import com.sw.ck.common.value.DynamicData;
import com.sw.ck.system.vo.SysWorkspaceCardTypeVO;

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

    @Autowired
    private UserWorkspaceService workspaceService;
    @Autowired
    private WorkspaceCardTypeService cardTypeService;

    /** 当前用户布局（custom=false 表示默认布局）。 */
    @SuppressWarnings("unchecked")
    @GetMapping("/layout")
    public R<WorkspaceLayoutVO> layout() {
        Map<String, Object> source = workspaceService.getLayout();
        WorkspaceLayoutVO result = new WorkspaceLayoutVO();
        result.setCustom(Boolean.TRUE.equals(source.get("custom")));
        result.setCardTypes((List<SysWorkspaceCardType>) source.get("cardTypes"));
        result.setLayout(DynamicData.of(source.get("layout")));
        return R.ok(result);
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
    public R<List<SysWorkspaceCardTypeVO>> cardTypes() {
        return R.ok(cardTypeService.listAvailable().stream().map(SysWorkspaceCardTypeVO::from).toList());
    }

    /** 后台卡片类型维护列表。 */
    @GetMapping("/card-types/manage")
    @PreAuthorize("@ss.hasPermi('workflow:workspace:manage')")
    public R<List<SysWorkspaceCardTypeVO>> manageList() {
        return R.ok(cardTypeService.listAll().stream().map(SysWorkspaceCardTypeVO::from).toList());
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
