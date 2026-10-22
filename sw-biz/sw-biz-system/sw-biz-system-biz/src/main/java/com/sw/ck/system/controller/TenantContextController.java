package com.sw.ck.system.controller;

import com.sw.ck.common.response.R;
import com.sw.ck.system.service.ManagementTenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 组织管理共用的最小租户上下文；不暴露管理员或凭据。 */
@RestController
@RequestMapping("/system/tenant-context")
public class TenantContextController {
    private final ManagementTenantContext context;

    public TenantContextController(ManagementTenantContext context) {
        this.context = context;
    }

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    public R<ManagementTenantContext.Option> current() {
        return R.ok(context.current());
    }

    @GetMapping("/options")
    @PreAuthorize("@ss.hasPermi('system:user:list') or @ss.hasPermi('system:dept:list')")
    public R<List<ManagementTenantContext.Option>> options(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean creatable) {
        return R.ok(context.options(keyword, creatable));
    }
}
