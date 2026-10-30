package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.service.SystemOrgScriptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 表单脚本组织目录快照端点。
 * <p>
 * 仅返回当前租户启用目录与有效关联；读取权限沿用表单查看权限，
 * 不要求角色/岗位管理权限，也不把管理端对象细节暴露给脚本。
 * </p>
 */
@RestController
@RequestMapping("/system/org")
public class SystemOrgScriptController {

    private final SystemOrgScriptService systemOrgScriptService;

    public SystemOrgScriptController(SystemOrgScriptService systemOrgScriptService) {
        this.systemOrgScriptService = systemOrgScriptService;
    }

    @GetMapping("/script-context")
    @PreAuthorize("@ss.hasPermi('form:view')")
    public R<SystemOrgScriptService.OrgScriptContext> scriptContext() {
        return R.ok(systemOrgScriptService.load());
    }
}
