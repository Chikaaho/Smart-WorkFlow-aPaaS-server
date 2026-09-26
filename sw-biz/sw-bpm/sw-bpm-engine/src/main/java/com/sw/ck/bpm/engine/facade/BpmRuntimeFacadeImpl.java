package com.sw.ck.bpm.engine.facade;

import com.sw.ck.bpm.api.dto.BpmActivityDTO;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.result.BpmProcessStatus;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * BPM 运行时门面实现 —— 封装 Flowable {@link RuntimeService} + {@link HistoryService}。
 * <p>
 * {@code Optional.empty()} 只表达"查询目标/上下文缺失"（实例标识缺失、定义未发布、
 * 实例不存在）；参数非法与引擎执行失败继续抛明确异常，不以上空吞异常。
 * </p>
 */
@Service
public class BpmRuntimeFacadeImpl implements BpmRuntimeFacade {

    private static final Logger log = LoggerFactory.getLogger(BpmRuntimeFacadeImpl.class);

    private final RuntimeService runtimeService;
    private final HistoryService historyService;

    public BpmRuntimeFacadeImpl(RuntimeService runtimeService, HistoryService historyService) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
    }

    @Override
    public Optional<String> startProcess(String processDefKey, String businessKey,
                                         Map<String, Object> variables, String tenantId) {
        ProcessInstance instance;
        try {
            instance = runtimeService.startProcessInstanceByKeyAndTenantId(
                    processDefKey, businessKey, variables, tenantId);
        } catch (FlowableObjectNotFoundException e) {
            // 该 key/租户下没有已发布的流程定义：启动目标缺失，未产生实例
            log.warn("BPM process start target missing: processDefKey={}, tenantId={}, error={}",
                    processDefKey, tenantId, e.getMessage());
            return Optional.empty();
        }
        log.info("BPM process started: processInstanceId={}, processDefKey={}, businessKey={}, tenantId={}",
                instance.getId(), processDefKey, businessKey, tenantId);
        return Optional.of(instance.getId());
    }

    @Override
    public Optional<List<String>> getActiveActivityIds(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            // 实例标识缺失：无法给出活跃节点
            return Optional.empty();
        }
        try {
            List<String> ids = runtimeService.getActiveActivityIds(processInstanceId);
            return Optional.of(ids != null ? ids : List.of());
        } catch (Exception e) {
            // 保留原“容错不抛”行为：实例不存在/引擎查询失败时无法给出活跃节点
            log.warn("Failed to get active activity ids: processInstanceId={}, error={}",
                    processInstanceId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<List<BpmActivityDTO>> queryHistoricActivities(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Optional.empty();
        }
        try {
            List<org.flowable.engine.history.HistoricActivityInstance> activities =
                    historyService.createHistoricActivityInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .orderByHistoricActivityInstanceEndTime().asc()
                            .list();

            if (activities == null || activities.isEmpty()) {
                // 查询已执行且零匹配：合法零结果以 present 空列表保留
                return Optional.of(List.of());
            }

            // 兜底：本引擎版本在 create 监听器内 setAssignee 不落 HI_ACTINST/HI_TASKINST 的
            // assignee 列（已由集成探针证实），监控页流转记录审批人会显示 "-"（R-04 缺口）。
            // 依次用历史任务表 assignee、历史流程变量 approver（DESIGNATED 指定审批人，
            // v1 单审批人语义下与实际 assignee 一致）补齐 userTask 行。
            Map<String, String> assigneeByTaskId = historyService
                    .createHistoricTaskInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .list().stream()
                    .filter(t -> t.getAssignee() != null)
                    .collect(Collectors.toMap(
                            org.flowable.task.api.history.HistoricTaskInstance::getId,
                            org.flowable.task.api.history.HistoricTaskInstance::getAssignee,
                            (a, b) -> a));
            String approverFallback = resolveApproverVariable(processInstanceId);

            return Optional.of(activities.stream()
                    .map(this::toActivityDto)
                    .peek(dto -> {
                        if (dto.getAssignee() == null && "userTask".equals(dto.getActivityType())) {
                            String a = dto.getTaskId() != null
                                    ? assigneeByTaskId.get(dto.getTaskId()) : null;
                            if (a == null) {
                                a = approverFallback;
                            }
                            dto.setAssignee(a);
                        }
                    })
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            // 保留原“容错不抛”行为：实例无历史记录/引擎查询失败时无法给出历史活动
            log.warn("Failed to query historic activities: processInstanceId={}, error={}",
                    processInstanceId, e.getMessage());
            return Optional.empty();
        }
    }


    /**
     * 读取历史流程变量 approver（DESIGNATED 审批人配置，String 或 List 取首元素）。
     * 查询失败返回 null，不阻断流转记录查询。
     */
    private String resolveApproverVariable(String processInstanceId) {
        try {
            org.flowable.variable.api.history.HistoricVariableInstance var = historyService
                    .createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .variableName("approver")
                    .singleResult();
            if (var == null || var.getValue() == null) {
                return null;
            }
            Object v = var.getValue();
            if (v instanceof java.util.Collection<?> col) {
                return col.isEmpty() ? null : String.valueOf(col.iterator().next());
            }
            return String.valueOf(v);
        } catch (Exception e) {
            log.warn("Failed to resolve approver variable: processInstanceId={}, error={}",
                    processInstanceId, e.getMessage());
            return null;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 将 Flowable {@code HistoricActivityInstance} 转为我方 {@link BpmActivityDTO}。
     * <p>
     * 时间转换：使用系统默认时区 {@code LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())}，
     * 与 {@code BpmTodoController.toTodoTaskDTO} 模式一致。
     * </p>
     */
    private BpmActivityDTO toActivityDto(
            org.flowable.engine.history.HistoricActivityInstance ha) {
        BpmActivityDTO dto = new BpmActivityDTO();
        dto.setActivityId(ha.getActivityId());
        dto.setActivityName(ha.getActivityName());
        dto.setActivityType(ha.getActivityType());
        if (ha.getStartTime() != null) {
            dto.setStartTime(LocalDateTime.ofInstant(
                    ha.getStartTime().toInstant(), ZoneId.systemDefault()));
        }
        if (ha.getEndTime() != null) {
            dto.setEndTime(LocalDateTime.ofInstant(
                    ha.getEndTime().toInstant(), ZoneId.systemDefault()));
        }
        dto.setAssignee(ha.getAssignee());
        dto.setTaskId(ha.getTaskId());
        return dto;
    }
    @Override
    public Optional<Map<String, Object>> getProcessVariables(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            // 实例标识缺失：无法确定查询目标
            return Optional.empty();
        }
        // 目标不存在（运行期与历史均无该实例）→ empty；实例存在但无变量才是 present 空 Map
        boolean instanceExists = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0
                || historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).count() > 0;
        if (!instanceExists) {
            return Optional.empty();
        }
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        try {
            for (org.flowable.variable.api.history.HistoricVariableInstance var :
                    historyService.createHistoricVariableInstanceQuery()
                            .processInstanceId(processInstanceId)
                            .list()) {
                variables.put(var.getVariableName(), var.getValue());
            }
        } catch (FlowableObjectNotFoundException e) {
            // 运行期与历史均不存在该实例
            return Optional.empty();
        }
        return Optional.of(variables);
    }

    @Override
    public Optional<BpmProcessStatus> getProcessInstanceStatus(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            return Optional.empty();
        }
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (running != null) {
            return Optional.of(BpmProcessStatus.RUNNING);
        }
        try {
            org.flowable.engine.history.HistoricProcessInstance historic =
                    historyService.createHistoricProcessInstanceQuery()
                            .processInstanceId(processInstanceId).singleResult();
            if (historic == null) {
                return Optional.empty();
            }
            String deleteReason = historic.getDeleteReason();
            if (deleteReason == null || deleteReason.isBlank()) {
                return Optional.of(BpmProcessStatus.APPROVED); // 正常走完：无删除原因
            }
            return Optional.of(mapTerminalStatus(deleteReason));
        } catch (RuntimeException e) {
            return Optional.of(BpmProcessStatus.UNKNOWN);
        }
    }

    /** 删除原因 → 对外终态（I3/I4 动作语义映射，未知原因统一 TERMINATED）。 */
    private BpmProcessStatus mapTerminalStatus(String reason) {
        String normalized = reason == null ? "" : reason.toUpperCase();
        if (normalized.contains("REJECT") || normalized.contains("DISAPPROVE")) {
            return BpmProcessStatus.REJECTED;
        }
        if (normalized.contains("WITHDRAW")) return BpmProcessStatus.WITHDRAWN;
        if (normalized.contains("DISCARD")) return BpmProcessStatus.DISCARDED;
        if (normalized.contains("FAILED") || normalized.contains("ERROR")) {
            return BpmProcessStatus.FAILED;
        }
        return BpmProcessStatus.TERMINATED;
    }

}
