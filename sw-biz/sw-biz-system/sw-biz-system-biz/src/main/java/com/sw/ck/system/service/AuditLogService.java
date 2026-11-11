package com.sw.ck.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.system.entity.SysAuditLog;
import com.sw.ck.system.mapper.SysAuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台审计日志写入与查询（1.0.0 F3）。
 * <p>
 * 写入为尽力而为：任何异常（含写库失败）只记结构化日志、绝不向上抛，
 * 保证业务（尤其登录与登录失败）不受审计可用性影响（F3-5）；登录失败记录
 * 与登录同链路同步写库，不因登录被拒而丢失。写入统一在租户挂载 suspending 下
 * 显式携带 tenant_id（预登录行 tenant_id=0），文本列统一截断。
 * </p>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final SysAuditLogMapper auditLogMapper;

    public AuditLogService(SysAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 写入一条审计日志。租户未知（预登录）传 0；任何内部异常吞掉并记 warn。
     */
    public void record(SysAuditLog entry) {
        if (entry.getTenantId() == null) {
            entry.setTenantId(0L);
        }
        if (entry.getCreateTime() == null) {
            entry.setCreateTime(LocalDateTime.now());
        }
        entry.setLogType(truncate(entry.getLogType(), 20));
        entry.setUsername(truncate(entry.getUsername(), 64));
        entry.setResult(truncate(entry.getResult(), 20));
        entry.setFailReason(truncate(entry.getFailReason(), 255));
        entry.setModule(truncate(entry.getModule(), 50));
        entry.setAction(truncate(entry.getAction(), 50));
        entry.setTarget(truncate(entry.getTarget(), 255));
        entry.setIp(truncate(entry.getIp(), 64));
        entry.setUserAgent(truncate(entry.getUserAgent(), 255));
        entry.setExceptionType(truncate(entry.getExceptionType(), 200));
        entry.setSummary(truncate(entry.getSummary(), 1000));
        entry.setTraceId(truncate(entry.getTraceId(), 64));
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("审计日志写入失败(忽略,不影响业务): logType={} username={} err={}",
                    entry.getLogType(), entry.getUsername(), e.getMessage());
        }
    }

    /**
     * 分页查询（F3-4）。租户隔离由 TenantLineHandler 常规生效；超管按租户筛选时
     * 由调用方（控制器经 ManagementTenantContext）在挂载 suspending 下查询。
     */
    public PageResult<SysAuditLog> page(PageParam pageParam, AuditLogPageQuery query,
                                        boolean suspendTenant) {
        // 超管按租户筛选时挂载 suspending（自定 tenant_id 条件）；普通租户走常规租户隔离
        if (suspendTenant) {
            try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
                return doPage(pageParam, query);
            }
        }
        return doPage(pageParam, query);
    }

    private PageResult<SysAuditLog> doPage(PageParam pageParam, AuditLogPageQuery query) {
        var wrapper = Wrappers.lambdaQuery(SysAuditLog.class)
                .eq(query.getLogType() != null && !query.getLogType().isBlank(),
                        SysAuditLog::getLogType, query.getLogType())
                .eq(query.getResult() != null && !query.getResult().isBlank(),
                        SysAuditLog::getResult, query.getResult())
                .eq(query.getModule() != null && !query.getModule().isBlank(),
                        SysAuditLog::getModule, query.getModule())
                .eq(query.getTenantId() != null, SysAuditLog::getTenantId, query.getTenantId())
                .like(query.getUsername() != null && !query.getUsername().isBlank(),
                        SysAuditLog::getUsername, query.getUsername())
                .like(query.getIp() != null && !query.getIp().isBlank(),
                        SysAuditLog::getIp, query.getIp())
                .ge(query.getBeginTime() != null, SysAuditLog::getCreateTime, query.getBeginTime())
                .le(query.getEndTime() != null, SysAuditLog::getCreateTime, query.getEndTime())
                .orderByDesc(SysAuditLog::getCreateTime)
                .orderByDesc(SysAuditLog::getId);
        var page = auditLogMapper.selectPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
                        pageParam.getPageNum(), pageParam.getPageSize()), wrapper);
        return PageResult.of(page);
    }

    /** 清理过期记录（F3-6）：按时间物理删除，分批防长事务；返回删除总行数。 */
    public int purgeBefore(LocalDateTime cutoff, int batch) {
        int total = 0;
        while (true) {
            List<Long> ids;
            try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
                ids = auditLogMapper.selectList(Wrappers.lambdaQuery(SysAuditLog.class)
                                .select(SysAuditLog::getId)
                                .lt(SysAuditLog::getCreateTime, cutoff)
                                .last("LIMIT " + batch))
                        .stream().map(SysAuditLog::getId).toList();
                if (ids.isEmpty()) {
                    return total;
                }
                int deleted = auditLogMapper.deleteByIds(ids);
                if (deleted <= 0) {
                    // 并发删除/锁冲突：本轮结束，避免同一批 id 反复查询造成死循环（下一轮再试）
                    return total;
                }
                total += deleted;
            } catch (Exception e) {
                log.warn("审计日志清理批次失败(下轮重试): deleted={} err={}", total, e.getMessage());
                return total;
            }
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 查询筛选参数（F3-4：类型/结果/模块/账号/IP/时间范围/租户）。 */
    public static class AuditLogPageQuery {
        private String logType;
        private String result;
        private String module;
        private String username;
        private String ip;
        private LocalDateTime beginTime;
        private LocalDateTime endTime;
        /** 超管按租户筛选；普通租户忽略（隔离由租户拦截器强制） */
        private Long tenantId;

        public String getLogType() {
            return logType;
        }

        public void setLogType(String logType) {
            this.logType = logType;
        }

        public String getResult() {
            return result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getModule() {
            return module;
        }

        public void setModule(String module) {
            this.module = module;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public LocalDateTime getBeginTime() {
            return beginTime;
        }

        public void setBeginTime(LocalDateTime beginTime) {
            this.beginTime = beginTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(Long tenantId) {
            this.tenantId = tenantId;
        }
    }

    /** 从请求解析客户端 IP（X-Forwarded-For 首段优先，回落 remoteAddr）。 */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    /** 请求路径 + 查询串（不含 context-path）。 */
    public static String requestTarget(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? uri : uri + "?" + query;
    }
}
