package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.service.TenantAdminService;
import com.sw.ck.system.service.TenantAdminService.TenantView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理控制器（T020）。
 * <p>
 * 读写入口都要求 system:tenant:list；写操作另由服务层确认调用者是租户 0 的系统超级管理员，
 * 普通租户用户即使持有菜单权限也不能维护租户。
 * </p>
 */
@RestController
@RequestMapping("/system/tenant")
public class TenantController {

    private final TenantAdminService tenantAdminService;

    public TenantController(TenantAdminService tenantAdminService) {
        this.tenantAdminService = tenantAdminService;
    }

    /** 新建租户请求。初始密码只入参，不在任何响应中回显。 */
    @Data
    public static class CreateTenantRequest {
        @NotBlank(message = "租户名称不能为空")
        private String name;
        @NotBlank(message = "管理员账号不能为空")
        private String adminUsername;
        @NotBlank(message = "初始密码不能为空")
        private String adminPassword;
        @NotBlank(message = "创建请求标识不能为空")
        private String requestId;
    }

    /** 编辑租户名称请求。 */
    @Data
    public static class RenameTenantRequest {
        @NotBlank(message = "租户名称不能为空")
        private String name;
    }

    /** 启停请求。 */
    @Data
    public static class TenantStatusRequest {
        @NotNull(message = "租户状态不能为空")
        private Integer status;
    }

    @Data
    public static class AssignAdministratorRequest {
        @NotNull(message = "请选择管理员")
        private Long adminUserId;
    }

    @Data
    public static class CreateAdministratorRequest {
        @NotBlank(message = "管理员账号不能为空")
        private String adminUsername;
        @NotBlank(message = "初始密码不能为空")
        private String adminPassword;
    }

    @GetMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<PageResult<TenantView>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String name,
                                          @RequestParam(required = false) Integer status) {
        return R.ok(tenantAdminService.page(pageNum, pageSize, name, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<TenantView> detail(@PathVariable Long id) {
        return R.ok(tenantAdminService.detail(id));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<Long> create(@Valid @RequestBody CreateTenantRequest request) {
        Long id = tenantAdminService.create(new TenantAdminService.CreateTenantCommand(
                request.getName(), request.getAdminUsername(), request.getAdminPassword(), request.getRequestId()));
        return R.ok(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<Void> rename(@PathVariable Long id, @Valid @RequestBody RenameTenantRequest request) {
        tenantAdminService.rename(id, request.getName());
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody TenantStatusRequest request) {
        tenantAdminService.updateStatus(id, request.getStatus());
        return R.ok();
    }

    @GetMapping("/{id}/administrators")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<java.util.List<TenantAdminService.AdminCandidate>> administrators(@PathVariable Long id) {
        return R.ok(tenantAdminService.adminCandidates(id));
    }

    @PutMapping("/{id}/administrator")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<Void> assignAdministrator(@PathVariable Long id,
                                      @Valid @RequestBody AssignAdministratorRequest request) {
        tenantAdminService.assignAdministrator(id, request.getAdminUserId());
        return R.ok();
    }

    @PostMapping("/{id}/administrator")
    @PreAuthorize("@ss.hasPermi('system:tenant:list')")
    public R<Long> createAdministrator(@PathVariable Long id,
                                       @Valid @RequestBody CreateAdministratorRequest request) {
        return R.ok(tenantAdminService.createAdministrator(id, request.getAdminUsername(), request.getAdminPassword()));
    }
}

