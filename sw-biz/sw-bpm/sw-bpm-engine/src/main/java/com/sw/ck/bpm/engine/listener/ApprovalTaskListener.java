package com.sw.ck.bpm.engine.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverContext;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverResolver;
import com.sw.ck.bpm.api.spi.assignee.NodeApproverType;
import com.sw.ck.bpm.api.participant.NodeParticipantContext;
import com.sw.ck.bpm.api.participant.ParticipantSnapshotRecorder;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.common.exception.BaseException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.task.service.delegate.DelegateTask;
import org.flowable.task.service.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.sw.ck.bpm.engine.participant.ParticipantResolverRegistry;
import com.sw.ck.common.event.DomainEventPublisher;

import java.util.List;
import java.util.Map;

/**
 * 审批任务创建监听器（Flowable TaskListener）。
 * <p>
 * 在 UserTask 创建时（create 事件）被 Flowable 引擎回调，
 * 读取 BPMN 扩展属性中的 approver 配置，经 {@link NodeApproverResolver} 分发解析后设置 assignee。
 * </p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>从 {@link DelegateTask#getTaskDefinitionKey()} 获取节点 ID（nodeKey）</li>
 *   <li>从 {@link DelegateTask#getProcessDefinitionId()} 加载 BpmnModel</li>
 *   <li>查找 UserTask 并读取 {@code approverConfig} 扩展元素（JSON）</li>
 *   <li>解析 approver 类型 + 值，按 type 从 {@code resolverMap} 分发</li>
 *   <li>取解析结果首个 userId 设为 assignee（v1 单人）</li>
 * </ol>
 *
 * <h3>错误码</h3>
 * <ul>
 *   <li>2200 — 解析结果为空（无人可分配）</li>
 *   <li>2201 — 审批人类型未实现（如 SCRIPT 桩）</li>
 *   <li>2202 — 审批人配置缺失（BPMN 扩展元素中无 approverConfig）</li>
 * </ul>
 */
@Component
public class ApprovalTaskListener implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTaskListener.class);

    private static final String APPROVER_CONFIG_ELEMENT = "approverConfig";
    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";
    private static final String CONFIG_KEY_TYPE = "type";
    private static final String CONFIG_KEY_VALUE = "value";

    private final RepositoryService repositoryService;
    private final Map<String, NodeApproverResolver> resolverMap;
    private final ObjectMapper objectMapper;
    private final ParticipantResolverRegistry participantResolverRegistry;
    private final ParticipantSnapshotRecorder participantSnapshotRecorder;
    private final UserQueryFacade userQueryFacade;
    /** 任务创建通知统一经领域事件发布，监听器在事务提交后异步落通知。 */
    private final DomainEventPublisher domainEventPublisher;
    /** 可选生命周期端口（I3：代理改派 + 时限登记；未装配时跳过）。 */
    @Autowired(required = false)
    private transient org.springframework.beans.factory.ObjectProvider<com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort> lifecycleEntryPort;

    /** 兼容既有引擎单测与旧 DESIGNATED 配置。 */
    public ApprovalTaskListener(RepositoryService repositoryService,
                                @org.springframework.beans.factory.annotation.Qualifier("approverResolverMap")
                                Map<String, NodeApproverResolver> resolverMap,
                                ObjectMapper objectMapper) {
        this(repositoryService, resolverMap, objectMapper, null, null, null, null);
    }

    @Autowired
    public ApprovalTaskListener(RepositoryService repositoryService,
                                @org.springframework.beans.factory.annotation.Qualifier("approverResolverMap")
                                Map<String, NodeApproverResolver> resolverMap,
                                ObjectMapper objectMapper,
                                ParticipantResolverRegistry participantResolverRegistry,
                                ObjectProvider<ParticipantSnapshotRecorder> participantSnapshotRecorder,
                                ObjectProvider<UserQueryFacade> userQueryFacade,
                                DomainEventPublisher domainEventPublisher) {
        this.repositoryService = repositoryService;
        this.resolverMap = resolverMap;
        this.objectMapper = objectMapper;
        this.participantResolverRegistry = participantResolverRegistry;
        this.participantSnapshotRecorder = participantSnapshotRecorder == null
                ? null : participantSnapshotRecorder.getIfAvailable();
        this.userQueryFacade = userQueryFacade == null ? null : userQueryFacade.getIfAvailable();
        this.domainEventPublisher = domainEventPublisher;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void notify(DelegateTask delegateTask) {
        String nodeKey = delegateTask.getTaskDefinitionKey();
        String processDefinitionId = delegateTask.getProcessDefinitionId();
        String processInstanceId = delegateTask.getProcessInstanceId();

        log.debug("TaskListener triggered: nodeKey={}, processDefinitionId={}, processInstanceId={}",
                nodeKey, processDefinitionId, processInstanceId);

        // 1. 加载 BpmnModel 并查找当前 UserTask
        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        if (bpmnModel == null) {
            log.error("BpmnModel not found for processDefinitionId={}", processDefinitionId);
            throw new BaseException(BpmErrorCode.APPROVER_CONFIG_MISSING.getCode(),
                    "无法加载流程定义模型: " + processDefinitionId);
        }

        UserTask userTask = (UserTask) bpmnModel.getFlowElement(nodeKey);
        if (userTask == null) {
            log.error("UserTask not found in BpmnModel: nodeKey={}", nodeKey);
            throw new BaseException(BpmErrorCode.APPROVER_CONFIG_MISSING.getCode(),
                    "BPMN 模型中未找到节点: " + nodeKey);
        }

        // 2. 读取统一 participantConfig；旧 approverConfig 作为兼容入口
        String approverJson = userTask.getAttributeValue(FLOWABLE_NS, "participantConfig");
        boolean unifiedConfig = approverJson != null && !approverJson.isBlank();
        if (!unifiedConfig) {
            approverJson = userTask.getAttributeValue(FLOWABLE_NS, APPROVER_CONFIG_ELEMENT);
        }
        if (approverJson == null || approverJson.isBlank()) {
            log.error("Approver config missing in UserTask attributes: nodeKey={}", nodeKey);
            throw new BaseException(BpmErrorCode.APPROVER_CONFIG_MISSING);
        }

        // 3. 解析 approver 配置
        Map<String, Object> approverConfig;
        try {
            approverConfig = objectMapper.readValue(approverJson,
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse approver config JSON: {}", e.getMessage());
            throw new BaseException(BpmErrorCode.APPROVER_CONFIG_MISSING.getCode(),
                    "审批人配置 JSON 解析失败: " + e.getMessage());
        }

        Object typeValue = approverConfig.containsKey("strategy")
                ? approverConfig.get("strategy") : approverConfig.get(CONFIG_KEY_TYPE);
        String type = typeValue == null ? null : String.valueOf(typeValue);
        Object value = approverConfig.get(CONFIG_KEY_VALUE);

        if (type == null || type.isBlank()) {
            throw new BaseException(BpmErrorCode.APPROVER_CONFIG_MISSING.getCode(),
                    "审批人类型为空: nodeKey=" + nodeKey);
        }

        // 构建上下文
        Long tenantId = parseLong(delegateTask.getVariable("tenantId"));
        if (tenantId == null) {
            log.error("TenantId missing in process variables: processInstanceId={}, nodeKey={}",
                    processInstanceId, nodeKey);
            throw new BaseException(BpmErrorCode.APPROVER_TENANT_ID_MISSING);
        }
        List<String> userIds;
        if (unifiedConfig && participantResolverRegistry != null) {
            Map<String, Object> variables = new java.util.LinkedHashMap<>(delegateTask.getVariables());
            variables.putIfAbsent("initiator", parseLong(delegateTask.getVariable("submitter")));
            variables.putIfAbsent("initiatorId", parseLong(delegateTask.getVariable("submitter")));
            NodeParticipantContext ctx = NodeParticipantContext.builder()
                    .tenantId(tenantId)
                    .processInstanceId(processInstanceId)
                    .taskId(delegateTask.getId())
                    .nodeKey(nodeKey)
                    .businessKey(delegateTask.getVariable("recordId") == null ? null
                            : String.valueOf(delegateTask.getVariable("recordId")))
                    .formKey(delegateTask.getVariable("formKey") == null ? null
                            : String.valueOf(delegateTask.getVariable("formKey")))
                    .initiatorUserId(parseLong(delegateTask.getVariable("submitter")))
                    .variables(variables)
                    .strategy(type)
                    .strategyValue(value)
                    .adapterId(approverConfig.get("adapterId") == null
                            ? null : String.valueOf(approverConfig.get("adapterId")))
                    .build();
            userIds = participantResolverRegistry.resolve(ctx);
        } else {
            NodeApproverResolver resolver = resolverMap.get(type);
            if (resolver == null) {
                log.error("Approver type '{}' not implemented (nodeKey={})", type, nodeKey);
                throw new BaseException(BpmErrorCode.APPROVER_TYPE_NOT_IMPLEMENTED.getCode(),
                        "未实现的审批人类型: " + type);
            }
            NodeApproverContext ctx = NodeApproverContext.builder()
                    .tenantId(tenantId).processInstanceId(processInstanceId).nodeKey(nodeKey)
                    .businessKey((String) delegateTask.getVariable("recordId"))
                    .formKey((String) delegateTask.getVariable("formKey"))
                    .approverValue(value)
                    .initiatorUserId(parseLong(delegateTask.getVariable("submitter"))).build();
            // 解析器契约恒 present；empty 属契约违约，按解析不到审批人拒绝
            userIds = resolver.resolve(ctx).orElseThrow(
                    () -> new BaseException(BpmErrorCode.APPROVER_RESOLVE_EMPTY));
        }
        if (userIds == null || userIds.isEmpty()) {
            log.error("Approver resolution returned empty: nodeKey={}, type={}", nodeKey, type);
            throw new BaseException(BpmErrorCode.APPROVER_RESOLVE_EMPTY);
        }

        // 单用户继续使用原生 assignee；多人统一作为候选任务，避免只取首人
        List<String> notificationUsers = userIds;
        if (userIds.size() == 1) {
            String nodeConfigJson;
            try {
                nodeConfigJson = userTask.getAttributeValue(FLOWABLE_NS, "nodeConfig");
            } catch (Exception e) {
                nodeConfigJson = null;
            }
            com.sw.ck.bpm.api.participant.LifecycleTaskEntryPort entryPort =
                    lifecycleEntryPort == null ? null : lifecycleEntryPort.getIfAvailable();
            List<String> effectiveUsers = userIds;
            if (entryPort != null) {
                try {
                    // empty = 未配置函数或函数服务不可用：走默认策略解析结果
                    java.util.Optional<List<String>> functionResolved = entryPort.resolveParticipantsByFunction(
                            tenantId, processInstanceId, nodeKey, delegateTask.getId(),
                            new java.util.LinkedHashMap<>(delegateTask.getVariables()),
                            nodeConfigJson);
                    if (functionResolved.isPresent() && !functionResolved.orElseThrow().isEmpty()) {
                        effectiveUsers = functionResolved.orElseThrow();
                    }
                } catch (Exception e) {
                    log.warn("节点函数参与人解析失败: taskId={}, error={}",
                            delegateTask.getId(), e.getMessage());
                }
                try {
                    // empty = 本次不改写 assignee（走默认派发）
                    java.util.Optional<String> proxied = entryPort.onTaskCreate(tenantId, processInstanceId,
                            nodeKey, delegateTask.getId(), effectiveUsers, nodeConfigJson)
                            .filter(assignee -> !assignee.isBlank());
                    if (proxied.isPresent()) {
                        delegateTask.setOwner(effectiveUsers.get(0));
                        effectiveUsers = List.of(proxied.orElseThrow());
                    }
                } catch (Exception e) {
                    log.warn("任务进入生命周期处理失败（不阻断任务创建）: taskId={}, error={}",
                            delegateTask.getId(), e.getMessage());
                }
            }
            notificationUsers = effectiveUsers;
            delegateTask.setAssignee(effectiveUsers.get(0));
        } else {
            for (String userId : userIds) {
                delegateTask.addCandidateUser(userId);
            }
        }

        if (participantSnapshotRecorder != null) {
            // 冻结参与人展示名：历史流程身份不随后续改名/停用被重写（I1）
            java.util.Map<String, String> frozen = new java.util.LinkedHashMap<>();
            if (userQueryFacade != null) {
                try {
                    List<Long> ids = userIds.stream()
                            .filter(id -> id != null && id.matches("\\d+"))
                            .map(Long::valueOf).distinct().toList();
                    // empty = 查询对象缺失：无展示名可冻结，快照仅记录 ID
                    userQueryFacade.getUserDisplayNames(ids).ifPresent(names ->
                            names.forEach((id, name) -> frozen.put(String.valueOf(id), name)));
                } catch (Exception e) {
                    log.warn("参与人展示名冻结失败，快照仅记录 ID: {}", e.getMessage());
                }
            }
            java.util.Map<String, String> displayNames = java.util.Collections.unmodifiableMap(frozen);
            participantSnapshotRecorder.record(processInstanceId, nodeKey, delegateTask.getId(),
                    userIds, displayNames, tenantId)
            .orElseThrow(() -> new IllegalStateException(
                    "ParticipantSnapshotRecorder#record 契约恒 present，empty 属契约违约"));
        }

        log.info("Task assignee set: taskId={}, nodeKey={}, assignee={}",
                delegateTask.getId(), nodeKey, userIds.size() == 1 ? userIds.get(0) : "CANDIDATES");

        publishNextTaskTodoCreated(delegateTask, tenantId, processInstanceId, notificationUsers);
    }

    /**
     * 任务创建回调位于引擎推进事务内；以任务本身的已解析参与人发布事件，
     * 让 AFTER_COMMIT 监听器在任务可查询后写入 TODO_CREATED。首个任务没有
     * lastApprovalActorId，因此仍由启动服务负责首轮通知。
     */
    private void publishNextTaskTodoCreated(DelegateTask task, Long tenantId,
                                            String processInstanceId,
                                            List<String> recipients) {
        if (domainEventPublisher == null || task.getVariable("lastApprovalActorId") == null
                || recipients == null || recipients.isEmpty()) {
            return;
        }
        Long actorId = parseLong(task.getVariable("lastApprovalActorId"));
        if (actorId == null) {
            actorId = parseLong(task.getVariable("submitter"));
        }
        if (actorId == null) {
            log.warn("下一个任务缺少审批操作人，跳过 TODO_CREATED: processInstanceId={}",
                    processInstanceId);
            return;
        }
        for (String recipient : recipients) {
            Long recipientId = parseLong(recipient);
            if (recipientId == null) {
                log.warn("task participant 非数字格式，跳过 TODO_CREATED: participant={}", recipient);
                continue;
            }
            domainEventPublisher.publish(new BpmNotifyEvent(
                    BpmNotifyTrigger.TODO_CREATED, recipientId, tenantId, actorId, task.getId()));
        }
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
