package com.sw.ck.bpm.process.controller;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.dto.ApprovalHistoryItemDTO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.dto.ProcessedTaskRespDTO;
import com.sw.ck.bpm.process.dto.TaskDetailRespDTO;
import com.sw.ck.bpm.process.dto.TodoTaskRespDTO;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 待办中心控制器（查询入口 + 审批动作 HTTP 入口）。
 * <p>
 * 审批动作（同意/驳回/退回）执行核心已收编至 {@link TaskActionService}；
 * 本 Controller 的动作端点是同步 HTTP 通道，与异步命令消费者
 * （TaskActionCommandHandler）共享同一业务服务，语义一致。
 * </p>
 *
 * <h3>安全</h3>
 * <ul>
 *   <li>接口均需登录（默认走鉴权，无需加 permit 白名单）</li>
 *   <li>动作前置越权校验在 TaskActionService 内统一执行</li>
 *   <li>待办查询按 {@code taskTenantId + 当前用户} 双条件过滤，不依赖 ORM 拦截器</li>
 * </ul>
 */
@RestController
@RequestMapping("/workflow/tasks")
public class BpmTodoController {

    private static final Logger log = LoggerFactory.getLogger(BpmTodoController.class);

    private final BpmTaskFacade bpmTaskFacade;
    private final BpmInstanceService bpmInstanceService;
    private final BpmProcessDefService bpmProcessDefService;
    private final TaskActionService taskActionService;
    private final com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.sw.ck.bpm.process.service.ParticipantNameService participantNameService;

    public BpmTodoController(BpmTaskFacade bpmTaskFacade,
                             BpmInstanceService bpmInstanceService,
                             BpmProcessDefService bpmProcessDefService,
                             TaskActionService taskActionService,
                             com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this(bpmTaskFacade, bpmInstanceService, bpmProcessDefService, taskActionService,
                approvalActionService, objectMapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BpmTodoController(BpmTaskFacade bpmTaskFacade,
                             BpmInstanceService bpmInstanceService,
                             BpmProcessDefService bpmProcessDefService,
                             TaskActionService taskActionService,
                             com.sw.ck.bpm.process.service.ApprovalActionService approvalActionService,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             com.sw.ck.bpm.process.service.ParticipantNameService participantNameService) {
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.bpmProcessDefService = bpmProcessDefService;
        this.taskActionService = taskActionService;
        this.approvalActionService = approvalActionService;
        this.objectMapper = objectMapper;
        this.participantNameService = participantNameService;
    }

    /**
     * 当前用户待办列表（分页）。
     */
    @GetMapping("/todo")
    public R<PageResult<TodoTaskRespDTO>> todo(PageParam pageParam) {
        LoginUser loginUser = LoginUserHolder.get();
        String tenantId = String.valueOf(loginUser.getTenantId());
        String assignee = String.valueOf(loginUser.getUserId());

        long offset = (pageParam.getPageNum() - 1) * pageParam.getPageSize();
        int limit = (int) pageParam.getPageSize();

        List<BpmTaskDTO> tasks = bpmTaskFacade.queryTodoPage(tenantId, assignee, (int) offset, limit);
        long total = bpmTaskFacade.countTodo(tenantId, assignee);

        List<TodoTaskRespDTO> dtos = tasks.stream()
                .map(this::toTodoTaskDTO)
                .collect(Collectors.toList());

        log.debug("待办查询: tenantId={}, assignee={}, total={}, pageNum={}, pageSize={}",
                tenantId, assignee, total, pageParam.getPageNum(), pageParam.getPageSize());

        PageResult<TodoTaskRespDTO> pageResult = new PageResult<>();
        pageResult.setRecords(dtos);
        pageResult.setTotal(total);
        pageResult.setPageNum(pageParam.getPageNum());
        pageResult.setPageSize(pageParam.getPageSize());

        return R.ok(pageResult);
    }

    /**
     * 完成审批（同意）。执行核心委托 {@link TaskActionService}。
     */
    public R<Void> complete(String taskId) {
        return taskActionService.execute(taskId, null);
    }

    @Transactional
    @PostMapping("/{taskId}/complete")
    public R<Void> complete(@PathVariable String taskId,
                            @RequestBody(required = false) ApprovalActionRequest request) {
        if (request == null) {
            return taskActionService.execute(taskId, null);
        }
        request.setAction(ApprovalAction.APPROVE);
        return taskActionService.execute(taskId, request);
    }

    /**
     * 驳回审批。执行核心委托 {@link TaskActionService}。
     */
    public R<Void> reject(String taskId) {
        ApprovalActionRequest actionRequest = new ApprovalActionRequest();
        actionRequest.setAction(ApprovalAction.REJECT);
        return taskActionService.execute(taskId, actionRequest);
    }

    @Transactional
    @PostMapping("/{taskId}/reject")
    public R<Void> reject(@PathVariable String taskId,
                          @RequestBody(required = false) ApprovalActionRequest request) {
        ApprovalActionRequest actionRequest = request == null ? new ApprovalActionRequest() : request;
        actionRequest.setAction(ApprovalAction.REJECT);
        return taskActionService.execute(taskId, actionRequest);
    }

    @Transactional
    @PostMapping("/{taskId}/return")
    public R<Void> returnTask(@PathVariable String taskId,
                              @RequestBody ApprovalActionRequest request) {
        ApprovalActionRequest actionRequest = request == null ? new ApprovalActionRequest() : request;
        actionRequest.setAction(ApprovalAction.RETURN);
        return taskActionService.execute(taskId, actionRequest);
    }

    /**
     * 任务详情。
     */
    @GetMapping("/{taskId}")
    public R<TaskDetailRespDTO> detail(@PathVariable String taskId) {
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId);
        if (task == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }

        TaskDetailRespDTO dto = new TaskDetailRespDTO();
        dto.setTaskId(task.getTaskId());
        dto.setTaskName(task.getName());
        dto.setProcessInstanceId(task.getProcessInstanceId());
        dto.setProcessDefinitionKey(task.getProcessDefinitionKey());

        // processName 富化
        if (task.getProcessDefinitionKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(task.getProcessDefinitionKey());
            if (processDef != null) {
                dto.setProcessName(processDef.getName());
            }
        }

        // formKey 从流程变量获取
        String formKey = bpmTaskFacade.getVariable(task.getProcessInstanceId(), "formKey");
        dto.setFormKey(formKey);

        dto.setBusinessKey(task.getBusinessKey());
        dto.setAssignee(task.getAssignee());

        // 发起人
        bpmInstanceService.findByProcessInstanceId(task.getProcessInstanceId())
                .ifPresent(instance -> {
                    dto.setInitiatorId(instance.getInitiatorId());
                    dto.setInitiatorName(taskActionService.resolveUserNames(
                            instance.getInitiatorId() == null
                                    ? java.util.Set.of()
                                    : java.util.Set.of(instance.getInitiatorId()))
                            .get(instance.getInitiatorId()));
                });

        // 任务创建时间
        if (task.getCreateTime() != null) {
            dto.setCreateTime(LocalDateTime.ofInstant(
                    task.getCreateTime().toInstant(), ZoneId.systemDefault()));
        }

        // 流程变量
        Map<String, Object> variables = bpmTaskFacade.getVariables(task.getProcessInstanceId());
        dto.setProcessVariables(variables);
        dto.setOpinionForm(taskActionService.resolveOpinionForm(task));

        if (task.getAssignee() != null && task.getAssignee().matches("\\d+")) {
            dto.setAssigneeName(taskActionService.resolveUserNames(
                            java.util.Set.of(Long.valueOf(task.getAssignee())))
                    .get(Long.valueOf(task.getAssignee())));
        }

        log.debug("任务详情查询: taskId={}, processInstanceId={}", taskId, task.getProcessInstanceId());

        // 审批历史
        List<BpmTaskDTO> historyTasks = bpmTaskFacade.queryHistoryByProcessInstance(task.getProcessInstanceId());
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
            history.add(item);
        }
        if (approvalActionService != null) {
            Map<String, ApprovalActionRecord> actions = approvalActionService
                    .findByProcessInstanceId(task.getProcessInstanceId()).stream()
                    .collect(Collectors.toMap(ApprovalActionRecord::getTaskId,
                            java.util.function.Function.identity(), (left, right) -> left));
            for (ApprovalHistoryItemDTO item : history) {
                ApprovalActionRecord action = actions.get(item.getTaskId());
                if (action == null) continue;
                item.setAction(action.getAction());
                item.setApprovalResult("APPROVE".equals(action.getAction()) ? "APPROVED"
                        : "REJECT".equals(action.getAction()) ? "REJECTED" : null);
                item.setOpinionFormId(action.getOpinionFormId());
                item.setOpinionFormVersion(action.getOpinionFormVersion());
                if (action.getOpinionData() != null && objectMapper != null) {
                    try {
                        item.setOpinionData(objectMapper.readValue(action.getOpinionData(), Map.class));
                    } catch (Exception ignored) {
                        item.setOpinionData(Map.of());
                    }
                }
            }
        }
        // 审批人展示名富化（快照冻结名优先，历史身份不随后续改名重写；查询失败不阻断详情）
        Map<Long, String> historyNames = participantNameService != null
                ? participantNameService.resolveDisplayNames(task.getProcessInstanceId(), historyTasks.stream()
                .map(BpmTaskDTO::getAssignee)
                .filter(a -> a != null && a.matches("\\d+"))
                .map(Long::valueOf)
                .collect(Collectors.toSet()))
                : taskActionService.resolveUserNames(historyTasks.stream()
                .map(BpmTaskDTO::getAssignee)
                .filter(a -> a != null && a.matches("\\d+"))
                .map(Long::valueOf)
                .collect(Collectors.toSet()));
        for (ApprovalHistoryItemDTO item : history) {
            if (item.getAssignee() != null && item.getAssignee().matches("\\d+")) {
                item.setAssigneeName(historyNames.get(Long.valueOf(item.getAssignee())));
            }
        }
        dto.setApprovalHistory(history);

        return R.ok(dto);
    }

    /**
     * 当前用户已办列表（分页）。
     */
    @GetMapping("/processed")
    public R<PageResult<ProcessedTaskRespDTO>> processed(PageParam pageParam) {
        LoginUser loginUser = LoginUserHolder.get();
        String tenantId = String.valueOf(loginUser.getTenantId());
        String assignee = String.valueOf(loginUser.getUserId());

        long offset = (pageParam.getPageNum() - 1) * pageParam.getPageSize();
        int limit = (int) pageParam.getPageSize();

        List<BpmTaskDTO> tasks = bpmTaskFacade.queryProcessedPage(tenantId, assignee, (int) offset, limit);
        long total = bpmTaskFacade.countProcessed(tenantId, assignee);

        List<ProcessedTaskRespDTO> dtos = tasks.stream()
                .map(this::toProcessedTaskDTO)
                .collect(Collectors.toList());

        log.debug("已办查询: tenantId={}, assignee={}, total={}, pageNum={}, pageSize={}",
                tenantId, assignee, total, pageParam.getPageNum(), pageParam.getPageSize());

        PageResult<ProcessedTaskRespDTO> pageResult = new PageResult<>();
        pageResult.setRecords(dtos);
        pageResult.setTotal(total);
        pageResult.setPageNum(pageParam.getPageNum());
        pageResult.setPageSize(pageParam.getPageSize());

        return R.ok(pageResult);
    }

    private TodoTaskRespDTO toTodoTaskDTO(BpmTaskDTO task) {
        TodoTaskRespDTO dto = new TodoTaskRespDTO();
        dto.setTaskId(task.getTaskId());
        dto.setProcessInstanceId(task.getProcessInstanceId());

        if (task.getCreateTime() != null) {
            dto.setCreateTime(LocalDateTime.ofInstant(
                    task.getCreateTime().toInstant(), ZoneId.systemDefault()));
        }

        dto.setBusinessKey(task.getBusinessKey());

        String formKey = bpmTaskFacade.getVariable(
                task.getProcessInstanceId(), "formKey");
        dto.setFormKey(formKey);

        if (task.getProcessDefinitionKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(task.getProcessDefinitionKey());
            if (processDef != null) {
                dto.setProcessName(processDef.getName());
            }
        }

        return dto;
    }

    private ProcessedTaskRespDTO toProcessedTaskDTO(BpmTaskDTO task) {
        ProcessedTaskRespDTO dto = new ProcessedTaskRespDTO();
        dto.setTaskId(task.getTaskId());
        dto.setTaskName(task.getName());
        dto.setProcessInstanceId(task.getProcessInstanceId());

        if (task.getCreateTime() != null) {
            dto.setCreateTime(LocalDateTime.ofInstant(
                    task.getCreateTime().toInstant(), ZoneId.systemDefault()));
        }
        if (task.getEndTime() != null) {
            dto.setEndTime(LocalDateTime.ofInstant(
                    task.getEndTime().toInstant(), ZoneId.systemDefault()));
        }

        String formKey = bpmTaskFacade.getVariable(
                task.getProcessInstanceId(), "formKey");
        dto.setFormKey(formKey);
        dto.setBusinessKey(bpmTaskFacade.getBusinessKey(task.getProcessInstanceId()));

        if (task.getProcessDefinitionKey() != null) {
            BpmProcessDef processDef = bpmProcessDefService.findByProcessKey(task.getProcessDefinitionKey());
            if (processDef != null) {
                dto.setProcessName(processDef.getName());
            }
        }

        return dto;
    }
}
