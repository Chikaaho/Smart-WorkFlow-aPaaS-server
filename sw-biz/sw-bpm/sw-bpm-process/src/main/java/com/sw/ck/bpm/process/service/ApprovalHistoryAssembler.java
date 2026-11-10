package com.sw.ck.bpm.process.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.process.controller.BpmMyInstanceController;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 实例流转记录装配（1.0.0 F2 服务端 PDF 与「我发起的」详情共用同一口径）。
 * <p>
 * 自 {@link BpmMyInstanceController} 原地迁出（move-not-copy）：引擎历史 + 审批动作
 * （意见/结果）合并，参与人快照/任务级覆盖与冻结名富化；意见字段与部门经
 * {@link ApprovalHistoryEnricher} 统一解析。
 * </p>
 */
@Service
public class ApprovalHistoryAssembler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalHistoryAssembler.class);

    private final BpmTaskFacade bpmTaskFacade;
    private final TaskActionService taskActionService;
    private final ApprovalActionService approvalActionService;
    private final ObjectMapper objectMapper;
    private final ParticipantNameService participantNameService;
    private final com.sw.ck.system.api.user.UserQueryFacade userQueryFacade;
    private final com.sw.ck.system.api.dept.DeptQueryFacade deptQueryFacade;

    public ApprovalHistoryAssembler(BpmTaskFacade bpmTaskFacade,
                                    TaskActionService taskActionService,
                                    ApprovalActionService approvalActionService,
                                    ObjectMapper objectMapper,
                                    ParticipantNameService participantNameService,
                                    com.sw.ck.system.api.user.UserQueryFacade userQueryFacade,
                                    com.sw.ck.system.api.dept.DeptQueryFacade deptQueryFacade) {
        this.bpmTaskFacade = bpmTaskFacade;
        this.taskActionService = taskActionService;
        this.approvalActionService = approvalActionService;
        this.objectMapper = objectMapper;
        this.participantNameService = participantNameService;
        this.userQueryFacade = userQueryFacade;
        this.deptQueryFacade = deptQueryFacade;
    }

    /** 与原控制器同一口径的流转记录装配。 */
    public List<ApprovalHistoryItemDTO> build(com.sw.ck.bpm.process.entity.BpmInstance instance) {
        // 流转记录：引擎历史 + 审批动作（意见/结果）合并。
        // A1/C1 修复：已办理节点必须回显真实动作/结果/意见（否则前端对 null 结果
        // 一律显示"进行中"，流程已结束的终审节点仍显示进行中）；无动作记录的
        // 节点（如取消成员）不伪造动作。
        // empty = 实例标识缺失：无历史（响应字段保持原列表形状，不漂移为 Optional）
        List<BpmTaskDTO> historyTasks =
                bpmTaskFacade.queryHistoryByProcessInstance(instance.getProcessInstanceId())
                        .orElse(List.of());
        Map<String, ApprovalActionRecord> actionByTask =
                approvalActionService == null ? Map.of()
                        : approvalActionService
                                .findByProcessInstanceId(instance.getProcessInstanceId())
                                .stream()
                                .filter(record -> record.getTaskId() != null)
                                .collect(Collectors.toMap(
                                        ApprovalActionRecord::getTaskId,
                                        record -> record,
                                        (first, second) -> first));
        List<ApprovalHistoryItemDTO> history = new ArrayList<>();
        for (BpmTaskDTO h : historyTasks) {
            ApprovalHistoryItemDTO item = new ApprovalHistoryItemDTO();
            item.setTaskId(h.getTaskId());
            item.setTaskName(h.getName());
            item.setNodeKey(h.getTaskDefinitionKey());
            item.setAssignee(h.getAssignee());
            if (h.getCreateTime() != null) {
                item.setCreateTime(LocalDateTime.ofInstant(
                        h.getCreateTime().toInstant(), ZoneId.systemDefault()));
            }
            if (h.getEndTime() != null) {
                item.setEndTime(LocalDateTime.ofInstant(
                        h.getEndTime().toInstant(), ZoneId.systemDefault()));
            }
            ApprovalActionRecord action = actionByTask.get(h.getTaskId());
            if (action != null) {
                item.setAction(action.getAction());
                item.setActionRecordId(action.getId());
                item.setApprovalResult(action.getSettlementStatus());
                item.setOpinionData(parseOpinionData(action.getOpinionData()));
                item.setOpinionFormId(action.getOpinionFormId());
                item.setOpinionFormVersion(action.getOpinionFormVersion());
            }
            history.add(item);
        }
        if (participantNameService != null) {
            // 候选模式任务在引擎历史中可能被 approver 兜底误填为发起人：
            // 快照命中即覆盖为权威参与人（I1 G5b），再做冻结名富化。
            // 任务级快照优先：动态并行多分支共用同一 node_key，节点级覆盖会把多条
            // 分支改写为同一办理人（I4 G1a），taskId 命中才回退节点级。
            Map<String, Long> nodeAssignees =
                    participantNameService.resolveNodeAssignees(instance.getProcessInstanceId());
            Map<String, Long> taskAssignees =
                    participantNameService.resolveTaskAssignees(instance.getProcessInstanceId());
            for (ApprovalHistoryItemDTO item : history) {
                Long pid = item.getTaskId() != null ? taskAssignees.get(item.getTaskId()) : null;
                if (pid == null && item.getNodeKey() != null) {
                    pid = nodeAssignees.get(item.getNodeKey());
                }
                if (pid != null) {
                    item.setAssignee(String.valueOf(pid));
                }
            }
            // 冻结快照名优先：流转记录身份不随后续改名/停用重写（I1）
            Map<Long, String> names = participantNameService.resolveDisplayNames(
                    instance.getProcessInstanceId(),
                    history.stream()
                            .map(ApprovalHistoryItemDTO::getAssignee)
                            .filter(a -> a != null && a.matches("\\d+"))
                            .map(Long::valueOf)
                            .collect(Collectors.toSet()));
            for (ApprovalHistoryItemDTO item : history) {
                if (item.getAssignee() != null && item.getAssignee().matches("\\d+")) {
                    item.setAssigneeName(names.get(Long.valueOf(item.getAssignee())));
                }
            }
        } else if (taskActionService != null) {
            Map<Long, String> names = taskActionService.resolveUserNames(history.stream()
                    .map(ApprovalHistoryItemDTO::getAssignee)
                    .filter(a -> a != null && a.matches("\\d+"))
                    .map(Long::valueOf)
                    .collect(Collectors.toSet()));
            for (ApprovalHistoryItemDTO item : history) {
                if (item.getAssignee() != null && item.getAssignee().matches("\\d+")) {
                    item.setAssigneeName(names.get(Long.valueOf(item.getAssignee())));
                }
            }
        }
        ApprovalHistoryEnricher.enrichOpinionFields(history, actionByTask, objectMapper, taskActionService);
        ApprovalHistoryEnricher.enrichAssigneeDept(history, userQueryFacade, deptQueryFacade);
        return history;
    }

    private Map<String, Object> parseOpinionData(String opinionDataJson) {
        if (opinionDataJson == null || opinionDataJson.isBlank() || objectMapper == null) {
            return null;
        }
        try {
            return objectMapper.readValue(opinionDataJson, Map.class);
        } catch (Exception e) {
            log.warn("意见快照解析失败，按无意见展示: {}", e.getMessage());
            return null;
        }
    }
}
