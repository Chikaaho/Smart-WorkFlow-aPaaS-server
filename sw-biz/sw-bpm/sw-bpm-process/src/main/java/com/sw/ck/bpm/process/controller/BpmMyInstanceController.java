package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.dto.MyInstanceItemDTO;
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
    private final com.sw.ck.system.api.user.UserQueryFacade userQueryFacade;

    public BpmMyInstanceController(BpmInstanceService bpmInstanceService,
                                   BpmProcessDefService bpmProcessDefService,
                                   BpmTaskFacade bpmTaskFacade,
                                   TaskActionService taskActionService) {
        this(bpmInstanceService, bpmProcessDefService, bpmTaskFacade, taskActionService, null, null, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BpmMyInstanceController(BpmInstanceService bpmInstanceService,
                                   BpmProcessDefService bpmProcessDefService,
                                   BpmTaskFacade bpmTaskFacade,
                                   TaskActionService taskActionService,
                                   com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService,
                                   com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                   com.sw.ck.bpm.process.service.ParticipantNameService participantNameService,
                                   com.sw.ck.system.api.user.UserQueryFacade userQueryFacade) {
        this.bpmInstanceService = bpmInstanceService;
        this.bpmProcessDefService = bpmProcessDefService;
        this.bpmTaskFacade = bpmTaskFacade;
        this.taskActionService = taskActionService;
        this.approvalActionService = approvalActionService;
        this.objectMapper = objectMapper;
        this.participantNameService = participantNameService;
        this.userQueryFacade = userQueryFacade;
    }

    /**
     * 我发起的流程实例（分页，可选状态/关键字过滤）。
     *
     * @param keyword 匹配 businessKey 或 formKey（可选）
     */
    @GetMapping
    public R<PageResult<MyInstanceItemDTO>> myInstances(PageParam pageParam,
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
        java.util.List<MyInstanceItemDTO> items = enrichInstanceItems(records);
        PageResult<MyInstanceItemDTO> enrichedPage = new PageResult<>();
        enrichedPage.setRecords(items);
        enrichedPage.setTotal(total);
        enrichedPage.setPageNum(pageParam.getPageNum());
        enrichedPage.setPageSize(pageParam.getPageSize());
        return R.ok(enrichedPage);
    }

    /**
     * 提交后实例探测（用户态）：按表单键+业务键统计「当前用户为发起人」的实例数。
     *
     * <p>表单提交后的确认提示需要判断实例是否已真实创建；该判定不得依赖工作流监控
     * 权限（{@code workflow:monitor:view}），否则普通用户会得到假阴性。本端点沿用
     * 「我发起的」的强制发起人口径：只统计本人实例，他人实例恒为 0（不泄露存在性）。</p>
     */
    @GetMapping("/count")
    public R<Map<String, Object>> myInstanceCount(@RequestParam String formKey,
                                                  @RequestParam String businessKey) {
        LoginUser loginUser = LoginUserHolder.get();
        Long userId = loginUser == null ? null : loginUser.getUserId();
        long count = bpmInstanceService.countByFormKeyAndBusinessKeyAndInitiator(formKey, businessKey, userId);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("count", count);
        result.put("formKey", formKey);
        result.put("businessKey", businessKey);
        return R.ok(result);
    }

    /**
     * 实例条目富化（V012-BUG-022）：流程名称（按定义键去重解析）与发起人展示名
     * （批量解析）；解析失败降级 null，不阻断列表。
     */
    java.util.List<MyInstanceItemDTO> enrichInstanceItems(java.util.List<BpmInstance> records) {
        java.util.Map<String, String> defNames = new java.util.HashMap<>();
        java.util.Set<Long> initiatorIds = new java.util.HashSet<>();
        for (BpmInstance record : records) {
            if (record.getProcessDefKey() != null) {
                defNames.computeIfAbsent(record.getProcessDefKey(), key -> {
                    BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(key);
                    return processDef == null ? null : processDef.getName();
                });
            }
            if (record.getInitiatorId() != null) {
                initiatorIds.add(record.getInitiatorId());
            }
        }
        java.util.Map<Long, String> initiatorNames = userQueryFacade == null || initiatorIds.isEmpty()
                ? java.util.Map.of()
                : userQueryFacade.getUserDisplayNames(initiatorIds).orElse(java.util.Map.of());
        return records.stream().map(record -> {
            MyInstanceItemDTO item = new MyInstanceItemDTO();
            item.setId(record.getId());
            item.setProcessInstanceId(record.getProcessInstanceId());
            item.setProcessDefKey(record.getProcessDefKey());
            item.setProcessName(record.getProcessDefKey() == null ? null
                    : defNames.get(record.getProcessDefKey()));
            item.setTheme(record.getTheme());
            item.setBusinessKey(record.getBusinessKey());
            item.setFormKey(record.getFormKey());
            item.setInitiatorId(record.getInitiatorId());
            item.setInitiatorName(record.getInitiatorId() == null ? null
                    : initiatorNames.get(record.getInitiatorId()));
            item.setStatus(record.getStatus());
            item.setCreateTime(record.getCreateTime());
            return item;
        }).collect(Collectors.toList());
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
        // empty = 实例标识缺失：无活动任务（响应字段保持原列表形状，不漂移为 Optional）
        List<BpmTaskDTO> activeTasks = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of());
        List<Map<String, Object>> progress = activeTasks.stream()
                .<Map<String, Object>>map(task -> Map.of(
                        "taskId", task.getTaskId(),
                        "taskName", task.getName() == null ? "" : task.getName(),
                        "nodeKey", task.getTaskDefinitionKey() == null ? "" : task.getTaskDefinitionKey(),
                        "assignee", task.getAssignee() == null ? "" : task.getAssignee()))
                .collect(Collectors.toList());

        List<ApprovalHistoryItemDTO> history = buildApprovalHistory(instance);

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

    /** 按表单键+业务键取最新实例（关系查询入口；独立成方法便于事务外单元测试与复用）。 */
    BpmInstance findLatestInstance(String formKey, String businessKey) {
        return bpmInstanceService.lambdaQuery()
                .eq(BpmInstance::getFormKey, formKey)
                .eq(BpmInstance::getBusinessKey, businessKey)
                .orderByDesc(BpmInstance::getCreateTime)
                .last("LIMIT 1")
                .one();
    }

    /**
     * 实例流转记录装配（我发起的详情与统一详情关系端点共用同一口径，避免两处漂移）：
     * 引擎历史 + 审批动作（意见/结果）合并，参与人快照/任务级覆盖与冻结名富化。
     */
    List<ApprovalHistoryItemDTO> buildApprovalHistory(BpmInstance instance) {
        // 流转记录：引擎历史 + 审批动作（意见/结果）合并。
        // A1/C1 修复：已办理节点必须回显真实动作/结果/意见（否则前端对 null 结果
        // 一律显示"进行中"，流程已结束的终审节点仍显示进行中）；无动作记录的
        // 节点（如取消成员）不伪造动作。
        // empty = 实例标识缺失：无历史（响应字段保持原列表形状，不漂移为 Optional）
        List<BpmTaskDTO> historyTasks =
                bpmTaskFacade.queryHistoryByProcessInstance(instance.getProcessInstanceId())
                        .orElse(List.of());
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
        return history;
    }

    /**
     * 0.1.7 #30：统一详情权限关系——按 formKey+businessKey 查询当前用户与实例的关系。
     * <p>
     * APPROVER=存在指派给当前用户的活动任务（附 pendingTaskId）；PARTICIPANT=发起人或
     * 历史任务经办人；NONE=与本实例无关系。NONE 时不回传实例任何信息（fail-closed，
     * 不向无关系者泄露实例存在性）。数据可见性仍由 form:data:query 行权限独立把关。
     * </p>
     */
    @GetMapping("/relations")
    public R<Map<String, Object>> instanceRelation(@RequestParam String formKey,
                                                   @RequestParam String businessKey) {
        LoginUser loginUser = LoginUserHolder.get();
        Long userId = loginUser.getUserId();
        Map<String, Object> result = new java.util.HashMap<>();

        BpmInstance instance = findLatestInstance(formKey, businessKey);
        if (instance == null) {
            result.put("relation", "NONE");
            return R.ok(result);
        }

        // empty = 实例标识缺失：按无活动任务/无历史处理，不漂移为 Optional
        List<BpmTaskDTO> activeTasks = bpmTaskFacade
                .queryByProcessInstance(instance.getProcessInstanceId())
                .orElse(List.of());
        String pendingTaskId = activeTasks.stream()
                .filter(task -> userId != null && userId.toString().equals(task.getAssignee()))
                .map(BpmTaskDTO::getTaskId)
                .findFirst()
                .orElse(null);

        boolean initiator = userId != null && userId.equals(instance.getInitiatorId());
        boolean historyParticipant = pendingTaskId != null || initiator;
        if (!historyParticipant) {
            List<BpmTaskDTO> historyTasks = bpmTaskFacade
                    .queryHistoryByProcessInstance(instance.getProcessInstanceId())
                    .orElse(List.of());
            historyParticipant = historyTasks.stream()
                    .anyMatch(task -> userId != null && userId.toString().equals(task.getAssignee()));
        }

        String relation = pendingTaskId != null ? "APPROVER"
                : historyParticipant ? "PARTICIPANT" : "NONE";
        result.put("relation", relation);
        if ("NONE".equals(relation)) {
            return R.ok(result);
        }

        String processName = null;
        if (instance.getProcessDefKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(instance.getProcessDefKey());
            processName = processDef == null ? null : processDef.getName();
        }
        String initiatorName = null;
        if (userQueryFacade != null && instance.getInitiatorId() != null) {
            initiatorName = userQueryFacade
                    .getUserDisplayNames(java.util.Set.of(instance.getInitiatorId()))
                    .orElse(Map.of())
                    .get(instance.getInitiatorId());
        }

        Map<String, Object> instanceInfo = new java.util.HashMap<>();
        instanceInfo.put("id", instance.getId());
        instanceInfo.put("processInstanceId", instance.getProcessInstanceId());
        instanceInfo.put("processDefKey", instance.getProcessDefKey());
        instanceInfo.put("processName", processName);
        instanceInfo.put("theme", instance.getTheme());
        instanceInfo.put("status", instance.getStatus());
        instanceInfo.put("initiatorId", instance.getInitiatorId());
        instanceInfo.put("initiatorName", initiatorName);
        instanceInfo.put("createTime", instance.getCreateTime());
        instanceInfo.put("currentNodes", activeTasks.stream()
                .map(task -> task.getName() == null ? "" : task.getName())
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toList()));
        instanceInfo.put("currentNodeKeys", activeTasks.stream()
                .map(BpmTaskDTO::getTaskDefinitionKey)
                .filter(key -> key != null && !key.isEmpty())
                .collect(Collectors.toList()));
        result.put("pendingTaskId", pendingTaskId);
        result.put("instance", instanceInfo);
        // 有流程的表单详情：流程信息（外挂组件）与我的详情同装配口径
        result.put("approvalHistory", buildApprovalHistory(instance));
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
