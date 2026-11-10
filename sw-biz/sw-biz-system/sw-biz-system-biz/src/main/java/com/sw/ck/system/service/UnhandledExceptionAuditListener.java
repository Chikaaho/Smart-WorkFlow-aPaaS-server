package com.sw.ck.system.service;

import com.sw.ck.common.audit.UnhandledExceptionAudit;
import com.sw.ck.system.entity.SysAuditLog;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 未处理异常审计落库（1.0.0 F3-3）：监听全局异常出口发布的审计事件写审计日志。
 * <p>
 * 摘要只承载安全结论文案，不含堆栈与请求体；操作人账号按 userId 回查补全
 * （异常出口在框架层拿不到 username）。
 * </p>
 */
@Component
public class UnhandledExceptionAuditListener {

    private final AuditLogService auditLogService;
    private final com.sw.ck.system.mapper.SysUserMapper sysUserMapper;

    public UnhandledExceptionAuditListener(AuditLogService auditLogService,
                                           com.sw.ck.system.mapper.SysUserMapper sysUserMapper) {
        this.auditLogService = auditLogService;
        this.sysUserMapper = sysUserMapper;
    }

    @EventListener
    public void onUnhandledException(UnhandledExceptionAudit event) {
        SysAuditLog entry = new SysAuditLog();
        entry.setLogType(SysAuditLog.TYPE_EXCEPTION);
        entry.setResult(SysAuditLog.RESULT_FAIL);
        entry.setUserId(event.userId());
        entry.setTenantId(event.tenantId());
        entry.setUsername(resolveUsername(event.userId()));
        entry.setModule(moduleOf(event.uri()));
        entry.setAction(event.method());
        entry.setTarget(event.uri());
        entry.setExceptionType(event.exceptionType());
        entry.setSummary(event.summary());
        entry.setTraceId(event.traceId());
        auditLogService.record(entry);
    }

    /** 按 userId 补全账号；查不到（已删除/预登录）保持空。 */
    private String resolveUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        try (com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.Suspended ignored =
                     com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension.suspended()) {
            var user = sysUserMapper.selectById(userId);
            return user == null ? null : user.getUsername();
        } catch (Exception e) {
            return null;
        }
    }

    /** 请求路径首段作为模块（/system/xxx → system）。 */
    private String moduleOf(String uri) {
        if (uri == null || uri.isEmpty() || uri.charAt(0) != '/') {
            return uri;
        }
        int end = uri.indexOf('/', 1);
        return end > 1 ? uri.substring(1, end) : uri.substring(1);
    }
}
