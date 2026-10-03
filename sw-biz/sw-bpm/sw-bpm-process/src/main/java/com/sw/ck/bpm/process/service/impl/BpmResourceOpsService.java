package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.BpmResourceOpsViews;
import com.sw.ck.bpm.process.entity.BpmResourcePolicy;
import com.sw.ck.bpm.process.entity.BpmResourceUsage;
import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.bpm.process.queue.ResourceAssuranceReconcileJob;
import com.sw.ck.bpm.process.service.ResourceAdmissionService;
import com.sw.ck.bpm.process.service.ResourceFactView;
import com.sw.ck.bpm.process.service.ResourcePolicyService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.form.api.port.FormTxnActionPort;
import com.sw.ck.form.api.port.TxnActionRuntimePort;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源保障运维查询服务（RG04/RG05/RG06）。
 * <p>
 * 租户隔离与权限：查看权限（{@code workflow:resource:view}）只能看本租户数据；
 * 跨租户运维需独立管理权限（{@code workflow:resource:manage}，超管按语义旁路）——
 * 服务端显式强制，不依赖前端过滤。汇总指标低基数（状态/类别/段/租户聚合），
 * 明细按页查询（size 硬上限 200），不以 commandId/用户ID/动作版本生成无界标签。
 * </p>
 */
@Service
public class BpmResourceOpsService {

    private static final Logger log = LoggerFactory.getLogger(BpmResourceOpsService.class);

    static final String VIEW_PERMISSION = "workflow:resource:view";
    static final String MANAGE_PERMISSION = "workflow:resource:manage";
    private static final long MAX_PAGE_SIZE = 200;

    private final ResourcePolicyService policyService;
    private final ResourceAdmissionService admissionService;
    private final ResourceFactView factView;
    private final ResourceAssuranceReconcileJob reconcileJob;
    private final BpmResourceUsageMapper usageMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<TxnActionRuntimePort> runtimePortProvider;
    private final ObjectProvider<FormTxnActionPort> txnActionPortProvider;
    private final org.springframework.context.ApplicationContext applicationContext;
    private final org.springframework.core.env.Environment environment;

    @Value("${flowable.async-executor-activate:false}")
    private boolean flowableAsyncActive;

    @Value("${flowable.process.async.executor.core-pool-size:8}")
    private int flowableAsyncThreads;

    @Value("${sw.bpm.command.poll-interval-millis:500}")
    private long dispatcherPollMillis;

    @Value("${sw.bpm.command.p0-poll-interval-millis:100}")
    private long dispatcherP0PollMillis;

    @Value("${sw.bpm.command.batch-size:20}")
    private int dispatcherBatchSize;

    @Value("${sw.bpm.command.p0-batch-size:5}")
    private int dispatcherP0BatchSize;

    @Value("${sw.form.txn-action.realtime-global-concurrency:16}")
    private int realtimeGlobalConcurrency;

    @Value("${sw.form.txn-action.realtime-tenant-concurrency:8}")
    private int realtimeTenantConcurrency;

    public BpmResourceOpsService(ResourcePolicyService policyService,
                                 ResourceAdmissionService admissionService,
                                 ResourceFactView factView,
                                 ResourceAssuranceReconcileJob reconcileJob,
                                 BpmResourceUsageMapper usageMapper,
                                 JdbcTemplate jdbcTemplate,
                                 ObjectProvider<TxnActionRuntimePort> runtimePortProvider,
                                 ObjectProvider<FormTxnActionPort> txnActionPortProvider,
                                 org.springframework.context.ApplicationContext applicationContext,
                                 org.springframework.core.env.Environment environment) {
        this.policyService = policyService;
        this.admissionService = admissionService;
        this.factView = factView;
        this.reconcileJob = reconcileJob;
        this.usageMapper = usageMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.runtimePortProvider = runtimePortProvider;
        this.txnActionPortProvider = txnActionPortProvider;
        this.applicationContext = applicationContext;
        this.environment = environment;
    }

    // ==================== RG04 运行画像 ====================

    public BpmResourceOpsViews.RuntimeProfile profile() {
        requireViewer();
        BpmResourcePolicy active = admissionService.findActivePolicy();
        List<String> violations = active == null
                ? List.of() : policyService.checkEnablement(active);
        return new BpmResourceOpsViews.RuntimeProfile(
                now(),
                active != null,
                active,
                violations,
                poolProfile(),
                asyncExecutorProfile(),
                dispatcherProfile(),
                consumersProfile(),
                usageRows(),
                factView.factBySegment(),
                factView.factByTenant(),
                realtimeGuardProfile(),
                "占用事实=命令/批次项/引擎队列持久行（ResourceFactView）；计数行为准入加速器，"
                        + "漂移由对账 CAS 修复；上一轮对账结果见日志与 usage/fact 勾稽字段");
    }

    private Map<String, Object> poolProfile() {
        Map<String, Object> pool = new LinkedHashMap<>();
        try {
            for (javax.sql.DataSource dataSource : applicationContext.getBeansOfType(javax.sql.DataSource.class)
                    .values()) {
                // Druid 不在本模块编译类路径；路由数据源经 getDataSources() 展开内层数据源，
                // 再沿 realDataSource 解包反射回读实际池值（运行事实）
                java.util.List<Object> candidates = new ArrayList<>();
                if (dataSource.getClass().getSimpleName().contains("DynamicRouting")) {
                    Object map = dataSource.getClass().getMethod("getDataSources").invoke(dataSource);
                    if (map instanceof java.util.Map<?, ?> m) {
                        candidates.addAll(m.values());
                    }
                } else {
                    candidates.add(dataSource);
                }
                for (Object candidate : candidates) {
                    Object current = candidate;
                    for (int depth = 0; current != null && depth < 6; depth++) {
                        if (current.getClass().getSimpleName().contains("Druid")) {
                            pool.put("actualPoolType", current.getClass().getSimpleName());
                            pool.put("actualMaxActive",
                                    current.getClass().getMethod("getMaxActive").invoke(current));
                            pool.put("actualInitialSize",
                                    current.getClass().getMethod("getInitialSize").invoke(current));
                            pool.put("actualMinIdle",
                                    current.getClass().getMethod("getMinIdle").invoke(current));
                            pool.put("actualMaxWait",
                                    current.getClass().getMethod("getMaxWait").invoke(current));
                            break;
                        }
                        current = unwrapDataSourceField(current, "realDataSource");
                    }
                }
            }
        } catch (Exception e) {
            pool.put("actualPoolReadError", e.getClass().getSimpleName());
        }
        pool.put("configuredMaxActive", environment.getProperty(
                "spring.datasource.dynamic.druid.max-active", "20"));
        pool.put("note", "prod 默认池 5 / 异步 OFF 的启用风险只在显式启用检查中定性（RG04），"
                + "无效启用在开启新受理前被明确拒绝");
        return pool;
    }

    private Map<String, Object> asyncExecutorProfile() {
        Map<String, Object> executor = new LinkedHashMap<>();
        executor.put("asyncExecutorActivate", flowableAsyncActive);
        executor.put("corePoolSize", flowableAsyncThreads);
        executor.put("enginePendingJobs", factView.enginePendingJobs());
        executor.put("engineDeadLetterJobs", factView.engineDeadLetterJobs());
        executor.put("note", "轻流程目标异步消费链受公平/保留合同约束；死信 job 需运维关注");
        return executor;
    }

    private Map<String, Object> dispatcherProfile() {
        Map<String, Object> dispatcher = new LinkedHashMap<>();
        dispatcher.put("normalPollMillis", dispatcherPollMillis);
        dispatcher.put("p0PollMillis", dispatcherP0PollMillis);
        dispatcher.put("normalBatchSize", dispatcherBatchSize);
        dispatcher.put("p0BatchSize", dispatcherP0BatchSize);
        BpmResourcePolicy policy = admissionService.findActivePolicy();
        dispatcher.put("batchPollClaimLimit", policy == null ? "未启用策略（不限）"
                : String.valueOf(policy.getBatchPollClaimLimit()));
        dispatcher.put("batchSliceItems", policy == null ? "未启用策略（不限）"
                : String.valueOf(policy.getBatchSliceItems()));
        dispatcher.put("note", "租户公平领取按活跃租户切片；批量每轮限量+切片让出，"
                + "保证共享预算内生产与 OA 各有推进机会");
        return dispatcher;
    }

    private Map<String, Object> consumersProfile() {
        Map<String, Object> consumers = new LinkedHashMap<>();
        consumers.put("flowableAsyncActive", flowableAsyncActive);
        boolean batchConsumer = consumerBeans().stream()
                .anyMatch(handler -> handler.types().contains(
                        com.sw.ck.bpm.process.entity.CommandTypeEnum.BATCH_INVOKE));
        consumers.put("batchInvokeHandlerRegistered", batchConsumer);
        return consumers;
    }

    private List<com.sw.ck.bpm.process.queue.BpmCommandHandler> consumerBeans() {
        return new ArrayList<>(applicationContext
                .getBeansOfType(com.sw.ck.bpm.process.queue.BpmCommandHandler.class).values());
    }

    private Map<String, Object> realtimeGuardProfile() {
        Map<String, Object> guard = new LinkedHashMap<>();
        TxnActionRuntimePort port = runtimePortProvider.getIfAvailable();
        if (port == null) {
            guard.put("available", false);
            guard.put("configuredGlobalMax", realtimeGlobalConcurrency);
            guard.put("configuredTenantMax", realtimeTenantConcurrency);
            return guard;
        }
        TxnActionRuntimePort.RealtimeGuardProfile profile = port.realtimeGuardProfile();
        guard.put("available", true);
        guard.put("globalMaxConcurrent", profile.globalMaxConcurrent());
        guard.put("tenantMaxConcurrent", profile.tenantMaxConcurrent());
        guard.put("globalInFlight", profile.globalInFlight());
        return guard;
    }

    private Map<String, Long> usageRows() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (BpmResourceUsage row : usageMapper.selectList(null)) {
            String key = row.getScope() + "|" + row.getScopeKey() + "|" + row.getSegment();
            result.put(key, row.getOutstanding() == null ? 0 : row.getOutstanding());
        }
        return result;
    }

    // ==================== RG05 积压汇总与明细 ====================

    public BpmResourceOpsViews.BacklogSummary backlogSummary() {
        requireViewer();
        Long tenantScope = viewerTenantOrNull();
        Map<String, Long> factBySegment = factView.factBySegment();
        Map<String, Long> usage = usageRows();

        List<Map<String, Object>> byStatus = tenantScope == null
                ? groupRows("SELECT status, COUNT(*) AS cnt FROM sw_bpm_command "
                + "WHERE deleted = 0 GROUP BY status", null)
                : groupRows("SELECT status, COUNT(*) AS cnt FROM sw_bpm_command "
                + "WHERE deleted = 0 AND tenant_id = ? GROUP BY status", tenantScope);
        List<Map<String, Object>> byClass = tenantScope == null
                ? groupRows("SELECT COALESCE(resource_class, 'NONE') AS resource_class, COUNT(*) AS cnt "
                + "FROM sw_bpm_command WHERE deleted = 0 GROUP BY resource_class", null)
                : groupRows("SELECT COALESCE(resource_class, 'NONE') AS resource_class, COUNT(*) AS cnt "
                + "FROM sw_bpm_command WHERE deleted = 0 AND tenant_id = ? GROUP BY resource_class",
                tenantScope);

        long open = 0;
        long incompleteUnits = 0;
        for (Map<String, Object> row : byStatus) {
            String status = String.valueOf(row.get("status"));
            long cnt = ((Number) row.get("cnt")).longValue();
            if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
                open += cnt;
            }
        }
        for (Long value : factBySegment.values()) {
            incompleteUnits += value;
        }
        Map<String, Long> engine = new LinkedHashMap<>();
        engine.put("pendingJobs", factView.enginePendingJobs());
        engine.put("deadLetterJobs", factView.engineDeadLetterJobs());
        Long batchPending = tenantScope == null
                ? jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_batch_item WHERE status = 'PENDING'", Long.class)
                : jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command_batch_item i "
                        + "JOIN sw_bpm_command_batch b ON b.id = i.batch_id "
                        + "WHERE i.status = 'PENDING' AND b.tenant_id = ?", Long.class, tenantScope);
        long counterTotal = usage.getOrDefault("GLOBAL|0|TOTAL", 0L);
        long factTotal = factBySegment.values().stream().mapToLong(Long::longValue).sum();
        return new BpmResourceOpsViews.BacklogSummary(now(),
                "汇总为当前时点全量口径；正式保障窗口的时效/结果判定以测量报告（10min 窗口）为准",
                "完成点口径：命令完成 / 流程已启动 / 目标动作完成（TARGET_ACTION_DONE）为不同完成点，"
                        + "轻流程目标未决不计入已释放",
                open, incompleteUnits, byStatus, byClass, engine,
                batchPending == null ? 0 : batchPending, usage, factBySegment,
                counterTotal == factTotal);
    }

    public BpmResourceOpsViews.CommandPage backlogCommands(long page, long size, Long tenantId,
                                                           String status, String resourceClass,
                                                           String channel, Integer policyVersion) {
        Long scope = resolveScope(tenantId);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        long offset = Math.max(0, page - 1) * pageSize;
        StringBuilder where = new StringBuilder(" WHERE deleted = 0 ");
        List<Object> args = new ArrayList<>();
        if (scope != null) {
            where.append(" AND tenant_id = ? ");
            args.add(scope);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ? ");
            args.add(status);
        }
        if (resourceClass != null && !resourceClass.isBlank()) {
            where.append(" AND resource_class = ? ");
            args.add(resourceClass);
        }
        if (channel != null && !channel.isBlank()) {
            where.append(" AND channel = ? ");
            args.add(channel);
        }
        if (policyVersion != null) {
            where.append(" AND policy_version = ? ");
            args.add(policyVersion);
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_command" + where, Long.class, args.toArray());
        List<Object> rowsArgs = new ArrayList<>(args);
        rowsArgs.add(pageSize);
        rowsArgs.add(offset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, command_key, command_type, channel, status, resource_class, resource_units, "
                        + "resource_segment, policy_version, retry_count, tenant_id, initiator_id, "
                        + "create_time, claimed_at, finished_at, deadline_at, overdue_at, "
                        + "resource_released_at, failure_reason "
                        + "FROM sw_bpm_command" + where + " ORDER BY create_time DESC LIMIT ? OFFSET ?",
                rowsArgs.toArray());
        return new BpmResourceOpsViews.CommandPage(Math.max(1, page), pageSize,
                total == null ? 0 : total, rows);
    }

    public BpmResourceOpsViews.CommandDetail commandDetail(long commandId) {
        requireViewer();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, command_key, command_type, channel, status, resource_class, resource_units, "
                        + "resource_segment, policy_version, retry_count, tenant_id, initiator_id, payload, "
                        + "result, failure_reason, create_time, claimed_at, finished_at, deadline_at, "
                        + "overdue_at, resource_released_at "
                        + "FROM sw_bpm_command WHERE id = ? AND deleted = 0", commandId);
        if (rows.isEmpty()) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "命令不存在");
        }
        Map<String, Object> command = rows.get(0);
        // 租户边界：非管理权限只能查看自己租户（且自己发起）的对象
        LoginUser viewer = LoginUserHolder.get();
        long rowTenant = ((Number) command.get("tenant_id")).longValue();
        if (!isManager() && (viewer.getTenantId() == null || viewer.getTenantId() != rowTenant)) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权查看该命令");
        }
        Map<String, Object> effect = jdbcTemplate.queryForList(
                "SELECT command_id, logical_command_id, result_json, biz_ref, create_time "
                        + "FROM sw_bpm_command_effect WHERE command_id = ?", commandId)
                .stream().findFirst().orElse(null);
        List<Map<String, Object>> rejects = jdbcTemplate.queryForList(
                "SELECT id, reject_scope, requested_units, reason_code, detail, create_time "
                        + "FROM sw_bpm_resource_reject_log WHERE command_key = ? ORDER BY create_time DESC",
                String.valueOf(command.get("command_key")));
        List<Object> targets = new ArrayList<>();
        if ("FLOW_START".equals(String.valueOf(command.get("command_type")))) {
            String commandKey = String.valueOf(command.get("command_key"));
            if (commandKey.startsWith("FLOW_START:")) {
                FormTxnActionPort port = txnActionPortProvider.getIfAvailable();
                if (port != null) {
                    targets.addAll(port.listInvocationsByBizRecord(
                            commandKey.substring("FLOW_START:".length()), 20));
                }
            }
        }
        return new BpmResourceOpsViews.CommandDetail(command, effect, rejects, targets);
    }

    public BpmResourceOpsViews.RejectPage rejects(long page, long size, Long tenantId) {
        Long scope = resolveScope(tenantId);
        long pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        long offset = Math.max(0, page - 1) * pageSize;
        String where = scope == null ? "" : " WHERE tenant_id = ? ";
        Object[] args = scope == null ? new Object[0] : new Object[]{scope};
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_resource_reject_log" + where, Long.class, args);
        Object[] pageArgs = scope == null
                ? new Object[]{pageSize, offset}
                : new Object[]{scope, pageSize, offset};
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, tenant_id, policy_version, resource_class, reject_scope, requested_units, "
                        + "reason_code, detail, command_key, create_time "
                        + "FROM sw_bpm_resource_reject_log" + where
                        + " ORDER BY create_time DESC LIMIT ? OFFSET ?", pageArgs);
        return new BpmResourceOpsViews.RejectPage(Math.max(1, page), pageSize,
                total == null ? 0 : total, rows);
    }

    // ==================== 权限与范围 ====================

    private boolean isManager() {
        LoginUser user = LoginUserHolder.get();
        return user != null && (user.isSuperAdmin()
                || (user.getPermissions() != null
                && user.getPermissions().contains(MANAGE_PERMISSION)));
    }

    private void requireViewer() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED, "未登录");
        }
        boolean allowed = user.isSuperAdmin()
                || (user.getPermissions() != null && user.getPermissions().contains(VIEW_PERMISSION));
        if (!allowed) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(),
                    "无权查看资源运维数据：缺少 " + VIEW_PERMISSION);
        }
    }

    /** 查询范围：管理权限可显式传租户（跨租户运维）；否则强制本租户。 */
    private Long resolveScope(Long tenantId) {
        requireViewer();
        if (isManager()) {
            return tenantId;
        }
        return LoginUserHolder.get().getTenantId();
    }

    private Long viewerTenantOrNull() {
        requireViewer();
        return isManager() ? null : LoginUserHolder.get().getTenantId();
    }

    private static Object unwrapDataSourceField(Object o, String field) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(o);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Map<String, Object>> groupRows(String sql, Object arg) {
        return arg == null
                ? jdbcTemplate.queryForList(sql)
                : jdbcTemplate.queryForList(sql, arg);
    }

    private String now() {
        return java.time.LocalDateTime.now().toString();
    }
}
