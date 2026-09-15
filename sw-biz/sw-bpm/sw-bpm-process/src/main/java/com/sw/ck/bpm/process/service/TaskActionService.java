package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.event.BpmDeviceCommandEvent;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.dto.GraphElement;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.bpm.process.dto.ApprovalAction;
import com.sw.ck.bpm.process.dto.ApprovalActionRequest;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.validator.ApprovalOpinionValidator;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审批动作统一业务服务（同意/驳回/退回的唯一执行核心）。
 * <p>
 * 从 {@code BpmTodoController.handleAction} 收编：同步 HTTP 入口、命令消费者
 * （TaskActionCommandHandler）与未来内部调用统一汇入本服务，保证两通道共享
 * 业务校验、越权校验、审计与幂等语义，不存在第二套审批执行路径。
 * </p>
 */
@Service
public class TaskActionService {

    private static final Logger log = LoggerFactory.getLogger(TaskActionService.class);

    private final BpmTaskFacade bpmTaskFacade;
    private final BpmInstanceService bpmInstanceService;
    private final BpmProcessDefService bpmProcessDefService;
    private final DomainEventPublisher domainEventPublisher;
    private final UserQueryFacade userQueryFacade;
    private final ApprovalActionService approvalActionService;
    private final ParticipantSnapshotRecorder participantSnapshotRecorder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    /** 可选生命周期服务（循环依赖经 ObjectProvider 解耦；未装配时跳过生命周期门）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<com.sw.ck.bpm.process.service.ApprovalLifecycleService> lifecycleProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sw.ck.bpm.process.service.NodeFunctionService nodeFunctionService;

    private com.sw.ck.bpm.process.service.ApprovalLifecycleService lifecycle() {
        return lifecycleProvider == null ? null : lifecycleProvider.getIfAvailable();
    }

    public TaskActionService(BpmTaskFacade bpmTaskFacade,
                             BpmInstanceService bpmInstanceService,
                             BpmProcessDefService bpmProcessDefService,
                             DomainEventPublisher domainEventPublisher,
                             UserQueryFacade userQueryFacade,
                             ApprovalActionService approvalActionService,
                             com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                             ParticipantSnapshotRecorder participantSnapshotRecorder) {
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.bpmProcessDefService = bpmProcessDefService;
        this.domainEventPublisher = domainEventPublisher;
        this.userQueryFacade = userQueryFacade;
        this.approvalActionService = approvalActionService;
        this.objectMapper = objectMapper;
        this.participantSnapshotRecorder = participantSnapshotRecorder;
    }

    /**
     * 执行审批动作（原 handleAction 语义，行为保持不变）。
     *
     * @param taskId  Flowable task ID
     * @param request 动作请求（action 已由入口归一化）
     */
    @Transactional
    public com.sw.ck.common.response.R<Void> execute(String taskId, ApprovalActionRequest request) {
        return execute(taskId, request, null);
    }

    /**
     * 执行审批动作（命令通道入口携带 commandId）。
     * <p>
     * 幂等恢复语义（提示05 §4 断言3）：目标任务已消失但存在动作记录时——
     * 记录属于同一受理命令（command_id 匹配）= 同命令确认丢失后的重投，
     * 定位自身已提交结果并恢复一致的可回查命令结果，不产生第二次审批/通知副作用；
     * 记录属于其他命令/意图 = 已被他人处理的确定性冲突，拒绝。
     * </p>
     *
     * @param commandId 受理命令标识；同步 HTTP 入口传 null（无命令语义）
     */
    @Transactional
    public com.sw.ck.common.response.R<Void> execute(String taskId, ApprovalActionRequest request,
                                                     Long commandId) {
        LoginUser loginUser = LoginUserHolder.get();

        // 1. 查询 task（经 Facade 包装，无 Flowable 泄漏）
        BpmTaskDTO task = bpmTaskFacade.getTask(taskId);
        if (task == null) {
            ApprovalActionRecord handled = approvalActionService == null
                    ? null : approvalActionService.findByTaskId(taskId);
            if (handled != null) {
                if (commandId != null && commandId.equals(handled.getCommandId())) {
                    log.info("同命令重放恢复自身已提交结果: commandId={}, taskId={}, "
                                    + "actionRecordId={}, actor={}",
                            commandId, taskId, handled.getId(), handled.getActorId());
                    return com.sw.ck.common.response.R.ok();
                }
                log.warn("已被处理冲突（不同命令/意图）: taskId={}, 已办命令={}, 当前命令={}",
                        taskId, handled.getCommandId(), commandId);
                throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.APPROVAL_ALREADY_HANDLED);
            }
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "任务不存在");
        }

        // 2. 越权校验：审批人
        boolean assigned = String.valueOf(loginUser.getUserId()).equals(task.getAssignee());
        if (!assigned && !bpmTaskFacade.canHandle(taskId, String.valueOf(loginUser.getUserId()))) {
            log.warn("越权拒绝（审批人不匹配）: taskId={}, taskAssignee={}, currentUserId={}",
                    taskId, task.getAssignee(), loginUser.getUserId());
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "无权处理该任务");
        }

        String processInstanceId = task.getProcessInstanceId();

        BpmInstance instance = bpmInstanceService.findByProcessInstanceId(processInstanceId).orElse(null);
        if (instance != null && InstanceStatusEnum.FAILED.getCode().equals(instance.getStatus())) {
            log.warn("失败实例拒绝继续审批: processInstanceId={}, taskId={}, userId={}",
                    processInstanceId, taskId, loginUser.getUserId());
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.INSTANCE_FAILED);
        }

        // 2.5 流程结束前读取设备透传变量（实例结束后 Runtime 变量不可查）
        String productId = asString(bpmTaskFacade.getVariable(processInstanceId, "productId"));
        String deviceName = asString(bpmTaskFacade.getVariable(processInstanceId, "deviceName"));
        String commandKey = asString(bpmTaskFacade.getVariable(processInstanceId, "commandKey"));
        String commandType = asString(bpmTaskFacade.getVariable(processInstanceId, "commandType"));

        boolean legacyInvocation = request == null;
        ApprovalActionRequest effectiveRequest = legacyInvocation ? new ApprovalActionRequest() : request;
        ApprovalAction action = effectiveRequest.getAction() == null ? ApprovalAction.APPROVE
                : effectiveRequest.getAction();
        effectiveRequest.setAction(action);
        Map<String, Object> processVariables = bpmTaskFacade.getVariables(processInstanceId);
        // I3 动作资格：任务存在待处理加签时，普通办理（APPROVE/DISAPPROVE/REJECT/
        // RETURN）必须先等加签完成或取消（生命周期门）。
        com.sw.ck.bpm.process.service.ApprovalLifecycleService lifecycleService = lifecycle();
        if (lifecycleService != null && !legacyInvocation) {
            lifecycleService.assertNoPendingSign(taskId);
        }
        ApprovalOpinionValidator.validate(effectiveRequest, resolveOpinionForm(task), processVariables);
        if (action == ApprovalAction.RETURN) {
            if (effectiveRequest.getReturnTargetNodeId() == null
                    || effectiveRequest.getReturnTargetNodeId().isBlank()) {
                throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.APPROVAL_RETURN_TARGET_INVALID);
            }
            bpmTaskFacade.returnTask(taskId, effectiveRequest.getReturnTargetNodeId());
            if (participantSnapshotRecorder != null) {
                participantSnapshotRecorder.settle(processInstanceId, task.getTaskDefinitionKey(),
                        task.getTaskId(), String.valueOf(loginUser.getUserId()), action.name(),
                        loginUser.getTenantId());
            }
            recordAction(task, loginUser, effectiveRequest, action, "RETURNED", processVariables,
                    commandId, nextReturnRound(processInstanceId));
            publishProcessEvent(processInstanceId, loginUser, BpmNotifyTrigger.PROCESS_RETURNED);
            // 方向 §6-96 退回：新办理轮次重建任务后必须可生成新通知（同轮次唯一身份），
            // 不被过宽唯一键误杀；受众=重建任务的 assignee/candidates（缺 task 或非数字仅 warn 不阻断）
            publishReturnedRoundTodoCreated(processInstanceId, loginUser);
            return com.sw.ck.common.response.R.ok();
        }

        Map<String, Object> variables = legacyInvocation ? null : new java.util.HashMap<>();
        if (variables != null) {
            variables.put("outcome", switch (action) {
                case REJECT -> "REJECTED";
                case DISAPPROVE -> "DISAPPROVED";
                default -> "APPROVED";
            });
            // 引擎在本次事务内创建下一个人工任务时，用该稳定变量把通知事件
            // 的操作人绑定到实际推进流程的审批人，而不是回退到发起人。
            variables.put("lastApprovalActorId", loginUser.getUserId());
        }
        try {
            if (assigned) bpmTaskFacade.complete(taskId, variables);
            else bpmTaskFacade.completeAsUser(taskId, String.valueOf(loginUser.getUserId()),
                    variables == null ? Map.of() : variables);
        } catch (RuntimeException e) {
            BaseException branchFailure = findBaseException(e,
                    com.sw.ck.bpm.api.exception.BpmErrorCode.BRANCH_EVALUATION_FAILED.getCode());
            if (branchFailure != null) {
                bpmInstanceService.updateStatus(processInstanceId, InstanceStatusEnum.FAILED.getCode());
                log.warn("分支条件求值失败，实例进入 FAILED: processInstanceId={}, taskId={}",
                        processInstanceId, taskId);
                return com.sw.ck.common.response.R.fail(branchFailure.getCode(), branchFailure.getMessage());
            }
            if (isConcurrentTaskCollision(e)) {
                // 并发同任务重复办理：另一请求已合法完成，本请求零副作用。
                // PostgreSQL 唯一键冲突会中止整个事务，必须翻译为既有业务状态，不得放大为 500。
                log.info("并发任务冲突，按已处理拒绝: taskId={}, processInstanceId={}",
                        taskId, processInstanceId);
                throw new BaseException(
                        com.sw.ck.bpm.api.exception.BpmErrorCode.APPROVAL_ALREADY_HANDLED.getCode(),
                        "任务不存在或已被处理");
            }
            throw e;
        }
        // REJECT 是流程级驳回终态（I3 §4.4）；不通过意见必须走 DISAPPROVE，
        // 由节点 disapprovePolicy 结算。旧「会签 REJECT 交给结算条件」的路径
        // 关闭：会签参与人的负向意见统一使用 DISAPPROVE。
        if (action == ApprovalAction.REJECT) {
            bpmTaskFacade.terminateProcess(processInstanceId, "REJECTED");
        }
        if (participantSnapshotRecorder != null) {
            participantSnapshotRecorder.settle(processInstanceId, task.getTaskDefinitionKey(),
                    task.getTaskId(), String.valueOf(loginUser.getUserId()), action.name(),
                    loginUser.getTenantId());
        }
        if (action == ApprovalAction.DISAPPROVE
                && !isConsensusTask(task)) {
            // 普通节点不通过意见结算策略：TERMINATE（默认）= 结束流程；
            // CONTINUE = 不通过意见不阻断流程（明确设计选择）。
            String policy = nodeDisapprovePolicy(task);
            if (legacyInvocation || "TERMINATE".equalsIgnoreCase(policy)
                    || policy == null || policy.isBlank()) {
                bpmTaskFacade.terminateProcess(processInstanceId, "DISAPPROVED");
            }
        }
        recordAction(task, loginUser, effectiveRequest, action,
                switch (action) {
                    case REJECT -> "REJECTED";
                    case DISAPPROVE -> "DISAPPROVED";
                    default -> "APPROVED";
                }, processVariables, commandId, null);
        runHandleResultFunction(task, loginUser, processVariables, action);
        log.info("审批已完成: taskId={}, processInstanceId={}, userId={}",
                taskId, processInstanceId, loginUser.getUserId());

        boolean processGone = !bpmTaskFacade.isProcessActive(processInstanceId)
                || action == ApprovalAction.REJECT
                || ((!legacyInvocation && action == ApprovalAction.DISAPPROVE)
                    && !isConsensusTask(task)
                         && (nodeDisapprovePolicy(task) == null
                         || "TERMINATE".equalsIgnoreCase(nodeDisapprovePolicy(task))));

        if (processGone) {
            // 节点结算（会签负向结算等）可能已在本次 complete 的事务内写终态并发通知。
            // 实例已非 RUNNING 时，本动作以投票人自身 action 推导终态会覆盖真实结算结果
            //（RATIO/ANY 负向在最后一票为 APPROVE 时被覆盖为 APPROVED），必须跳过改写。
            BpmInstance endedInstance = bpmInstanceService
                    .findByProcessInstanceId(processInstanceId).orElse(null);
            String endedStatus = endedInstance == null ? null : endedInstance.getStatus();
            if (endedStatus != null
                    && !InstanceStatusEnum.RUNNING.getCode().equals(endedStatus)) {
                log.info("流程已由节点结算写终态({})，动作侧不改写终态/通知: processInstanceId={}",
                        endedStatus, processInstanceId);
            } else {
                // 会签负向终局经 ConsensusSettlementPort 结算（实例被终止）：
                // 会签任务的 DISAPPROVE 若使流程终结，终态=REJECTED，不得按默认正向写 APPROVED。
                boolean negativeTerminal = action == ApprovalAction.REJECT
                        || (action == ApprovalAction.DISAPPROVE
                            && (isConsensusTask(task)
                                || !isConsensusTask(task)
                                    && (nodeDisapprovePolicy(task) == null
                                        || "TERMINATE".equalsIgnoreCase(nodeDisapprovePolicy(task)))));
                String terminalStatus = negativeTerminal
                        ? InstanceStatusEnum.REJECTED.getCode()
                        : InstanceStatusEnum.APPROVED.getCode();
                bpmInstanceService.updateStatus(processInstanceId, terminalStatus);
                log.info("流程已结束，实例状态更新为 {}: processInstanceId={}",
                        terminalStatus, processInstanceId);

                // — 发布审批结果通知事件（DISAPPROVE 使用独立语义触发器） —
                publishProcessEvent(processInstanceId, loginUser,
                        action == ApprovalAction.DISAPPROVE && !isConsensusTask(task)
                                ? BpmNotifyTrigger.PROCESS_DISAPPROVED
                                : negativeTerminal ? BpmNotifyTrigger.PROCESS_REJECTED
                                : BpmNotifyTrigger.PROCESS_APPROVED);

                // — 审批结果驱动设备：流程变量携带 productId/deviceName/commandKey 时发布设备命令事件 —
                if (productId != null && deviceName != null && commandKey != null) {
                    if (commandType == null) {
                        commandType = "PROPERTY";
                    }
                    domainEventPublisher.publish(new BpmDeviceCommandEvent(
                            processInstanceId, productId, deviceName,
                            commandKey, commandType,
                            loginUser.getTenantId(), loginUser.getUserId()));
                    log.info("设备命令事件已发布: processInstanceId={}, productId={}, deviceName={}, commandKey={}",
                            processInstanceId, productId, deviceName, commandKey);
                }
            }
        }

        return com.sw.ck.common.response.R.ok();
    }

    /** 按 ID 批量解析用户展示名；查不到的 ID 返回 null，不阻断查询。 */
    public Map<Long, String> resolveUserNames(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        try {
            return userQueryFacade.getUserDisplayNames(ids);
        } catch (Exception e) {
            log.warn("用户展示名批量查询失败，回退为 null: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 解析任务节点的意见表单配置（详情与动作校验共用）。 */
    public Map<String, Object> resolveOpinionForm(BpmTaskDTO task) {
        if (bpmProcessDefService == null || objectMapper == null
                || task.getProcessDefinitionKey() == null || task.getTaskDefinitionKey() == null) {
            return Map.of();
        }
        try {
            BpmProcessDef definition = bpmProcessDefService
                    .findByProcessKey(task.getProcessDefinitionKey());
            if (definition == null || definition.getGraphJson() == null) return Map.of();
            ProcessGraph graph = objectMapper.readValue(definition.getGraphJson(), ProcessGraph.class);
            if (graph.getElements() == null) return Map.of();
            return graph.getElements().stream()
                    .filter(element -> "node".equals(element.getKind())
                            && task.getTaskDefinitionKey().equals(element.getId()))
                    .map(GraphElement::getConfig)
                    .filter(java.util.Objects::nonNull)
                    .map(config -> config.get("opinionForm"))
                    .filter(Map.class::isInstance)
                    .map(value -> (Map<String, Object>) value)
                    .findFirst().orElse(Map.of());
        } catch (Exception e) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
    }

    public boolean isConsensusTask(BpmTaskDTO task) {
        if (task == null || bpmProcessDefService == null || objectMapper == null
                || task.getProcessDefinitionKey() == null || task.getTaskDefinitionKey() == null) {
            return false;
        }
        try {
            BpmProcessDef definition = bpmProcessDefService
                    .findByProcessKey(task.getProcessDefinitionKey());
            if (definition == null || definition.getGraphJson() == null) return false;
            ProcessGraph graph = objectMapper.readValue(definition.getGraphJson(), ProcessGraph.class);
            if (graph.getElements() == null) return false;
            return graph.getElements().stream()
                    .filter(element -> "node".equals(element.getKind())
                            && task.getTaskDefinitionKey().equals(element.getId()))
                    .anyMatch(element -> "CONSENSUS".equalsIgnoreCase(element.getType()));
        } catch (Exception e) {
            return false;
        }
    }

    private void recordAction(BpmTaskDTO task, LoginUser loginUser,
                              ApprovalActionRequest request, ApprovalAction action,
                              String settlementStatus, Map<String, Object> processVariables,
                              Long commandId, Integer roundNo) {
        if (approvalActionService == null) return;
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setProcessInstanceId(task.getProcessInstanceId());
        record.setNodeKey(task.getTaskDefinitionKey() == null
                ? task.getTaskId() : task.getTaskDefinitionKey());
        record.setTaskId(task.getTaskId());
        record.setActorId(loginUser.getUserId());
        record.setAction(action.name());
        record.setCommandId(commandId);
        record.setOpinionFormId(request.getOpinionFormId());
        record.setOpinionFormVersion(request.getOpinionFormVersion());
        record.setRoundNo(roundNo);
        if (request.getTargetUserId() != null) {
            record.setTargetUserId(request.getTargetUserId());
        }
        Map<String, Object> opinionData = request.getOpinionData() == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(request.getOpinionData());
        if (request.getComment() != null && !request.getComment().isBlank()) {
            opinionData.putIfAbsent("comment", request.getComment());
        }
        try {
            record.setOpinionData(objectMapper == null ? "{}" : objectMapper.writeValueAsString(opinionData));
            Map<String, Object> initialization = new java.util.LinkedHashMap<>();
            initialization.put("source", "processVariables.formData");
            Object formData = processVariables == null ? null : processVariables.get("formData");
            if (formData instanceof Map<?, ?> values) {
                initialization.put("sourceFields", values.keySet().stream().map(String::valueOf).toList());
            }
            record.setInitializationSummary(objectMapper == null ? "{}"
                    : objectMapper.writeValueAsString(initialization));
            // I3 意见表单不可变快照：等价引用 = 提交时刻的节点意见表单配置 + 主表单版本摘要；
            // 后续修改/停用意见表单不改变历史解释（§4.10）。
            Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("opinionForm", resolveOpinionForm(task));
            snapshot.put("opinionFormId", request.getOpinionFormId());
            snapshot.put("opinionFormVersion", request.getOpinionFormVersion());
            Object formVersion = processVariables == null ? null
                    : processVariables.get("formVersion");
            if (formVersion != null) {
                snapshot.put("mainFormVersion", String.valueOf(formVersion));
            }
            record.setOpinionFormSnapshot(objectMapper == null ? "{}"
                    : objectMapper.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.APPROVAL_OPINION_INVALID);
        }
        record.setSettlementStatus(settlementStatus);
        record.setTenantId(loginUser.getTenantId());
        approvalActionService.save(record);
    }

    /** 计算退回新轮次：本实例 RETURNED 动作数 + 1 逐轮递增。 */
    private int nextReturnRound(String processInstanceId) {
        if (approvalActionService == null) {
            return 1;
        }
        long previous = approvalActionService.findByProcessInstanceId(processInstanceId)
                .stream()
                .filter(item -> item.getAction() != null
                        && ApprovalAction.RETURN.name().equals(item.getAction()))
                .count();
        return (int) previous + 1;
    }

    /** I3 §4.9：结果处理白名单函数执行（输出不改变流程走向，仅审计摘要/白名单变量）。 */
    private void runHandleResultFunction(BpmTaskDTO task, LoginUser loginUser,
                                         Map<String, Object> processVariables,
                                         ApprovalAction action) {
        if (nodeFunctionService == null) {
            return;
        }
        try {
            BpmProcessDef definition = bpmProcessDefService
                    .findByProcessKey(task.getProcessDefinitionKey());
            if (definition == null || definition.getGraphJson() == null) return;
            ProcessGraph graph = objectMapper.readValue(definition.getGraphJson(), ProcessGraph.class);
            if (graph.getElements() == null) return;
            Map<String, Object> nodeConfig = graph.getElements().stream()
                    .filter(element -> "node".equals(element.getKind())
                            && task.getTaskDefinitionKey().equals(element.getId()))
                    .map(GraphElement::getConfig)
                    .filter(java.util.Objects::nonNull)
                    .filter(config -> config.get("functions") != null)
                    .findFirst().orElse(null);
            if (nodeConfig == null) {
                return;
            }
            Map<String, Object> functions = new java.util.LinkedHashMap<>();
            functions.put("functions", nodeConfig.get("functions"));
            Map<String, Object> variables = new java.util.LinkedHashMap<>(
                    processVariables == null ? Map.of() : processVariables);
            variables.put("outcome", action.name());
            com.sw.ck.bpm.api.nodefunc.NodeFunctionResult result = nodeFunctionService
                    .handleResult(
                            com.sw.ck.bpm.api.nodefunc.NodeFunctionContext.builder()
                                    .tenantId(loginUser.getTenantId())
                                    .processInstanceId(task.getProcessInstanceId())
                                    .nodeKey(task.getTaskDefinitionKey())
                                    .nodeIdempotentKey(task.getTaskId())
                                    .initiatorUserId(asString(variables.get("submitter")) == null
                                            ? null : Long.valueOf(asString(variables.get("submitter"))))
                                    .actorUserId(loginUser.getUserId())
                                    .variables(variables)
                                    .build(), functions,
                            Map.of("outcome", switch (action) {
                                case REJECT -> "REJECTED";
                                case DISAPPROVE -> "DISAPPROVED";
                                default -> "APPROVED";
                            }));
            if (result != null && result.getSummary() != null) {
                log.info("节点结果函数完成: taskId={}, summary={}", task.getTaskId(),
                        result.getSummary());
            }
            // 白名单结果变量写回流程（I3 §4.9）：变量名限 [a-z_][a-z0-9_]{0,63}、值≤2000 字符、≤10 个，
            // 只允许补充结果变量，不改变 outcome/task/表单；超限即拒绝。
            if (result != null && result.getResultVariables() != null
                    && !result.getResultVariables().isEmpty()) {
                if (result.getResultVariables().size() > 10) {
                    throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
                }
                for (Map.Entry<String, Object> entry : result.getResultVariables().entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    if (!name.matches("[a-z_][a-z0-9_]{0,63}")) {
                        throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
                    }
                    String value = entry.getValue() == null ? "null" : String.valueOf(entry.getValue());
                    if (value.length() > 2000) {
                        throw new BaseException(com.sw.ck.bpm.api.exception.BpmErrorCode.NODE_FUNCTION_INVALID_OUTPUT);
                    }
                    bpmTaskFacade.setVariable(task.getProcessInstanceId(), name, value);
                }
            }
        } catch (BaseException e) {
            // 白名单校验失败按函数失败处理：不回滚已完成的审批动作，仅审计
            log.warn("结果函数输出不合法（不回滚审批状态）: taskId={}, error={}",
                    task.getTaskId(), e.getMessage());
        } catch (Exception e) {
            // 函数失败不回滚已完成的审批动作；仅记录
            log.warn("结果函数执行失败（不回滚审批状态）: taskId={}, error={}",
                    task.getTaskId(), e.getMessage());
        }
    }

    /** 读取节点的不通过意见结算策略（disapprovePolicy；默认 TERMINATE）。 */
    private String nodeDisapprovePolicy(BpmTaskDTO task) {
        if (bpmProcessDefService == null || objectMapper == null
                || task.getProcessDefinitionKey() == null || task.getTaskDefinitionKey() == null) {
            return null;
        }
        try {
            BpmProcessDef definition = bpmProcessDefService
                    .findByProcessKey(task.getProcessDefinitionKey());
            if (definition == null || definition.getGraphJson() == null) return null;
            ProcessGraph graph = objectMapper.readValue(definition.getGraphJson(), ProcessGraph.class);
            if (graph.getElements() == null) return null;
            return graph.getElements().stream()
                    .filter(element -> "node".equals(element.getKind())
                            && task.getTaskDefinitionKey().equals(element.getId()))
                    .map(GraphElement::getConfig)
                    .filter(java.util.Objects::nonNull)
                    .map(config -> config.get("disapprovePolicy"))
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void publishProcessEvent(String processInstanceId, LoginUser loginUser,
                                     BpmNotifyTrigger trigger) {        BpmInstance instance = bpmInstanceService
                .findByProcessInstanceId(processInstanceId)
                .orElse(null);
        if (instance == null) {
            log.warn("流程实例记录不存在: processInstanceId={}，跳过 {} 通知",
                    processInstanceId, trigger);
            return;
        }

        BpmNotifyEvent event = new BpmNotifyEvent(
                trigger,
                instance.getInitiatorId(),
                loginUser.getTenantId(),
                loginUser.getUserId(),
                processInstanceId
        );
        domainEventPublisher.publish(event);
        log.debug("流程结果事件已发布: trigger={}, processInstanceId={}, initiatorId={}",
                trigger, processInstanceId, instance.getInitiatorId());
    }

    /**
     * 退回新轮次 TODO_CREATED：与 ProcessStartService.publishTodoCreatedEvent 同一语义（经
     * Facade 查询重建任务的 assignee/candidates 逐人发布），轮次身份按新 task 唯一，
     * 与上一轮待办不冲突、不误杀。仅异常路径 warn，不阻断退回主事务。
     */
    private void publishReturnedRoundTodoCreated(String processInstanceId, LoginUser loginUser) {
        try {
            java.util.List<BpmTaskDTO> tasks = bpmTaskFacade.queryByProcessInstance(processInstanceId);
            BpmTaskDTO matchedTask = tasks.stream().findFirst().orElse(null);
            if (matchedTask == null) {
                log.warn("流程 {} 退回后无重建待办 task，跳过新轮次 TODO_CREATED 通知", processInstanceId);
                return;
            }
            java.util.LinkedHashSet<String> recipientIds = new java.util.LinkedHashSet<>();
            if (matchedTask.getAssignee() != null) recipientIds.add(matchedTask.getAssignee());
            if (matchedTask.getCandidateUserIds() != null) {
                recipientIds.addAll(matchedTask.getCandidateUserIds());
            }
            for (String recipient : recipientIds) {
                Long recipientId;
                try {
                    recipientId = Long.valueOf(recipient);
                } catch (NumberFormatException e) {
                    log.warn("退回新轮次 task participant 非数字格式: participant={}，跳过该 TODO_CREATED 通知", recipient);
                    continue;
                }
                domainEventPublisher.publish(new BpmNotifyEvent(BpmNotifyTrigger.TODO_CREATED,
                        recipientId, loginUser.getTenantId(), loginUser.getUserId(), matchedTask.getTaskId()));
            }
            log.info("退回新轮次 TODO_CREATED 事件已发布: taskId={}, recipients={}",
                    matchedTask.getTaskId(), recipientIds);
        } catch (Exception e) {
            log.warn("退回新轮次通知失败（不回滚退回）: processInstanceId={}, exceptionClass={}",
                    processInstanceId, e.getClass().getSimpleName());
        }
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private BaseException findBaseException(Throwable error, int code) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BaseException baseException && baseException.getCode() == code) {
                return baseException;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 双实例/重复并发下，同任务重复办理撞会签投票唯一键或引擎乐观锁——另一请求已合法完成。 */
    private boolean isConcurrentTaskCollision(Throwable error) {
        Throwable current = error;
        while (current != null) {
            // sw-bpm-process 不直接依赖 flowable common-api，用类名识别避免包依赖扩散
            if (current.getClass().getSimpleName().contains("OptimisticLocking")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("uk_sw_bpm_vote_task_actor")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
