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

    public IotProcessTriggerListener(ObjectProvider<BpmRuntimeFacade> runtimeFacadeProvider,
                                     ObjectProvider<BpmProcessDefService> processDefServiceProvider,
                                     ObjectProvider<IotProcessTriggerFacade> triggerFacadeProvider,
                                     ObjectProvider<BpmInstanceService> instanceServiceProvider,
                                     ObjectProvider<BpmTaskFacade> taskFacadeProvider,
                                     ObjectProvider<com.sw.ck.iot.api.IotDeviceFacade> deviceFacadeProvider,
                                     ObjectProvider<com.sw.ck.iot.api.IotDeviceQueryFacade> deviceQueryFacadeProvider) {
        this.runtimeFacadeProvider = runtimeFacadeProvider;
        this.processDefServiceProvider = processDefServiceProvider;
        this.triggerFacadeProvider = triggerFacadeProvider;
        this.instanceServiceProvider = instanceServiceProvider;
        this.taskFacadeProvider = taskFacadeProvider;
        this.deviceFacadeProvider = deviceFacadeProvider;
        this.deviceQueryFacadeProvider = deviceQueryFacadeProvider;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIotTrigger(IotProcessTriggerEvent event) {
        BpmRuntimeFacade runtimeFacade = runtimeFacadeProvider.getIfAvailable();
        BpmProcessDefService processDefService = processDefServiceProvider.getIfAvailable();
        IotProcessTriggerFacade triggerFacade = triggerFacadeProvider.getIfAvailable();
        if (runtimeFacade == null || processDefService == null) {
            markFailed(triggerFacade, event.getIdempotentKey(), "流程运行时未装配");
            return;
        }
        try {
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
            String processInstanceId = runtimeFacade.startProcess(
                    event.getProcessTemplateKey(),
                    event.getIdempotentKey(),
                    variables,
                    String.valueOf(event.getTenantId()));
            // 业务实例记录落库（与 ProcessStartService 同语义）：引擎实例 ≠ 业务记录，
            // 二者都存在才算"真实流程实例"，受理不等于实例创建
            BpmInstanceService instanceService = instanceServiceProvider.getIfAvailable();
            if (instanceService != null) {
                boolean processActive = true;
                BpmTaskFacade taskFacade = taskFacadeProvider.getIfAvailable();
                if (taskFacade != null) {
                    processActive = taskFacade.isProcessActive(processInstanceId);
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
                triggerFacade.markTriggerResult(event.getIdempotentKey(), processInstanceId, null);
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
                deviceKey = resolveDeviceKeyById(fixedId);
                if (deviceKey == null) {
                    return handleFailure(config, "设计时固定设备不存在: deviceId=" + fixedId);
                }
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
        Long commandId = deviceFacade.dispatchByDeviceKey(event.getTenantId(), deviceKey, commandKey,
                param == null ? "{}" : com.alibaba.fastjson2.JSON.toJSONString(param), processInstanceId,
                source);
        if (commandId == null) {
            return handleFailure(config, "运行时校验拒绝或无下行主题: deviceKey=" + deviceKey);
        }
        log.info("A6 设备命令已生成（单命令）: commandId={}, deviceKey={}, flowInstanceId={}",
                commandId, deviceKey, processInstanceId);
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

    private String resolveDeviceKeyById(Long deviceId) {
        com.sw.ck.iot.api.IotDeviceQueryFacade queryFacade = deviceQueryFacadeProvider.getIfAvailable();
        if (queryFacade == null) {
            return null;
        }
        return queryFacade.getDeviceKeyById(deviceId);
    }

    private void markFailed(IotProcessTriggerFacade triggerFacade, String idempotentKey, String error) {
        if (triggerFacade != null) {
            String safe = error == null ? null
                    : (error.length() <= 480 ? error : error.substring(0, 480));
            triggerFacade.markTriggerResult(idempotentKey, null, safe);
        }
    }
}
