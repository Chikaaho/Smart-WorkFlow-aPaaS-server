package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.api.dto.ApproverContext;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.event.BpmNotifyEvent;
import com.sw.ck.bpm.api.event.BpmNotifyTrigger;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.spi.ApproverResolver;
import com.sw.ck.bpm.process.dto.StartCommand;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.system.api.user.UserQueryFacade;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.common.exception.BaseException;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程发起唯一入口。
 * <p>
 * 封装表单绑定查询 → 审批人解析 → 流程发起 → 实例落库 四个步骤，
 * 由 {@code @Transactional} 保证流程发起 + 实例插入的原子性。
 * </p>
 *
 * <p>
 * 调用方：
 * <ul>
 *   <li>{@link com.sw.ck.bpm.process.listener.FormSubmittedEventListener}
 *       — 表单提交事件 {@code AFTER_COMMIT} 后异步触发</li>
 *   <li>后续 {@code ScheduledFlowTriggerEvent} 监听器 — 定时任务 FLOW 类型发起</li>
 * </ul>
 * </p>
 *
 * <p>
 * 防腐：所有引擎操作经 {@link BpmRuntimeFacade} / {@link BpmTaskFacade}，
 * 不 import 任何 Flowable 类型。
 * </p>
 *
 * <p>
 * 事务边界：{@code @Transactional} 仅包围 bpm 侧的操作（流程发起 + 实例落库），
 * 表单提交的事务已在 {@code AFTER_COMMIT} 时独立提交。流程发起失败不回滚表单数据，
 * 这是预期的解耦设计。
 * </p>
 */
@Service
public class ProcessStartService {

    private static final Logger log = LoggerFactory.getLogger(ProcessStartService.class);

    private final BpmFormBindingService bindingService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sw.ck.bpm.process.service.BpmProcessDefService bpmProcessDefService;
    private final ApproverResolver approverResolver;
    private final BpmRuntimeFacade bpmRuntimeFacade;
    private final BpmTaskFacade bpmTaskFacade;
    private final BpmInstanceService bpmInstanceService;
    private final DomainEventPublisher domainEventPublisher;
    /** 可选：发起前置校验发起人在本租户有效且启用（I1）。 */
    private final UserQueryFacade userQueryFacade;

    public ProcessStartService(BpmFormBindingService bindingService,
                                ApproverResolver approverResolver,
                                BpmRuntimeFacade bpmRuntimeFacade,
                                BpmTaskFacade bpmTaskFacade,
                                BpmInstanceService bpmInstanceService,
                                DomainEventPublisher domainEventPublisher) {
        this(bindingService, approverResolver, bpmRuntimeFacade, bpmTaskFacade,
                bpmInstanceService, domainEventPublisher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProcessStartService(BpmFormBindingService bindingService,
                                ApproverResolver approverResolver,
                                BpmRuntimeFacade bpmRuntimeFacade,
                                BpmTaskFacade bpmTaskFacade,
                                BpmInstanceService bpmInstanceService,
                                DomainEventPublisher domainEventPublisher,
                                ObjectProvider<UserQueryFacade> userQueryFacade) {
        this.bindingService = bindingService;
        this.approverResolver = approverResolver;
        this.bpmRuntimeFacade = bpmRuntimeFacade;
        this.bpmTaskFacade = bpmTaskFacade;
        this.bpmInstanceService = bpmInstanceService;
        this.domainEventPublisher = domainEventPublisher;
        this.userQueryFacade = userQueryFacade == null ? null : userQueryFacade.getIfAvailable();
    }

    /**
     * 发起流程实例。
     * <ol>
     *   <li>查启用绑定 — 无绑定则 info 日志 return（no-op，不是每个表单都走流程）</li>
     *   <li>解析审批人 — 经 {@link ApproverResolver} 获取 approver 变量值</li>
     *   <li>经 Facade 发起 — {@code bpmRuntimeFacade.startProcess(...)}</li>
     *   <li>落实例 — {@code sw_bpm_instance} 按引擎运行态写入 RUNNING 或 APPROVED（基列靠拦截器自动注入）</li>
     * </ol>
     *
     * @param cmd 发起命令，不可为空
     */
    @Transactional
    public void start(StartCommand cmd) {
        // 1. 查启用绑定
        List<BpmFormBinding> bindings = bindingService.findActiveByFormKey(cmd.getFormKey());
        if (bindings.isEmpty()) {
            log.info("表单 {} 无启用绑定，跳过流程发起", cmd.getFormKey());
            return;
        }

        // 0. 发起人有效性前置校验（I1）：停用/跨租户/已删除用户不得发起流程
        requireActiveInitiator(cmd);
        BpmFormBinding binding;
        if (cmd.getProcessDefKey() != null && !cmd.getProcessDefKey().isBlank()) {
            binding = bindings.stream()
                    .filter(candidate -> cmd.getProcessDefKey().equals(candidate.getProcessDefKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "表单流程绑定已失效: formKey=" + cmd.getFormKey()
                                    + ", processDefKey=" + cmd.getProcessDefKey()));
        } else {
            if (bindings.size() != 1) {
                throw new IllegalStateException("表单存在多个有效流程绑定: formKey=" + cmd.getFormKey());
            }
            binding = bindings.get(0);
        }

        // 2. 解析审批人
        ApproverContext ctx = new ApproverContext();
        ctx.setFormKey(cmd.getFormKey());
        ctx.setSubmittedData(cmd.getSubmittedData());
        ctx.setSubmitter(cmd.getSubmitter());
        ctx.setTenantId(cmd.getTenantId());
        String approver = approverResolver.resolve(ctx);

        log.debug("审批人解析完成: resolver={}, approver={}",
                approverResolver.getClass().getSimpleName(), approver);

        // 3. 经 Facade 发起；formData 是本次提交的只读快照，供受控表达式和意见初始化使用。
        if (cmd.getTenantId() == null) {
            throw new IllegalArgumentException(
                    "tenantId must not be null when starting process; formKey=" + cmd.getFormKey());
        }
        Map<String, Object> variables = new HashMap<>();
        variables.put("approver", approver);
        variables.put("formKey", cmd.getFormKey());
        variables.put("recordId", cmd.getRecordId());
        variables.put("submitter", String.valueOf(cmd.getSubmitter()));
        variables.put("tenantId", cmd.getTenantId());
        Map<String, Object> formData = new LinkedHashMap<>();
        if (cmd.getSubmittedData() != null) {
            formData.putAll(cmd.getSubmittedData());
        }
        variables.put("formData", Collections.unmodifiableMap(formData));

        // 设备控制透传：表单数据若携带 device_key/deviceKey、command_key/commandKey，
        // 透传为流程变量，供审批通过后下发设备命令（无则跳过，不影响普通审批流）
        putFirstNotBlank(variables, "deviceKey", cmd.getSubmittedData(), "device_key", "deviceKey");
        putFirstNotBlank(variables, "commandKey", cmd.getSubmittedData(), "command_key", "commandKey");

        // 原: runtimeService.startProcessInstanceByKeyAndTenantId(...)
        // → bpmRuntimeFacade.startProcess(...)
        String processInstanceId = bpmRuntimeFacade.startProcess(
                binding.getProcessDefKey(),
                cmd.getRecordId(),
                variables,
                String.valueOf(cmd.getTenantId())
        );

        log.info("流程已发起: processInstanceId={}, processDefKey={}, businessKey={}, tenantId={}",
                processInstanceId, binding.getProcessDefKey(), cmd.getRecordId(), cmd.getTenantId());

        // 4. 落实例记录（基列由 MyBatis-Plus 拦截器自动注入，不手动填）
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId(processInstanceId);
        instance.setProcessDefKey(binding.getProcessDefKey());
        instance.setBusinessKey(cmd.getRecordId());
        instance.setFormKey(cmd.getFormKey());
        instance.setInitiatorId(cmd.getSubmitter());
        // I3 §4.3：运行实例绑定明确的发布版本（发起时刻 def.published_version 快照）
        BpmProcessDef startedDef = bpmProcessDefServiceByName(binding);
        if (startedDef != null && startedDef.getPublishedVersion() != null) {
            instance.setDefVersion(startedDef.getPublishedVersion());
        }
        // Flowable 可能在 startProcess 返回前就完成无人工节点的流程。此时若无条件写
        // RUNNING，会产生“引擎已到 End、业务记录仍运行中”的假终态；沿用审批完成路径
        // 的 APPROVED 语义。
        boolean processActive = bpmTaskFacade.isProcessActive(processInstanceId);
        instance.setStatus(processActive
                ? InstanceStatusEnum.RUNNING.getCode()
                : InstanceStatusEnum.APPROVED.getCode());
        bpmInstanceService.save(instance);

        log.info("流程实例记录已保存: id={}, status={}, processActive={}",
                instance.getId(), instance.getStatus(), processActive);

        // 5. 发布 TODO_CREATED 通知事件（查询刚创建的 task）
        if (processActive) {
            publishTodoCreatedEvent(processInstanceId, cmd);
        } else {
            log.info("流程启动后已到达终态，跳过 TODO_CREATED 通知: processInstanceId={}",
                    processInstanceId);
        }
    }

    /** 查定义行以绑定发布版本（bpmProcessDefService 可选注入；查不到返回 null）。 */
    private BpmProcessDef bpmProcessDefServiceByName(BpmFormBinding binding) {
        if (bpmProcessDefService == null) {
            return null;
        }
        try {
            return bpmProcessDefService.findByProcessKey(binding.getProcessDefKey());
        } catch (Exception e) {
            return null;
        }
    }

    /** 发起人必须在本租户内有效且启用；userQueryFacade 缺失时跳过（兼容单测装配）。 */
    private void requireActiveInitiator(StartCommand cmd) {
        if (userQueryFacade == null || cmd.getSubmitter() == null) {
            return;
        }
        List<Long> active = userQueryFacade.findActiveUserIds(List.of(cmd.getSubmitter()), cmd.getTenantId());
        if (active == null || active.isEmpty()) {
            log.error("流程发起人无效: submitter={}, tenantId={}, formKey={}",
                    cmd.getSubmitter(), cmd.getTenantId(), cmd.getFormKey());
            throw new BaseException(BpmErrorCode.INSTANCE_INITIATOR_INVALID);
        }
    }

    /**
     * 从表单提交数据中按候选键提取首个非空值放入流程变量
     *（动态宽表物理列为蛇形命名，同时兼容驼峰入参）。
     */
    private void putFirstNotBlank(Map<String, Object> variables, String targetKey,
                                  Map<String, Object> submittedData, String... candidateKeys) {
        if (submittedData == null) {
            return;
        }
        for (String key : candidateKeys) {
            Object value = submittedData.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                variables.put(targetKey, String.valueOf(value));
                return;
            }
        }
    }

    /**
     * 查询流程实例的首个待办 task，发布 TODO_CREATED 通知事件。
     * <p>
     * 骨架阶段为单节点审批，经 Facade 查询审批人待办并匹配 processInstanceId。
     * 若查不到 task（非预期）仅 warn 日志不阻断主流程。
     * </p>
     */
    private void publishTodoCreatedEvent(String processInstanceId, StartCommand cmd) {
        // 经 Facade 按流程实例精确查询刚创建的任务
        List<BpmTaskDTO> tasks = bpmTaskFacade.queryByProcessInstance(processInstanceId);
        BpmTaskDTO matchedTask = tasks.stream()
                .findFirst()
                .orElse(null);

        if (matchedTask == null) {
            log.warn("流程 {} 无待办 task，跳过 TODO_CREATED 通知", processInstanceId);
            return;
        }

        java.util.LinkedHashSet<String> recipientIds = new java.util.LinkedHashSet<>();
        if (matchedTask.getAssignee() != null) recipientIds.add(matchedTask.getAssignee());
        if (matchedTask.getCandidateUserIds() != null) recipientIds.addAll(matchedTask.getCandidateUserIds());
        for (String recipient : recipientIds) {
            Long approverId;
            try {
                approverId = Long.valueOf(recipient);
            } catch (NumberFormatException e) {
                log.warn("task participant 非数字格式: participant={}，跳过该 TODO_CREATED 通知", recipient);
                continue;
            }
            domainEventPublisher.publish(new BpmNotifyEvent(
                    BpmNotifyTrigger.TODO_CREATED,
                    approverId,
                    cmd.getTenantId(),
                    cmd.getSubmitter(),
                    matchedTask.getTaskId()));
        }
        log.debug("TODO_CREATED 事件已发布: taskId={}, recipients={}",
                matchedTask.getTaskId(), recipientIds);
    }
}
