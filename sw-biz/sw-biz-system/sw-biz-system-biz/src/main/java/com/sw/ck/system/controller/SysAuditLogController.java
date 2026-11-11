package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.service.AuditLogService;
import com.sw.ck.system.service.ManagementTenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日志查询（1.0.0 F3-4）：登录、操作、异常三类审计的分页查询。
 * <p>
 * 租户管理员只能看到本租户记录（租户拦截器强制）；超级管理员可按租户筛选
 * （不选租户时跨全部租户，含预登录的 tenant_id=0 行）。
 * </p>
 */
@RestController
@RequestMapping("/system/audit-log")
public class SysAuditLogController {

    private final AuditLogService auditLogService;
    private final ManagementTenantContext tenantContext;

    public SysAuditLogController(AuditLogService auditLogService,
                                 ManagementTenantContext tenantContext) {
        this.auditLogService = auditLogService;
        this.tenantContext = tenantContext;
    }

    /**
     * 分页查询审计日志。类型/结果/模块/账号/IP/时间范围/租户组合筛选，时间倒序。
     */
    @PostMapping("/page")
    @PreAuthorize("@ss.hasPermi('system:log:list')")
    public R<PageResult<SysAuditLog>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestBody(required = false)
                                           AuditLogService.AuditLogPageQuery query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        AuditLogService.AuditLogPageQuery filter =
                query == null ? new AuditLogService.AuditLogPageQuery() : query;
        Long tenantId = tenantContext.readTarget(filter.getTenantId());
        boolean suspendTenant = tenantId != null;
        filter.setTenantId(tenantId);
        return R.ok(auditLogService.page(pageParam, filter, suspendTenant));
    }
}
