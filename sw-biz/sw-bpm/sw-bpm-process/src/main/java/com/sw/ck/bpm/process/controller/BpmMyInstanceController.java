package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.TaskActionService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 我发起的控制器（个人入口，非流程监控）。
 * <p>
 * 强制以当前登录用户为发起人查询（initiator_id = 当前用户，租户经拦截器），
 * 不信任客户端传入的查询人身份；普通用户无需管理监控权限。
 * </p>
 */
@RestController
@RequestMapping("/workflow/my/instances")
public class BpmMyInstanceController {

    private static final Logger log = LoggerFactory.getLogger(BpmMyInstanceController.class);

    private final BpmInstanceService bpmInstanceService;
    private final BpmProcessDefService bpmProcessDefService;
    private final BpmTaskFacade bpmTaskFacade;
    private final TaskActionService taskActionService;
    private final com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.sw.ck.bpm.process.service.ParticipantNameService participantNameService;

    public BpmMyInstanceController(BpmInstanceService bpmInstanceService,
                                   BpmProcessDefService bpmProcessDefService,
                                   BpmTaskFacade bpmTaskFacade,
                                   TaskActionService taskActionService) {
        this(bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BpmMyInstanceController(BpmInstanceService bpmInstanceService,
                                   BpmProcessDefService bpmProcessDefService,
                                   BpmTaskFacade bpmTaskFacade,
                                   TaskActionService taskActionService,
                                   com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                   com.sw.ck.bpm.process.service.ParticipantNameService participantNameService) {
        this.bpmInstanceService = bpmInstanceService;
        this.bpmProcessDefService = bpmProcessDefService;
        this.bpmTaskFacade = bpmTaskFacade;
        this.taskActionService = taskActionService;
        this.approvalActionService = approvalActionService;
        this.objectMapper = objectMapper;
        this.participantNameService = participantNameService;
    }

    /**
     * 我发起的流程实例（分页，可选状态/关键字过滤）。
     *
     * @param keyword 匹配 businessKey 或 formKey（可选）
     */
    @GetMapping
    public R<PageResult<BpmInstance>> myInstances(PageParam pageParam,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String keyword) {
        LoginUser loginUser = LoginUserHolder.get();
        var query = bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getInitiatorId, loginUser.getUserId())
                .eq(status != null && !status.isBlank(), BpmInstance::getStatus, status);
        if (keyword != null && !keyword.isBlank()) {
            query.and(wrapper -> wrapper
                    .like(BpmInstance::getBusinessKey, keyword)
                    .or().like(BpmInstance::getFormKey, keyword)
                    .or().like(BpmInstance::getProcessDefKey, keyword));
        }
        long total = query.count();
        List<BpmInstance> records = query
                .orderByDesc(BpmInstance::getCreateTime)
                .last("LIMIT " + pageParam.getPageSize()
                        + " OFFSET " + (pageParam.getPageNum() - 1) * pageParam.getPageSize())
                .list();
        PageResult<BpmInstance> page = new PageResult<>();
        page.setRecords(records);
        page.setTotal(total);
        page.setPageNum(pageParam.getPageNum());
        page.setPageSize(pageParam.getPageSize());
        log.debug("我发起的查询: userId={}, total={}", loginUser.getUserId(), total);
        return R.ok(page);
    }

    /**
     * 我发起的实例详情：实例状态 + 当前进度（活动任务）+ 流转记录。
     */
    @GetMapping("/{id}")
    public R<Map<String, Object>> myInstanceDetail(@PathVariable Long id) {
        LoginUser loginUser = LoginUserHolder.get();
        BpmInstance instance = bpmInstanceService.getById(id);
        if (instance == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程实例不存在");
        }
        if (!loginUser.getUserId().equals(instance.getInitiatorId())) {
            log.warn("我发起的详情越权拒绝: instanceId={}, initiator={}, currentUser={}",
                    id, instance.getInitiatorId(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权查看该流程实例");
        }

        String processName = null;
        if (instance.getProcessDefKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
            processName = processDef == null ? null : processDef.getName();
        }

        // 当前进度：活动任务
        List<BpmTaskDTO> activeTasks = bpmTaskFacade.queryByProcessInstance(instance.getProcessInstanceId());
        List<Map<String, Object>> progress = activeTasks.stream()
                .<Map<String, Object>>map(task -> Map.of(
                        "taskId", task.getTaskId(),
                        "taskName", task.getName() == null ? "" : task.getName(),
                        "nodeKey", task.getTaskDefinitionKey() == null ? "" : task.getTaskDefinitionKey(),
                        "assignee", task.getAssignee() == null ? "" : task.getAssignee()))
                .collect(Collectors.toList());

        // 流转记录：引擎历史 + 审批动作（意见/结果）合并。
        // A1/C1 修复：已办理节点必须回显真实动作/结果/意见（否则前端对 null 结果
        // 一律显示"进行中"，流程已结束的终审节点仍显示进行中）；无动作记录的
        // 节点（如取消成员）不伪造动作。
        List<BpmTaskDTO> historyTasks =
                bpmTaskFacade.queryHistoryByProcessInstance(instance.getProcessInstanceId());
        Map<String, com.sw.ck.bpm.process.entity.ApprovalActionRecord> actionByTask =
                approvalActionService == null ? Map.of()
                        : approvalActionService
                                .findByProcessInstanceId(instance.getProcessInstanceId())
                                .stream()
                                .filter(record -> record.getTaskId() != null)
                                .collect(Collectors.toMap(
                                        com.sw.ck.bpm.process.entity.ApprovalActionRecord::getTaskId,
                                        record -> record,
                                        (first, second) -> first));
        List<ApprovalHistoryItemDTO> history = new java.util.ArrayList<>();
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
            com.sw.ck.bpm.process.entity.ApprovalActionRecord action = actionByTask.get(h.getTaskId());
            if (action != null) {
                item.setAction(action.getAction());
                item.setApprovalResult(action.getSettlementStatus());
                item.setOpinionData(parseOpinionData(action.getOpinionData()));
                item.setOpinionFormId(action.getOpinionFormId());
                item.setOpinionFormVersion(action.getOpinionFormVersion());
            }
            history.add(item);
        }
        if (participantNameService != null) {
            // 候选模式任务在引擎历史中可能被 approver 兜底误填为发起人：
            // 快照命中即覆盖为权威参与人（I1 G5b），再做冻结名富化
            Map<String, Long> nodeAssignees =
                    participantNameService.resolveNodeAssignees(instance.getProcessInstanceId());
            for (ApprovalHistoryItemDTO item : history) {
                if (item.getNodeKey() != null) {
                    Long pid = nodeAssignees.get(item.getNodeKey());
                    if (pid != null) {
                        item.setAssignee(String.valueOf(pid));
                    }
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

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("instance", instance);
        result.put("processName", processName);
        result.put("formKey", instance.getFormKey());
        result.put("businessKey", instance.getBusinessKey());
        result.put("status", instance.getStatus());
        result.put("progress", progress);
        result.put("history", history);
        return R.ok(result);
    }

    /** 意见快照 JSON → Map；空/坏数据返回 null（不伪造意见）。 */
    @SuppressWarnings("unchecked")
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
