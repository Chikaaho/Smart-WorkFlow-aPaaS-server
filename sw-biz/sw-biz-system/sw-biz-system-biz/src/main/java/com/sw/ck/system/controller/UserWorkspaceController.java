package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.service.UserWorkspaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工作台布局控制器（v0.0.2 OA，P54）。
 * <p>
 * 登录用户个人配置：读取（无配置/失效回落默认布局）、保存（组件键白名单 + 体积上限）、
 * 恢复默认。配置按当前租户及用户持久化，两用户配置独立。
 * </p>
 */
@RestController
@RequestMapping({"/system/workspace", "/workspace"})
public class UserWorkspaceController {

    private final UserWorkspaceService workspaceService;

    public UserWorkspaceController(UserWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
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
}
