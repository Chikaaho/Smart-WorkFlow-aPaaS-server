package com.sw.ck.iot.agentbridge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.agent.event.AgentGraphExecutionTerminalEvent;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRule;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRuleMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 图执行终态承接（0.2.4，失败即升级）。
 * <p>
 * 监听 {@link AgentGraphExecutionTerminalEvent}（普通进程事件，无事务上下文）：
 * 命中桥接触发记录（agent_execution_id）时回写触发终态；
 * 执行 FAILED 且规则配置了失败升级流程模板时，在同一事务内生成 FLOW 升级触发记录
 * （幂等键 iot-esc-{executionId}）并发布流程发起事件，复用既有 IoT→流程链路
 * （含恢复调度）。CANCELLED/INTERRUPTED 不自动升级——中断语义按 0.2.3 口径
 * 「停止自动重放，人工核实外部副作用」，避免重复副作用。
 * </p>
 */
@Component
public class AgentExecutionOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionOutcomeListener.class);

    private final IotProcessTriggerMapper triggerMapper;
    private final IotEventRuleMapper ruleMapper;
    private final IotDeviceMapper deviceMapper;
    private final DomainEventPublisher eventPublisher;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    public AgentExecutionOutcomeListener(IotProcessTriggerMapper triggerMapper,
                                         IotEventRuleMapper ruleMapper,
                                         IotDeviceMapper deviceMapper,
                                         DomainEventPublisher eventPublisher,
                                         ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.triggerMapper = triggerMapper;
        this.ruleMapper = ruleMapper;
        this.deviceMapper = deviceMapper;
        this.eventPublisher = eventPublisher;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    @EventListener
    public void onTerminal(AgentGraphExecutionTerminalEvent event) {
        if (event == null || event.getExecutionId() == null || event.getTenantId() == null) {
            return;
        }
        IotProcessTrigger trigger = triggerMapper.selectOne(
                new LambdaQueryWrapper<IotProcessTrigger>()
                        .eq(IotProcessTrigger::getAgentExecutionId, event.getExecutionId())
                        .eq(IotProcessTrigger::getTenantId, event.getTenantId())
                        .last("LIMIT 1"));
        if (trigger == null || !"AGENT".equals(trigger.getActionType())) {
            // 非桥接触发的执行（页面手动执行等）不承接
            return;
        }
        // 事件来自执行工作线程（无登录态）：还原租户上下文供后续查询与事件发布消费方使用
        LoginUser loginUser = new LoginUser();
        loginUser.setTenantId(event.getTenantId());
        loginUser.setUserId(parseUserId(event.getOperatorId()));
        LoginUserHolder.set(loginUser);
        try {
            handleTerminal(event, trigger);
        } catch (Exception e) {
            log.error("Agent 终态承接失败: executionId={}, status={}, error={}",
                    event.getExecutionId(), event.getStatus(), e.getMessage(), e);
        } finally {
            LoginUserHolder.clear();
        }
    }

    private void handleTerminal(AgentGraphExecutionTerminalEvent event, IotProcessTrigger trigger) {
        switch (event.getStatus() == null ? "" : event.getStatus()) {
            case "SUCCESS" -> markTerminal(trigger, "SUCCESS", null);
            case "FAILED" -> {
                markTerminal(trigger, "FAILED", event.getErrorCategory()
                        + (event.getErrorMessage() == null ? "" : ": " + event.getErrorMessage()));
                escalateIfConfigured(event, trigger);
            }
            case "CANCELLED" -> markTerminal(trigger, "FAILED", "执行已取消");
            case "INTERRUPTED" -> markTerminal(trigger, "FAILED",
                    "服务重启中断，执行效果无法确认，请人工核实");
            default -> log.warn("Agent 终态未知状态，不承接: executionId={}, status={}",
                    event.getExecutionId(), event.getStatus());
        }
    }

    private void markTerminal(IotProcessTrigger trigger, String status, String error) {
        IotProcessTrigger patch = new IotProcessTrigger();
        patch.setId(trigger.getId());
        patch.setStatus(status);
        patch.setError(error);
        triggerMapper.update(patch,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotProcessTrigger>lambdaUpdate()
                        .eq(IotProcessTrigger::getId, trigger.getId())
                        .in(IotProcessTrigger::getStatus, "PENDING", "PROCESSING"));
    }

    /** 失败升级：按规则配置的升级流程模板发起 FLOW 触发（同事务插入 + 提交后发布）。 */
    private void escalateIfConfigured(AgentGraphExecutionTerminalEvent event, IotProcessTrigger trigger) {
        IotEventRule rule = ruleMapper.selectById(trigger.getRuleId());
        if (rule == null || rule.getAgentFailureTemplateKey() == null
                || rule.getAgentFailureTemplateKey().isBlank()) {
            log.info("Agent 研判失败且未配置升级流程，仅回写触发失败: triggerId={}, executionId={}",
                    trigger.getId(), event.getExecutionId());
            return;
        }
        PlatformTransactionManager transactionManager = transactionManagerProvider.getIfAvailable();
        if (transactionManager == null) {
            log.error("事务管理器未装配，无法生成升级触发: executionId={}", event.getExecutionId());
            return;
        }
        IotDevice device = trigger.getDeviceId() == null ? null : deviceMapper.selectById(trigger.getDeviceId());
        Map<String, Object> facts = rebuildFacts(rule, trigger);
        Map<String, Object> formData = buildEscalationFormData(rule, device, facts,
                event.getExecutionId(), event.getErrorCategory(), event.getErrorMessage());
        String idempotentKey = "iot-esc-" + event.getExecutionId();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Boolean published = template.execute(status -> {
            IotProcessTrigger escalation = new IotProcessTrigger();
            escalation.setRuleId(rule.getId());
            escalation.setDeviceId(trigger.getDeviceId());
            escalation.setIdempotentKey(idempotentKey);
            escalation.setStatus("PENDING");
            escalation.setFormSnapshot(JSON.toJSONString(formData));
            escalation.setTriggerTime(java.time.LocalDateTime.now());
            escalation.setActionType("FLOW");
            escalation.setProcessTemplateKey(rule.getAgentFailureTemplateKey());
            escalation.setTriggerSource("AGENT_FAIL");
            escalation.setConfiguredBy(rule.getCreateBy());
            escalation.setRetryCount(0);
            try {
                com.sw.ck.common.persistence.IdempotentInsert.execute(
                        transactionManager, () -> triggerMapper.insert(escalation));
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                log.info("升级触发幂等命中，跳过重复升级: key={}", idempotentKey);
                return Boolean.FALSE;
            }
            IotProcessTriggerEvent flowEvent = new IotProcessTriggerEvent();
            flowEvent.setTenantId(event.getTenantId());
            flowEvent.setTriggerId(escalation.getId());
            flowEvent.setRuleId(rule.getId());
            flowEvent.setDeviceId(trigger.getDeviceId());
            flowEvent.setProcessTemplateKey(rule.getAgentFailureTemplateKey());
            flowEvent.setIdempotentKey(idempotentKey);
            flowEvent.setTriggerSource("AGENT_FAIL");
            flowEvent.setConfiguredBy(rule.getCreateBy());
            flowEvent.setActionType("FLOW");
            flowEvent.setFormData(formData);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("ruleName", rule.getName());
            context.put("agentExecutionId", event.getExecutionId());
            context.put("agentErrorCategory", event.getErrorCategory());
            context.put("agentErrorMessage", event.getErrorMessage());
            if (device != null) {
                context.put("deviceKey", device.getDeviceKey());
                context.put("deviceName", device.getName());
            }
            flowEvent.setContext(context);
            eventPublisher.publish(flowEvent);
            return Boolean.TRUE;
        });
        if (Boolean.TRUE.equals(published)) {
            log.info("Agent 研判失败已升级发起流程: templateKey={}, executionId={}, key={}",
                    rule.getAgentFailureTemplateKey(), event.getExecutionId(), idempotentKey);
        }
    }

    /**
     * 从 Agent 输入映射与触发快照重建原始事实（value/preValue/reportTime/payload），
     * 供升级表单映射的 PROPERTY_VALUE 等来源取值。
     */
    private Map<String, Object> rebuildFacts(IotEventRule rule, IotProcessTrigger trigger) {
        Map<String, Object> facts = new HashMap<>();
        Map<String, Object> snapshot = parseSnapshot(trigger.getFormSnapshot());
        if (rule.getAgentInputMappingJson() == null || snapshot.isEmpty()) {
            return facts;
        }
        try {
            JSONArray mappings = JSON.parseArray(rule.getAgentInputMappingJson());
            if (mappings == null) {
                return facts;
            }
            Map<String, String> factSourceOf = Map.of(
                    "PROPERTY_VALUE", "value",
                    "PREV_VALUE", "preValue",
                    "REPORT_TIME", "reportTime",
                    "EVENT_PAYLOAD", "payload");
            for (int i = 0; i < mappings.size(); i++) {
                JSONObject mapping = mappings.getJSONObject(i);
                String source = mapping.getString("source");
                String factKey = factSourceOf.get(source);
                if (factKey != null) {
                    Object value = snapshot.get(mapping.getString("field"));
                    if (value != null) {
                        facts.put(factKey, value);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("升级事实重建失败（按缺省继续）: triggerId={}, error={}",
                    trigger.getId(), e.getMessage());
        }
        return facts;
    }

    private Map<String, Object> parseSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return Map.of();
        }
        try {
            JSONObject parsed = JSON.parseObject(snapshotJson);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 升级表单映射：source 词汇表同流程映射，另支持 AGENT_EXECUTION_ID / AGENT_ERROR。 */
    private Map<String, Object> buildEscalationFormData(IotEventRule rule, IotDevice device,
                                                        Map<String, Object> facts, Long executionId,
                                                        String errorCategory, String errorMessage) {
        Map<String, Object> formData = new HashMap<>();
        String mappingJson = rule.getFormMappingJson();
        if (mappingJson != null && !mappingJson.isBlank()) {
            try {
                JSONArray mappings = JSON.parseArray(mappingJson);
                if (mappings != null) {
                    for (int i = 0; i < mappings.size(); i++) {
                        JSONObject mapping = mappings.getJSONObject(i);
                        String field = mapping.getString("field");
                        String source = mapping.getString("source");
                        Object value = switch (source == null ? "" : source) {
                            case "DEVICE_ID" -> device == null ? null : device.getId();
                            case "DEVICE_KEY" -> device == null ? null : device.getDeviceKey();
                            case "DEVICE_NAME" -> device == null ? null : device.getName();
                            case "PRODUCT_ID" -> device == null ? null : device.getProductRefId();
                            case "PROPERTY_VALUE" -> facts.get("value");
                            case "PREV_VALUE" -> facts.get("preValue");
                            case "EVENT_PAYLOAD" -> facts.get("payload");
                            case "REPORT_TIME" -> facts.get("reportTime");
                            case "RULE_NAME" -> rule.getName();
                            case "AGENT_EXECUTION_ID" -> executionId;
                            case "AGENT_ERROR" -> errorCategory == null ? null
                                    : (errorMessage == null || errorMessage.isBlank()
                                    ? errorCategory : errorCategory + ": " + errorMessage);
                            default -> mapping.get("fixed");
                        };
                        if (value != null || mapping.getBooleanValue("required")) {
                            formData.put(field, value);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("升级表单映射解析失败（按空表单继续）: ruleId={}, error={}",
                        rule.getId(), e.getMessage());
            }
        }
        return formData;
    }

    private Long parseUserId(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(operatorId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
