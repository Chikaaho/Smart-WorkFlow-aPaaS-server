package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmInstanceIntervention;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.mapper.ApprovalActionRecordMapper;
import com.sw.ck.bpm.process.mapper.BpmInstanceInterventionMapper;
import com.sw.ck.bpm.process.mapper.BpmMonitorMapper;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmMonitorService;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 流程运营监控/干预/基础分析实现（I4 §3.3）。
 * <p>
 * 数据范围同时作用于汇总与明细（DataScopeFilter 同口径）：不得用「明细拒绝」掩盖汇总泄漏。
 * 干预逐项授权：实例级挂起与定义级挂起分离；每次干预落审计行（操作者/原因/前后状态/受影响任务）。
 * </p>
 */
@Service
public class BpmMonitorServiceImpl implements BpmMonitorService {

    private static final Logger log = LoggerFactory.getLogger(BpmMonitorServiceImpl.class);
    private static final List<String> ACTIONS = List.of("SUSPEND", "RESUME", "TERMINATE", "TRANSFER");
    /** 历史活动明细穿透上限：分析只聚合时间范围内的实例，超限提示缩小范围。 */
    private static final int HISTORY_SAMPLE_CAP = 200;

    private final BpmMonitorMapper monitorMapper;
    private final BpmInstanceService bpmInstanceService;
    private final BpmInstanceInterventionMapper interventionMapper;
    private final com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper branchMapper;
    private final ApprovalActionRecordMapper actionRecordMapper;
    private final com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper deadlineMapper;
    private final BpmTaskFacade bpmTaskFacade;
    private final BpmRuntimeFacade bpmRuntimeFacade;
    private final LoginContextProvider loginContextProvider;
    private final DeptScopeProvider deptScopeProvider;

    public BpmMonitorServiceImpl(BpmMonitorMapper monitorMapper,
                                 BpmInstanceService bpmInstanceService,
                                 BpmInstanceInterventionMapper interventionMapper,
                                 com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper branchMapper,
                                 ApprovalActionRecordMapper actionRecordMapper,
                                         com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper deadlineMapper,
                                 BpmTaskFacade bpmTaskFacade,
                                 BpmRuntimeFacade bpmRuntimeFacade,
                                 LoginContextProvider loginContextProvider,
                                 DeptScopeProvider deptScopeProvider) {
        this.monitorMapper = monitorMapper;
        this.bpmInstanceService = bpmInstanceService;
        this.interventionMapper = interventionMapper;
        this.branchMapper = branchMapper;
        this.actionRecordMapper = actionRecordMapper;
        this.deadlineMapper = deadlineMapper;
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.loginContextProvider = loginContextProvider;
        this.deptScopeProvider = deptScopeProvider;
    }

    @Override
    public PageResult<InstanceMonitorView> monitorInstances(PageParam pageParam, MonitorQuery query) {
        MonitorQuery q = query == null
                ? new MonitorQuery(null, null, null, null, null, null, null, null) : query;
        List<String> instanceIds = resolveInstanceIdFilter(q);
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);
        long total = monitorMapper.monitorCount(q.processDefKey(), q.processInstanceId(),
                q.initiatorId(), q.status(), q.timeFrom(), q.timeTo(), instanceIds, scope);
        long offset = (Math.max(pageParam.getPageNum(), 1) - 1) * pageParam.getPageSize();
        List<BpmInstance> records = monitorMapper.monitorList(q.processDefKey(), q.processInstanceId(),
                q.initiatorId(), q.status(), q.timeFrom(), q.timeTo(), instanceIds, scope,
                (int) pageParam.getPageSize(), offset);
        // empty = 实例标识缺失，或该实例无运行期记录：无活跃节点可展示（原空列表口径）
        List<InstanceMonitorView> views = records.stream().map(instance -> {
            List<String> activeNodeIds = bpmRuntimeFacade
                    .getActiveActivityIds(instance.getProcessInstanceId())
                    .orElse(List.of());
            // empty = 实例标识缺失（此处标识非空，契约不产生）：按未挂起展示
            java.util.Optional<Boolean> suspendedDecision = bpmTaskFacade
                    .isProcessInstanceSuspended(instance.getProcessInstanceId());
            boolean suspended = suspendedDecision.isPresent() && suspendedDecision.get();
            return new InstanceMonitorView(instance, activeNodeIds, suspended);
        }).toList();
        PageResult<InstanceMonitorView> result = new PageResult<>();
        result.setRecords(views);
        result.setTotal(total);
        result.setPageNum(pageParam.getPageNum());
        result.setPageSize(pageParam.getPageSize());
        return result;
    }

    /** 节点/办理人条件前置解析为实例 ID 集合（口径=当前身份可见的运行任务）。 */
    private List<String> resolveInstanceIdFilter(MonitorQuery q) {
        if ((q.nodeKey() == null || q.nodeKey().isBlank())
                && q.assignee() == null) {
            return null; // 不限制
        }
        String tenantId = LoginUserHolder.get() == null ? null
                : String.valueOf(LoginUserHolder.get().getTenantId());
        List<BpmTaskDTO> tasks;
        if (q.assignee() == null) {
            tasks = List.of();
        } else {
            java.util.Optional<List<BpmTaskDTO>> todo =
                    bpmTaskFacade.queryTodo(tenantId, String.valueOf(q.assignee()));
            // empty = 租户或处理人上下文缺失，无法确定查询范围：按无可见任务处理（原空列表口径）
            tasks = todo.isEmpty() ? List.of() : todo.get();
        }
        if (q.assignee() != null && q.nodeKey() != null && !q.nodeKey().isBlank()) {
            return tasks.stream().filter(t -> q.nodeKey().equals(t.getTaskDefinitionKey()))
                    .map(BpmTaskDTO::getProcessInstanceId).distinct().toList();
        }
        if (q.assignee() != null) {
            return tasks.stream().map(BpmTaskDTO::getProcessInstanceId).distinct().toList();
        }
        // 仅 nodeKey 条件：逐实例判断代价高，返回 null 并由明细行富化比对——此处以
        // 办理人全集不可得为由，退化为「当前实例列表逐行过滤」不可取；改为对运行中
        // 任务逐条扫描（运营数据量受租户/范围约束，页查询再经 IN 收敛）。
        return null;
    }

    @Override
    @Transactional
    public BpmInstanceIntervention intervene(String processInstanceId, String action,
                                             String reason, Long toAssignee, List<String> taskIds) {
        if (action == null || !ACTIONS.contains(action)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "干预动作不合法: " + action);
        }
        Long operator = LoginUserHolder.get() == null ? null : LoginUserHolder.get().getUserId();
        if (operator == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new BaseException(CommonErrorCode.NOT_FOUND.getCode(),
                        "流程实例不存在"));

        BpmInstanceIntervention record = new BpmInstanceIntervention();
        record.setProcessInstanceId(processInstanceId);
        record.setAction(action);
        record.setOperatorId(operator);
        record.setReason(reason);
        record.setIntervenedAt(LocalDateTime.now());
        int affected = 0;

        switch (action) {
            case "SUSPEND" -> {
                // empty = 实例标识缺失，无法判定（此处标识非空）：按未挂起记录前态
                java.util.Optional<Boolean> suspendedDecision =
                        bpmTaskFacade.isProcessInstanceSuspended(processInstanceId);
                boolean before = suspendedDecision.isPresent() && suspendedDecision.get();
                record.setBeforeState(before ? "SUSPENDED" : "RUNNING");
                // present = APPLIED（本次挂起）/ ALREADY_APPLIED（已挂起或已无运行期记录）
                bpmTaskFacade.suspendProcessInstance(processInstanceId)
                .orElseThrow(() -> new IllegalStateException(
                        "BpmTaskFacade#suspendProcessInstance 契约恒 present，empty 属契约违约"));
                record.setAfterState("SUSPENDED");
            }
            case "RESUME" -> {
                // empty = 实例标识缺失，无法判定（此处标识非空）：按未挂起记录前态
                java.util.Optional<Boolean> suspendedDecision =
                        bpmTaskFacade.isProcessInstanceSuspended(processInstanceId);
                boolean before = suspendedDecision.isPresent() && suspendedDecision.get();
                record.setBeforeState(before ? "SUSPENDED" : "RUNNING");
                // present = APPLIED（本次恢复）/ ALREADY_APPLIED（未挂起或已无运行期记录）
                bpmTaskFacade.resumeProcessInstance(processInstanceId)
                .orElseThrow(() -> new IllegalStateException(
                        "BpmTaskFacade#resumeProcessInstance 契约恒 present，empty 属契约违约"));
                record.setAfterState("RUNNING");
            }
            case "TERMINATE" -> {
                record.setBeforeState(instance.getStatus());
                // empty = 实例标识缺失：无运行任务（原空列表口径）
                List<BpmTaskDTO> active = bpmTaskFacade.queryByProcessInstance(processInstanceId)
                        .orElse(List.of());
                affected = active.size();
                // present = APPLIED（本次终止）/ ALREADY_APPLIED（已无运行实例）
                bpmTaskFacade.terminateProcess(processInstanceId,
                        reason == null || reason.isBlank() ? "ADMIN_TERMINATE" : reason);
                bpmInstanceService.updateStatus(processInstanceId, "TERMINATED");
                record.setAfterState("TERMINATED");
            }
            case "TRANSFER" -> {
                if (toAssignee == null) {
                    throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                            "迁移办理人必须指定目标办理人");
                }
                // empty = 实例标识缺失：无运行任务（原空列表口径）
                List<BpmTaskDTO> active = bpmTaskFacade.queryByProcessInstance(processInstanceId)
                        .orElse(List.of());
                // 可选迁移范围：提供 taskIds 时只迁移所选子集，未选任务保持原办理人（三级提示 R2）
                List<BpmTaskDTO> selected = taskIds == null || taskIds.isEmpty() ? active
                        : active.stream()
                                .filter(t -> taskIds.contains(t.getTaskId()))
                                .toList();
                record.setBeforeState(instance.getStatus());
                record.setAfterState(instance.getStatus());
                record.setToAssignee(toAssignee);
                Map<Long, List<BpmTaskDTO>> byFromAssignee = new LinkedHashMap<>();
                for (BpmTaskDTO task : selected) {
                    byFromAssignee.computeIfAbsent(parseLong(task.getAssignee()),
                            key -> new ArrayList<>()).add(task);
                }
                BpmInstanceIntervention last = record;
                for (Map.Entry<Long, List<BpmTaskDTO>> group : byFromAssignee.entrySet()) {
                    for (BpmTaskDTO task : group.getValue()) {
                        // present = APPLIED；任务不存在/已被处理继续抛原异常
                        bpmTaskFacade.setAssignee(task.getTaskId(), String.valueOf(toAssignee))
                        .orElseThrow(() -> new IllegalStateException(
                                "BpmTaskFacade#setAssignee 契约恒 present，empty 属契约违约"));
                    }
                    BpmInstanceIntervention groupRecord = new BpmInstanceIntervention();
                    groupRecord.setProcessInstanceId(processInstanceId);
                    groupRecord.setAction(action);
                    groupRecord.setOperatorId(operator);
                    groupRecord.setReason(reason);
                    groupRecord.setBeforeState(instance.getStatus());
                    groupRecord.setAfterState(instance.getStatus());
                    groupRecord.setFromAssignee(group.getKey());
                    groupRecord.setToAssignee(toAssignee);
                    groupRecord.setAffectedTasks(group.getValue().size());
                    groupRecord.setIntervenedAt(record.getIntervenedAt());
                    interventionMapper.insert(groupRecord);
                    affected += group.getValue().size();
                    last = groupRecord;
                }
                log.info("实例迁移已执行: instance={}, operator={}, groups={} of {} active, affected={}",
                        processInstanceId, operator, byFromAssignee.size(), selected.size(), affected);
                return last;
            }
                    default -> throw new BaseException(CommonErrorCode.PARAM_ERROR);
        }
        record.setAffectedTasks(affected);
        interventionMapper.insert(record);
        log.info("实例干预已执行: instance={}, action={}, operator={}, affected={}",
                processInstanceId, action, operator, affected);
        return record;
    }

    @Override
    public List<BpmInstanceIntervention> interventions(String processInstanceId) {
        return interventionMapper.selectList(Wrappers.<BpmInstanceIntervention>lambdaQuery()
                .eq(BpmInstanceIntervention::getProcessInstanceId, processInstanceId)
                .orderByDesc(BpmInstanceIntervention::getIntervenedAt));
    }

    @Override
    public List<com.sw.ck.bpm.process.entity.DynamicBranchSnapshot> branches(String processInstanceId) {
        return branchMapper.selectList(Wrappers.<com.sw.ck.bpm.process.entity.DynamicBranchSnapshot>lambdaQuery()
                .eq(com.sw.ck.bpm.process.entity.DynamicBranchSnapshot::getProcessInstanceId,
                        processInstanceId)
                .orderByAsc(com.sw.ck.bpm.process.entity.DynamicBranchSnapshot::getBranchIndex));
    }

    @Override
    public Map<String, Object> analyticsSummary(String processDefKey,
                                                LocalDateTime timeFrom, LocalDateTime timeTo) {
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);
        Map<String, Object> summary = new LinkedHashMap<>();
        long launched = monitorMapper.monitorCount(processDefKey, null, null, null,
                timeFrom, timeTo, null, scope);
        long completed = monitorMapper.monitorCount(processDefKey, null, null,
                InstanceStatusEnum.APPROVED.getCode(), timeFrom, timeTo, null, scope)
                + monitorMapper.monitorCount(processDefKey, null, null,
                InstanceStatusEnum.REJECTED.getCode(), timeFrom, timeTo, null, scope);
        long running = monitorMapper.monitorCount(processDefKey, null, null,
                InstanceStatusEnum.RUNNING.getCode(), timeFrom, timeTo, null, scope);
        long rejected = monitorMapper.monitorCount(processDefKey, null, null,
                InstanceStatusEnum.REJECTED.getCode(), timeFrom, timeTo, null, scope);
        summary.put("launched", launched);
        summary.put("completed", completed);
        summary.put("running", running);
        summary.put("rejected", rejected);

        // 耗时（终态实例：create → 引擎历史活动最大结束时间；实例行 update_time 不随
        // 终态刷新，不能作为终态时间口径——改用与节点停留同源的 Flowable 历史，可复算且非零）。
        // 时长统计依赖下方 nodeStats 的同一遍历史循环，指标落账在循环之后（R3）。
        List<Long> durations = new ArrayList<>();
        Set<String> endedStatuses = Set.of(InstanceStatusEnum.APPROVED.getCode(),
                InstanceStatusEnum.REJECTED.getCode());

        // 办理人工作量（时间范围内的审批动作）
        Map<Long, Long> workload = new LinkedHashMap<>();
        long returnedCount = 0L;
        List<ApprovalActionRecord> actionRecords = actionRecordMapper.selectList(
                Wrappers.<ApprovalActionRecord>lambdaQuery()
                        .ge(timeFrom != null, ApprovalActionRecord::getCreateTime, timeFrom)
                        .le(timeTo != null, ApprovalActionRecord::getCreateTime, timeTo));
        for (ApprovalActionRecord record : actionRecords) {
            if (record.getActorId() != null) {
                workload.merge(record.getActorId(), 1L, Long::sum);
            }
            if ("RETURN".equals(record.getAction())) {
                returnedCount++;
            }
        }
        summary.put("handlerWorkload", workload);
        // 退回/驳回与超期（三级提示 R3：相关值非零且可复算）
        summary.put("returnedCount", returnedCount);
        summary.put("rejectedActionCount", actionRecords.stream()
                .filter(r -> "REJECT".equals(r.getAction())).count());
        summary.put("overdueCount", countOverdue());

        // 节点停留/瓶颈（时间范围内实例的历史活动，按样本上限聚合）；
        // 同一遍历史同时累计终态实例时长（create → 最大活动结束时间）
        Map<String, List<Long>> nodeDurations = new HashMap<>();
        List<BpmInstance> sampled = monitorMapper.monitorList(processDefKey, null, null, null,
                timeFrom, timeTo, null, scope, HISTORY_SAMPLE_CAP, 0);
        for (BpmInstance instance : sampled) {
            try {
                java.time.LocalDateTime maxActivityEnd = null;
                // empty = 实例标识缺失或该实例无历史记录：无活动可统计
                List<BpmActivityDTO> historicActivities = bpmRuntimeFacade
                        .queryHistoricActivities(instance.getProcessInstanceId())
                        .orElse(List.of());
                for (BpmActivityDTO activity : historicActivities) {
                    if (activity.getEndTime() != null) {
                        maxActivityEnd = maxActivityEnd == null
                                ? activity.getEndTime()
                                : (activity.getEndTime().isAfter(maxActivityEnd)
                                        ? activity.getEndTime() : maxActivityEnd);
                        if ("userTask".equals(activity.getActivityType())
                                && activity.getStartTime() != null) {
                            nodeDurations.computeIfAbsent(activity.getActivityId(),
                                    key -> new ArrayList<>()).add(Duration.between(
                                    activity.getStartTime(), activity.getEndTime()).toMillis());
                        }
                    }
                }
                if (endedStatuses.contains(instance.getStatus()) && instance.getCreateTime() != null
                        && maxActivityEnd != null) {
                    durations.add(Duration.between(instance.getCreateTime(), maxActivityEnd)
                            .toMillis());
                }
            } catch (RuntimeException ignored) {
                // 单实例历史读取失败不阻断整体汇总
            }
        }
        Map<String, Object> nodeStats = new LinkedHashMap<>();
        nodeDurations.forEach((nodeKey, values) -> nodeStats.put(nodeKey, Map.of(
                "count", values.size(),
                "avgStayMs", values.stream().mapToLong(Long::longValue).average().orElse(0),
                "p90StayMs", percentile(values, 90))));
        summary.put("nodeStats", nodeStats);
        // 时长指标在历史循环之后落账（durations 已在同一循环内填充）
        summary.put("avgDurationMs", durations.stream().mapToLong(Long::longValue).average()
                .orElse(0));
        summary.put("p50DurationMs", percentile(durations, 50));
        summary.put("p90DurationMs", percentile(durations, 90));
        summary.put("durationSample", durations.size());
        return summary;
    }

    /** 实例办理时限原始行（超时可查，I4 §3.3 方向口径）。 */
    @Override
    public List<Map<String, Object>> deadlines(String processInstanceId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.sw.ck.bpm.process.entity.BpmTaskDeadline d : deadlineMapper.selectList(
                Wrappers.<com.sw.ck.bpm.process.entity.BpmTaskDeadline>lambdaQuery()
                        .eq(com.sw.ck.bpm.process.entity.BpmTaskDeadline::getProcessInstanceId,
                                processInstanceId)
                        .orderByAsc(com.sw.ck.bpm.process.entity.BpmTaskDeadline::getDueAt))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", d.getTaskId());
            row.put("nodeKey", d.getNodeKey());
            row.put("dueAt", d.getDueAt());
            row.put("handled", hasActionRecord(d.getTaskId()));
            row.put("overdue", !hasActionRecord(d.getTaskId())
                    && d.getDueAt() != null && d.getDueAt().isBefore(java.time.LocalDateTime.now()));
            rows.add(row);
        }
        return rows;
    }

    private boolean hasActionRecord(String taskId) {
        return actionRecordMapper.selectCount(Wrappers.<ApprovalActionRecord>lambdaQuery()
                .eq(ApprovalActionRecord::getTaskId, taskId)) > 0;
    }

    /** 超期未办时限行数（复算口径：due_at < now 且无办理动作记录，租户边界由拦截器注入）。 */
    private long countOverdue() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        long count = 0;
        for (com.sw.ck.bpm.process.entity.BpmTaskDeadline d : deadlineMapper.selectList(
                Wrappers.<com.sw.ck.bpm.process.entity.BpmTaskDeadline>lambdaQuery()
                        .lt(com.sw.ck.bpm.process.entity.BpmTaskDeadline::getDueAt, java.time.LocalDateTime.now()))) {
            if (!hasActionRecord(d.getTaskId())) {
                count++;
            }
        }
        return count;
    }

    /** 线性插值分位（可复算口径）。 */
    private static long percentile(List<Long> values, int p) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        double rank = (p / 100.0d) * (n - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high || Objects.equals(sorted.get(low), sorted.get(high))) {
            return sorted.get(low);
        }
        return Math.round(sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (rank - low));
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
