package com.sw.ck.iot.event;

import java.io.Serializable;
import java.util.Map;

/**
 * IoT → 流程 联动事件（从 sw-basic-iot api 包发布，经 DomainEventPublisher）。
 * <p>
 * 消费方（sw-bpm-process）监听后以受控租户系统身份发起流程，
 * 并经 {@code IotProcessTriggerFacade} 回写最终实例结果；
 * 受理不等于实例创建，未回写前保持 PENDING。
 * </p>
 */
public class IotProcessTriggerEvent implements Serializable {

    private Long tenantId;
    private Long ruleId;
    private Long scriptId;
    private Long deviceId;
    private String processTemplateKey;
    private String idempotentKey;
    private String triggerSource;
    private Long configuredBy;
    private Long triggerId;
    private Map<String, Object> formData;
    private Map<String, Object> context;

    /** 动作类型：FLOW（默认，流程发起消费）/ AGENT（Agent 图执行消费） */
    private String actionType;

    /** AGENT 动作的目标已发布图 key */
    private String agentGraphKey;

    /** AGENT 动作的执行输入（映射结果对象，序列化为初始输入文本） */
    private Map<String, Object> agentInput;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Long getScriptId() {
        return scriptId;
    }

    public void setScriptId(Long scriptId) {
        this.scriptId = scriptId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
    }

    public String getProcessTemplateKey() {
        return processTemplateKey;
    }

    public void setProcessTemplateKey(String processTemplateKey) {
        this.processTemplateKey = processTemplateKey;
    }

    public String getIdempotentKey() {
        return idempotentKey;
    }

    public void setIdempotentKey(String idempotentKey) {
        this.idempotentKey = idempotentKey;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }

    public Long getConfiguredBy() {
        return configuredBy;
    }

    public void setConfiguredBy(Long configuredBy) {
        this.configuredBy = configuredBy;
    }

    public Map<String, Object> getFormData() {
        return formData;
    }

    public void setFormData(Map<String, Object> formData) {
        this.formData = formData;
    }

    public Long getTriggerId() {
        return triggerId;
    }

    public void setTriggerId(Long triggerId) {
        this.triggerId = triggerId;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getAgentGraphKey() {
        return agentGraphKey;
    }

    public void setAgentGraphKey(String agentGraphKey) {
        this.agentGraphKey = agentGraphKey;
    }

    public Map<String, Object> getAgentInput() {
        return agentInput;
    }

    public void setAgentInput(Map<String, Object> agentInput) {
        this.agentInput = agentInput;
    }
}
