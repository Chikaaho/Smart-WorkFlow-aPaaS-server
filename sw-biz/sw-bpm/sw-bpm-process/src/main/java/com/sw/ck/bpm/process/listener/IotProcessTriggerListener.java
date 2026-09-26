package com.sw.ck.bpm.process.listener;

import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.InstanceStatusEnum;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * IoT 事件发起流程（P21：规则/脚本 → 流程实例与表单数据）。
 * <p>
 * 监听 {@link IotProcessTriggerEvent}（AFTER_COMMIT + 异步），校验流程模板
 * 「已发布 + 允许 IoT 接入」，以受控租户系统身份发起流程（不伪装自然人），
 * 并经 {@link IotProcessTriggerFacade} 回写最终实例结果；
 * 受理不等于实例创建，未回写前触发记录保持 PENDING。
 * 幂等由 sw_iot_process_trigger.idempotent_key 唯一约束保证。
 * </p>
 */
@Component
public class IotProcessTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(IotProcessTriggerListener.class);

    /** IoT 自动发起使用的受控系统身份（不伪装自然人）。 */
    private static final String IOT_SYSTEM_ACTOR = "iot-system";

    private final ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider;
    private final ObjectProvider<BpmProcessDefService> processDefServiceProvider;
    private final ObjectProvider<IotProcessTriggerFacade> triggerFacadeProvider;
    private final ObjectProvider<com.sw.ck.iot.api.IotDeviceFacade> deviceFacadeProvider;
    private final ObjectProvider<com.sw.ck.iot.api.IotDeviceQueryFacade> deviceQueryFacadeProvider;
    private final ObjectProvider<BpmInstanceService> instanceServiceProvider;
    private final ObjectProvider<BpmTaskFacade> taskFacadeProvider;
    /** G3b：流程发起与业务实例记录必须落在同一提交边界（引擎命令按 REQUIRED 加入本事务）。 */
    private final ObjectProvider<org.springframework.transaction.PlatformTransactionManager> transactionManagerProvider;

    public IotProcessTriggerListener(ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider,
                                     ObjectProvider<BpmProcessDefService> processDefServiceProvider,
                                     ObjectProvider<IotProcessTriggerFacade> triggerFacadeProvider,
                                     ObjectProvider<BpmInstanceService> instanceServiceProvider,
                                     ObjectProvider<BpmTaskFacade> taskFacadeProvider,
                                     ObjectProvider<com.sw.ck.iot.api.IotDeviceFacade> deviceFacadeProvider,
                                     ObjectProvider<com.sw.ck.iot.api.IotDeviceQueryFacade> deviceQueryFacadeProvider,
                                     ObjectProvider<org.springframework.transaction.PlatformTransactionManager>
                                             transactionManagerProvider) {
        this.runtimeFacadeProvider = runtimeFacadeProvider;
        this.processDefServiceProvider = processDefServiceProvider;
        this.triggerFacadeProvider = triggerFacadeProvider;
        this.instanceServiceProvider = instanceServiceProvider;
        this.taskFacadeProvider = taskFacadeProvider;
        this.deviceFacadeProvider = deviceFacadeProvider;
        this.deviceQueryFacadeProvider = deviceQueryFacadeProvider;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIotTrigger(IotProcessTriggerEvent event) {
        // Phase 4：异步线程无登录态，显式从事件还原租户上下文，否则租户过滤查询/回写全部 fail-closed
        com.sw.ck.security.holder.LoginUser loginUser = new com.sw.ck.security.holder.LoginUser();
        loginUser.setTenantId(event.getTenantId());
        loginUser.setUserId(event.getConfiguredBy());
        com.sw.ck.security.holder.LoginUserHolder.set(loginUser);
        BpmRuntimeFacade runtimeFacade = runtimeFacadeProvider.getIfAvailable();
        BpmProcessDefService processDefService = processDefServiceProvider.getIfAvailable();
        IotProcessTriggerFacade triggerFacade = triggerFacadeProvider.getIfAvailable();
        if (runtimeFacade == null || processDefService == null) {
            markFailed(triggerFacade, event.getIdempotentKey(), "流程运行时未装配");
            return;
        }
        try {
            // Phase 4 幂等守卫：恢复调度在租约回收后会重投同一触发（幂等键不变），
            // 而"引擎已发起 + 业务实例未落库"的窗口会导致重复发起。业务实例记录按
            // iot-{triggerId} 稳定派生，已存在即认定为已完成并发起，直接回写终态。
            BpmInstanceService guardService = instanceServiceProvider.getIfAvailable();
            if (guardService != null && event.getTriggerId() != null) {
                java.util.Optional<BpmInstance> existing =
                        guardService.findByBusinessKey("iot-" + event.getTriggerId());
                if (existing.isPresent() && existing.get().getProcessInstanceId() != null
                        && !existing.get().getProcessInstanceId().isBlank()) {
                    log.info("IoT 触发幂等跳过（已存在流程实例）: triggerId={}, idempotentKey={}, processInstanceId={}",
                            event.getTriggerId(), event.getIdempotentKey(), existing.get().getProcessInstanceId());
                    if (triggerFacade != null) {
                        writeBackTriggerResult(triggerFacade, event.getIdempotentKey(),
                                existing.get().getProcessInstanceId(), null, "幂等跳过复用既有实例");
                    }
                    return;
                }
            }
            BpmProcessDef def = processDefService.findByProcessKey(event.getProcessTemplateKey());
            if (def == null || !"PUBLISHED".equals(def.getStatus())
                    || def.getProcessDefinitionId() == null || def.getProcessDefinitionId().isBlank()) {
                markFailed(triggerFacade, event.getIdempotentKey(),
                        "流程模板未发布: " + event.getProcessTemplateKey());
                return;
            }
            if (!Boolean.TRUE.equals(def.getIotAccessEnabled())) {
                markFailed(triggerFacade, event.getIdempotentKey(),
                        "流程模板未开启 IoT 接入: " + event.getProcessTemplateKey());
                return;
            }
            Map<String, Object> variables = new HashMap<>();
            variables.put("formData", event.getFormData() == null ? Map.of() : event.getFormData());
            variables.put("submitter", IOT_SYSTEM_ACTOR);
            variables.put("iotTriggerSource", event.getTriggerSource());
            variables.put("iotDeviceId", event.getDeviceId());
            variables.put("iotRuleId", event.getRuleId());
            if (event.getContext() != null) {
                variables.put("iotContext", event.getContext());
            }
            // G3b：引擎实例创建与业务实例记录必须原子。历史实现分属两个提交边界，
            // "引擎已创建、业务实例尚未落库"的崩溃窗口会留下 engine-only 孤儿；
            // 这里把二者放进同一事务，引擎命令按 REQUIRED 加入（引擎与应用共用 DataSource/事务管理器）。
            // empty = 该 key/租户无已发布定义（原 FlowableObjectNotFoundException 缺失路径）：
            // 发起目标缺失属真实失败，交由下方统一失败路径回写触发记录，不得静默成功。
            String processInstanceId = startProcessWithInstanceRecord(
                    runtimeFacade, event, def, variables);
            // A6 设备动作：三类设备来源（FIXED/FORM_FIELD/VARIABLE），运行时重校验
            String actionError = runDeviceAction(def, event, processInstanceId);
            if (actionError != null && !"__MANUAL_PENDING__".equals(actionError)) {
                // failurePolicy=BLOCK：命令失败回写触发记录，不伪装成功
                markFailed(triggerFacade, event.getIdempotentKey(), "A6 设备命令失败: " + actionError);
                return;
            }
            if ("__MANUAL_PENDING__".equals(actionError)) {
                // MANUAL：触发记录保持 PENDING（人工处理中），不写 FAILED 也不写 SUCCESS
                log.info("A6 MANUAL 策略：触发记录保持 PENDING 人工处理: idempotentKey={}", event.getIdempotentKey());
                return;
            }
            if (triggerFacade != null) {
                writeBackTriggerResult(triggerFacade, event.getIdempotentKey(), processInstanceId, null,
                        "流程发起成功回写");
            }
            log.info("IoT 触发流程已发起: templateKey={}, processInstanceId={}, idempotentKey={}",
                    event.getProcessTemplateKey(), processInstanceId, event.getIdempotentKey());
        } catch (Exception e) {
            log.error("IoT 触发流程失败: templateKey={}, key={}, error={}",
                    event.getProcessTemplateKey(), event.getIdempotentKey(), e.getMessage(), e);
            markFailed(triggerFacade, event.getIdempotentKey(), e.getMessage());
        }
    }

    /**
     * A6：按流程模板设备动作配置解析设备并下发（单命令；全链关联 flowInstanceId）。
     *
     * @return null=成功或不适用；否则失败原因（BLOCK 策略）
     */
    private String runDeviceAction(BpmProcessDef def, IotProcessTriggerEvent event,
                                   String processInstanceId) {
        String configJson = def.getIotDeviceActionJson();
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        com.alibaba.fastjson2.JSONObject config;
        try {
            config = com.alibaba.fastjson2.JSON.parseObject(configJson);
        } catch (Exception e) {
            return "设备动作配置非法 JSON";
        }
        if (!Boolean.TRUE.equals(config.getBoolean("enabled"))) {
            return null;
        }
        IotDeviceFacade deviceFacade = deviceFacadeProvider.getIfAvailable();
        if (deviceFacade == null) {
            return "IoT 设备门面未装配";
        }
        String source = config.getString("deviceSource");
        String deviceKey;
        switch (source == null ? "FIXED" : source) {
            case "FIXED" -> {
                Long fixedId = config.getLong("deviceId");
                java.util.Optional<String> fixedDeviceKey = resolveDeviceKeyById(fixedId);
                if (fixedDeviceKey.isEmpty()) {
                    return handleFailure(config, "设计时固定设备不存在: deviceId=" + fixedId);
                }
                deviceKey = fixedDeviceKey.orElseThrow();
            }
            case "FORM_FIELD" -> {
                Object v = event.getFormData() == null ? null
                        : event.getFormData().get(config.getString("deviceField"));
                deviceKey = v == null ? null : String.valueOf(v);
                if (deviceKey == null || deviceKey.isBlank()) {
                    return handleFailure(config, "表单字段缺少设备标识: " + config.getString("deviceField"));
                }
            }
            case "VARIABLE" -> {
                Object v = event.getContext() == null ? null
                        : event.getContext().get(config.getString("variableName"));
                deviceKey = v == null ? null : String.valueOf(v);
                if (deviceKey == null || deviceKey.isBlank()) {
                    return handleFailure(config, "流程变量缺少设备标识: " + config.getString("variableName"));
                }
            }
            default -> {
                return "未知设备来源: " + source;
            }
        }
        String paramField = config.getString("paramField");
        Object param = paramField == null ? null
                : (event.getFormData() == null ? null : event.getFormData().get(paramField));
        String commandKey = config.getString("commandKey") == null ? "iot_action" : config.getString("commandKey");
        // empty = 设备/连接/下行主题不合格（不适用）：交由 failurePolicy 处理，不伪造受理成功
        java.util.Optional<Long> commandId = deviceFacade.dispatchByDeviceKey(event.getTenantId(), deviceKey,
                commandKey, param == null ? "{}" : com.alibaba.fastjson2.JSON.toJSONString(param),
                processInstanceId, source);
        if (commandId.isEmpty()) {
            return handleFailure(config, "运行时校验拒绝或无下行主题: deviceKey=" + deviceKey);
        }
        log.info("A6 设备命令已生成（单命令）: commandId={}, deviceKey={}, flowInstanceId={}",
                commandId.orElseThrow(), deviceKey, processInstanceId);
        return null;
    }

    /**
     * A6 失败策略统一处理：BLOCK=回写失败；CONTINUE=记录后继续；MANUAL=保持 PENDING 人工处理。
     *
     * @return null（继续成功路径）或失败原因（BLOCK 回写）
     */
    private String handleFailure(com.alibaba.fastjson2.JSONObject config, String reason) {
        String policy = config.getString("failurePolicy");
        if ("CONTINUE".equals(policy)) {
            log.warn("A6 失败策略 CONTINUE（记录后继续）: {}", reason);
            return null;
        }
        if ("MANUAL".equals(policy)) {
            log.warn("A6 失败策略 MANUAL（触发记录保持 PENDING 人工处理）: {}", reason);
            return "__MANUAL_PENDING__";
        }
        return reason;
    }

    private java.util.Optional<String> resolveDeviceKeyById(Long deviceId) {
        com.sw.ck.iot.api.IotDeviceQueryFacade queryFacade = deviceQueryFacadeProvider.getIfAvailable();
        if (queryFacade == null) {
            return java.util.Optional.empty();
        }
        return queryFacade.getDeviceKeyById(deviceId);
    }

    /**
     * 在单一事务内完成"引擎发起 + 业务实例记录"。
     *
     * <p>二者必须同提交或同回滚：只发起未记录会形成 engine-only 孤儿（重启后既无业务实例也
     * 无法审计终态），只记录未发起则形成幻觉实例。事务管理器缺失时拒绝执行而不是降级，
     * 因为降级会重新引入本方法要消除的崩溃窗口。</p>
     */
    private String startProcessWithInstanceRecord(BpmRuntimeFacade runtimeFacade, IotProcessTriggerEvent event,
                                                  BpmProcessDef def, Map<String, Object> variables) {
        org.springframework.transaction.PlatformTransactionManager transactionManager =
                transactionManagerProvider.getIfAvailable();
        if (transactionManager == null) {
            throw new IllegalStateException("事务管理器未装配：IoT 流程发起与业务实例记录无法保持同一提交边界");
        }
        org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return template.execute(status -> {
            String processInstanceId = runtimeFacade.startProcess(
                            event.getProcessTemplateKey(),
                            event.getIdempotentKey(),
                            variables,
                            String.valueOf(event.getTenantId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "流程模板无已发布定义: " + event.getProcessTemplateKey()));
            BpmInstanceService instanceService = instanceServiceProvider.getIfAvailable();
            if (instanceService != null) {
                boolean processActive = true;
                BpmTaskFacade taskFacade = taskFacadeProvider.getIfAvailable();
                if (taskFacade != null) {
                    java.util.Optional<Boolean> activeDecision = taskFacade.isProcessActive(processInstanceId);
                    // empty = 实例标识缺失（此处实例刚创建，契约不产生）：保持原有乐观默认 true
                    if (activeDecision.isPresent()) {
                        processActive = activeDecision.get();
                    }
                }
                BpmInstance instance = new BpmInstance();
                instance.setProcessInstanceId(processInstanceId);
                instance.setProcessDefKey(event.getProcessTemplateKey());
                instance.setBusinessKey("iot-" + event.getTriggerId());
                instance.setFormKey(def.getFormKey());
                // 受控租户系统身份：IoT 自动发起不伪装自然人
                instance.setInitiatorId(0L);
                instance.setStatus(processActive
                        ? InstanceStatusEnum.RUNNING.getCode()
                        : InstanceStatusEnum.APPROVED.getCode());
                instanceService.save(instance);
            }
            return processInstanceId;
        });
    }

    private void markFailed(IotProcessTriggerFacade triggerFacade, String idempotentKey, String error) {
        if (triggerFacade != null) {
            String safe = error == null ? null
                    : (error.length() <= 480 ? error : error.substring(0, 480));
            writeBackTriggerResult(triggerFacade, idempotentKey, null, safe, "发起失败回写");
        }
    }

    /**
     * 回写触发记录终态并显式消费契约结果。
     *
     * <p>{@code Optional<Boolean>} 三层语义：true = 本次改写终态；false = 命中终态保护、
     * 合法幂等跳过（迟到写回或恢复调度重投）；empty = 无该幂等键的触发记录。
     * 回写失败不影响已经成立的流程发起，只记录事实。</p>
     */
    private void writeBackTriggerResult(IotProcessTriggerFacade triggerFacade, String idempotentKey,
                                        String processInstanceId, String error, String scene) {
        triggerFacade.markTriggerResult(idempotentKey, processInstanceId, error)
                .ifPresentOrElse(
                        changed -> log.info("IoT 触发写回: scene={}, idempotentKey={}, terminalStateChanged={}",
                                scene, idempotentKey, changed),
                        () -> log.warn("IoT 触发写回未命中记录（无该幂等键的触发记录）: scene={}, idempotentKey={}",
                                scene, idempotentKey));
    }
}
