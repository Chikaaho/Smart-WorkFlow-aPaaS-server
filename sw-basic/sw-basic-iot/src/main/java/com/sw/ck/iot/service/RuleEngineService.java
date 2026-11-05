package com.sw.ck.iot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRule;
import com.sw.ck.iot.entity.IotProcessTrigger;
import com.sw.ck.iot.entity.IotPropertyRecord;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotEventRuleMapper;
import com.sw.ck.iot.mapper.IotProcessTriggerMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备事件规则引擎。
 * <p>
 * 评估已发布规则的属性变化/阈值/事件条件，执行防抖（debounce）与冷却（cooldown），
 * 按稳定幂等键生成流程触发记录后经领域事件交给流程侧发起；
 * 受理成功与否以流程侧回写为准，重复消息/重试不生成重复触发记录。
 * </p>
 */
@Service
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    /** 连续次数计数（单节点内存态；条件 {continuousCount}）。 */
    private final Map<Long, Integer> continuousCounters = new ConcurrentHashMap<>();

    private final IotEventRuleMapper ruleMapper;
    private final IotProcessTriggerMapper triggerMapper;
    private final IotPropertyRecordMapper propertyRecordMapper;
    private final DomainEventPublisher eventPublisher;
    private IotAuditService auditService;

    /** 幂等插入的保存点包装所需（可选装配：单元测试可缺省）。 */
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    public RuleEngineService(IotEventRuleMapper ruleMapper,
                             IotProcessTriggerMapper triggerMapper,
                             IotPropertyRecordMapper propertyRecordMapper,
                             DomainEventPublisher eventPublisher) {
        this.ruleMapper = ruleMapper;
        this.triggerMapper = triggerMapper;
        this.propertyRecordMapper = propertyRecordMapper;
        this.eventPublisher = eventPublisher;
    }

    @Autowired
    public void setAuditService(IotAuditService auditService) {
        this.auditService = auditService;
    }

    @Autowired(required = false)
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * 属性上报入口。
     */
    public void onProperty(IotDevice device, String propertyId, String newValueJson,
                           String preValueJson, LocalDateTime reportTime, String dedupKey) {
        List<IotEventRule> rules = publishedRules(device.getId());
        for (IotEventRule rule : rules) {
            if (!"PROPERTY_CHANGED".equals(rule.getRuleType())
                    && !"THRESHOLD".equals(rule.getRuleType())) {
                continue;
            }
            try {
                if (!evaluate(rule, propertyId, newValueJson, preValueJson)) {
                    continuousCounters.remove(rule.getId());
                    continue;
                }
            } catch (Exception e) {
                log.warn("规则条件评估失败: ruleId={}, error={}", rule.getId(), e.getMessage());
                continue;
            }
            fire(device, rule, Map.of(
                    "propertyId", propertyId,
                    "value", newValueJson,
                    "preValue", preValueJson == null ? "" : preValueJson,
                    "reportTime", reportTime.toString()
            ), dedupKey);
        }
    }

    /**
     * 事件上报入口。
     */
    public void onEvent(IotDevice device, String eventId, String payloadJson, String dedupKey) {
        List<IotEventRule> rules = publishedRules(device.getId());
        for (IotEventRule rule : rules) {
            if (!"EVENT_OCCUR".equals(rule.getRuleType())) {
                continue;
            }
            JSONObject condition = JSON.parseObject(rule.getConditionJson());
            String target = condition.getString("eventId");
            if (target != null && !target.equals(eventId)) {
                continue;
            }
            fire(device, rule, Map.of(
                    "eventId", eventId,
                    "payload", payloadJson == null ? "" : payloadJson
            ), dedupKey);
        }
    }

    /**
     * 连接状态变化入口。
     */
    public void onConnectionChanged(IotDevice device, boolean online) {
        String type = online ? "ONLINE" : "OFFLINE";
        List<IotEventRule> rules = publishedRules(device.getId());
        for (IotEventRule rule : rules) {
            if (!type.equals(rule.getRuleType())) {
                continue;
            }
            fire(device, rule, Map.of("online", online), null);
        }
    }

    // ---------------- 评估 ----------------

    private boolean evaluate(IotEventRule rule, String propertyId, String newValueJson, String preValueJson) {
        JSONObject condition = JSON.parseObject(rule.getConditionJson());
        String targetProperty = condition.getString("propertyId");
        if (targetProperty != null && !targetProperty.equals(propertyId)) {
            return false;
        }
        String op = condition.getString("op");
        Object newValue = JSON.parse(newValueJson);
        Double value = toDouble(newValue);
        return switch (op == null ? "CHANGED" : op) {
            case "CHANGED" -> newValueJson == null || !newValueJson.equals(preValueJson);
            case "FIRST_REPORT" -> preValueJson == null;
            case "GT" -> compare(value, condition.getDouble("threshold")) > 0;
            case "GTE" -> compare(value, condition.getDouble("threshold")) >= 0;
            case "LT" -> compare(value, condition.getDouble("threshold")) < 0;
            case "LTE" -> compare(value, condition.getDouble("threshold")) <= 0;
            case "EQ" -> compare(value, condition.getDouble("threshold")) == 0;
            case "BETWEEN" -> compare(value, condition.getDouble("threshold")) >= 0
                    && compare(value, condition.getDouble("threshold2")) <= 0;
            case "OUTSIDE" -> compare(value, condition.getDouble("threshold")) < 0
                    || compare(value, condition.getDouble("threshold2")) > 0;
            default -> throw new IllegalArgumentException("未知操作符: " + op);
        };
    }

    private int compare(Double a, Double b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("阈值比较要求属性为数值且阈值已配置");
        }
        return Double.compare(a, b);
    }

    private Double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    // ---------------- 触发 ----------------

    private synchronized void fire(IotDevice device, IotEventRule rule,
                                   Map<String, Object> eventFacts, String dedupKey) {
        LocalDateTime now = LocalDateTime.now();
        // 防抖：距上次触发不足 debounce 的丢弃
        if (rule.getDebounceMs() != null && rule.getDebounceMs() > 0 && rule.getLastFiredTime() != null) {
            long since = ChronoUnit.MILLIS.between(rule.getLastFiredTime(), now);
            if (since < rule.getDebounceMs()) {
                log.debug("规则防抖丢弃: ruleId={}, sinceMs={}", rule.getId(), since);
                return;
            }
        }
        // 冷却：与防抖相同实现域，独立配置
        if (rule.getCooldownMs() != null && rule.getCooldownMs() > 0 && rule.getLastFiredTime() != null) {
            long since = ChronoUnit.MILLIS.between(rule.getLastFiredTime(), now);
            if (since < rule.getCooldownMs()) {
                log.debug("规则冷却中: ruleId={}, sinceMs={}", rule.getId(), since);
                return;
            }
        }
        // 连续次数
        int required = rule.getContinuousCount() == null ? 1 : rule.getContinuousCount();
        if (required > 1) {
            int count = continuousCounters.merge(rule.getId(), 1, Integer::sum);
            if (count < required) {
                return;
            }
        }
        continuousCounters.remove(rule.getId());

        boolean flowAction = rule.getProcessEnabled() != null && rule.getProcessEnabled() == 1
                && rule.getProcessTemplateKey() != null && !rule.getProcessTemplateKey().isBlank();
        boolean agentAction = rule.getAgentEnabled() != null && rule.getAgentEnabled() == 1
                && rule.getAgentGraphKey() != null && !rule.getAgentGraphKey().isBlank();
        if (!flowAction && !agentAction) {
            // 无任何联动动作则只更新触发时间
            touchFired(rule, now);
            return;
        }

        if (flowAction) {
            fireFlow(device, rule, eventFacts, dedupKey, now);
        }
        if (agentAction) {
            fireAgent(device, rule, eventFacts, dedupKey, now);
        }
        touchFired(rule, now);
    }

    /** FLOW 动作：按既有链路生成流程触发记录并发布流程发起事件。 */
    private void fireFlow(IotDevice device, IotEventRule rule, Map<String, Object> eventFacts,
                          String dedupKey, LocalDateTime now) {
        Map<String, Object> formData = buildFormData(rule, device, eventFacts);
        String idempotentKey = "r" + rule.getId() + "-v" + rule.getRuleVersion() + "-"
                + device.getId() + "-" + (dedupKey == null
                ? java.util.UUID.randomUUID() : dedupKey);

        IotProcessTrigger trigger = new IotProcessTrigger();
        trigger.setRuleId(rule.getId());
        trigger.setDeviceId(device.getId());
        trigger.setIdempotentKey(idempotentKey);
        trigger.setStatus("PENDING");
        trigger.setFormSnapshot(JSON.toJSONString(formData));
        trigger.setTriggerTime(now);
        trigger.setActionType("FLOW");
        // Phase 4：触发行必须携带恢复身份（发起目标/来源/发起人），否则提交后进程退出时
        // 恢复调度无法续跑该触发（findDue 要求 process_template_key 非空），事件静默消失。
        trigger.setProcessTemplateKey(rule.getProcessTemplateKey());
        trigger.setTriggerSource("RULE");
        trigger.setConfiguredBy(rule.getCreateBy());
        trigger.setRetryCount(0);
        if (!insertTriggerIdempotent(trigger)) {
            return;
        }
        publishTriggerEvent(device, rule, trigger, formData, null, null, eventFacts);
    }

    /** AGENT 动作：按输入映射构建执行输入，生成 AGENT 触发记录并发布图执行事件。 */
    private void fireAgent(IotDevice device, IotEventRule rule, Map<String, Object> eventFacts,
                           String dedupKey, LocalDateTime now) {
        Map<String, Object> agentInput = buildAgentInput(rule, device, eventFacts);
        String idempotentKey = "r" + rule.getId() + "-v" + rule.getRuleVersion() + "-"
                + device.getId() + "-agent-" + (dedupKey == null
                ? java.util.UUID.randomUUID() : dedupKey);

        IotProcessTrigger trigger = new IotProcessTrigger();
        trigger.setRuleId(rule.getId());
        trigger.setDeviceId(device.getId());
        trigger.setIdempotentKey(idempotentKey);
        trigger.setStatus("PENDING");
        trigger.setFormSnapshot(JSON.toJSONString(agentInput));
        trigger.setTriggerTime(now);
        trigger.setActionType("AGENT");
        // 恢复调度 findDue 要求发起目标非空：AGENT 行以目标图 key 承担恢复身份
        trigger.setProcessTemplateKey(rule.getAgentGraphKey());
        trigger.setAgentGraphKey(rule.getAgentGraphKey());
        trigger.setTriggerSource("RULE");
        trigger.setConfiguredBy(rule.getCreateBy());
        trigger.setRetryCount(0);
        if (!insertTriggerIdempotent(trigger)) {
            return;
        }
        publishTriggerEvent(device, rule, trigger, null, rule.getAgentGraphKey(), agentInput, eventFacts);
    }

    /** 幂等插入触发记录；命中幂等键（重复消息/重投）时返回 false 且不发布事件。 */
    private boolean insertTriggerIdempotent(IotProcessTrigger trigger) {
        try {
            // PostgreSQL 上唯一键冲突会中止整个事务，故在保存点内插入，
            // 命中幂等键后仍能执行下面的降级写回（否则 ingest 事务整体失败）。
            com.sw.ck.common.persistence.IdempotentInsert.execute(transactionManager,
                    () -> triggerMapper.insert(trigger));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.info("幂等键命中，跳过重复触发: key={}", trigger.getIdempotentKey());
            return false;
        }
    }

    /** 发布触发事件并记录审计（FLOW 与 AGENT 共用同一事件类型，消费方按 actionType 路由）。 */
    private void publishTriggerEvent(IotDevice device, IotEventRule rule, IotProcessTrigger trigger,
                                     Map<String, Object> formData, String agentGraphKey,
                                     Map<String, Object> agentInput, Map<String, Object> eventFacts) {
        IotProcessTriggerEvent event = new IotProcessTriggerEvent();
        event.setTenantId(device.getTenantId());
        event.setTriggerId(trigger.getId());
        event.setRuleId(rule.getId());
        event.setDeviceId(device.getId());
        event.setProcessTemplateKey(trigger.getProcessTemplateKey());
        event.setIdempotentKey(trigger.getIdempotentKey());
        event.setTriggerSource("RULE");
        event.setConfiguredBy(rule.getCreateBy());
        event.setActionType(trigger.getActionType());
        event.setAgentGraphKey(agentGraphKey);
        event.setAgentInput(agentInput);
        event.setFormData(formData);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ruleName", rule.getName());
        context.put("ruleVersion", rule.getRuleVersion());
        context.put("deviceKey", device.getDeviceKey());
        context.put("deviceName", device.getName());
        context.putAll(eventFacts);
        event.setContext(context);
        eventPublisher.publish(event);

        if (auditService != null) {
            String action = "AGENT".equals(trigger.getActionType()) ? "AGENT_TRIGGER" : "FLOW_TRIGGER";
            auditService.recordAction(device.getTenantId(), rule.getCreateBy(),
                    "system:iot-rule-engine", action, "RULE_TRIGGER",
                    String.valueOf(trigger.getId()), "PENDING", trigger.getIdempotentKey(),
                    "ruleId=" + rule.getId() + ", deviceId=" + device.getId());
        }

        log.info("规则触发{}事件: ruleId={}, device={}, key={}",
                "AGENT".equals(trigger.getActionType()) ? "Agent" : "流程",
                rule.getId(), device.getDeviceKey(), trigger.getIdempotentKey());
    }

    /**
     * 构建 Agent 执行输入：source 词汇表与 form_mapping_json 一致，
     * 另支持 RECENT_SERIES（按设备/属性/时间窗回查近期属性记录序列）。
     */
    private Map<String, Object> buildAgentInput(IotEventRule rule, IotDevice device,
                                                Map<String, Object> eventFacts) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (rule.getAgentInputMappingJson() != null && !rule.getAgentInputMappingJson().isBlank()) {
            JSONArray mappings = JSON.parseArray(rule.getAgentInputMappingJson());
            if (mappings != null) {
                for (int i = 0; i < mappings.size(); i++) {
                    JSONObject mapping = mappings.getJSONObject(i);
                    String field = mapping.getString("field");
                    String source = mapping.getString("source");
                    Object value;
                    if ("RECENT_SERIES".equals(source)) {
                        value = recentSeries(device, mapping);
                    } else {
                        value = resolveSource(source, device, eventFacts, mapping);
                    }
                    if (value != null || mapping.getBooleanValue("required")) {
                        input.put(field, value);
                    }
                }
            }
        }
        return input;
    }

    /** RECENT_SERIES：时间窗内该设备属性记录升序序列（[{time,value}]），上限 limit（默认 30）。 */
    private List<Map<String, Object>> recentSeries(IotDevice device, JSONObject mapping) {
        String propertyId = mapping.getString("propertyId");
        int windowSeconds = mapping.getIntValue("windowSeconds") > 0
                ? mapping.getIntValue("windowSeconds") : 300;
        int limit = mapping.getIntValue("limit") > 0 ? Math.min(mapping.getIntValue("limit"), 200) : 30;
        LocalDateTime since = LocalDateTime.now().minusSeconds(windowSeconds);
        List<IotPropertyRecord> records = propertyRecordMapper.selectList(
                new LambdaQueryWrapper<IotPropertyRecord>()
                        .eq(IotPropertyRecord::getDeviceId, device.getId())
                        .eq(propertyId != null && !propertyId.isBlank(),
                                IotPropertyRecord::getPropertyId, propertyId)
                        .ge(IotPropertyRecord::getReportTime, since)
                        .orderByAsc(IotPropertyRecord::getReportTime)
                        .last("LIMIT " + limit));
        List<Map<String, Object>> series = new ArrayList<>();
        for (IotPropertyRecord record : records) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", record.getReportTime() == null ? null : record.getReportTime().toString());
            point.put("value", toPlainValue(record.getValueJson()));
            series.add(point);
        }
        return series;
    }

    private Object toPlainValue(String valueJson) {
        if (valueJson == null) {
            return null;
        }
        try {
            Object parsed = JSON.parse(valueJson);
            return parsed instanceof Number ? parsed : valueJson;
        } catch (Exception ignore) {
            return valueJson;
        }
    }

    private Map<String, Object> buildFormData(IotEventRule rule, IotDevice device,
                                              Map<String, Object> eventFacts) {
        Map<String, Object> formData = new HashMap<>();
        if (rule.getFormMappingJson() != null && !rule.getFormMappingJson().isBlank()) {
            JSONArray mappings = JSON.parseArray(rule.getFormMappingJson());
            if (mappings != null) {
                for (int i = 0; i < mappings.size(); i++) {
                    JSONObject mapping = mappings.getJSONObject(i);
                    String field = mapping.getString("field");
                    String source = mapping.getString("source");
                    Object value = resolveSource(source, device, eventFacts, mapping);
                    if (value != null || mapping.getBooleanValue("required")) {
                        formData.put(field, value);
                    }
                }
            }
        }
        return formData;
    }

    private Object resolveSource(String source, IotDevice device,
                                 Map<String, Object> eventFacts, JSONObject mapping) {
        if (source == null) {
            return mapping.get("fixed");
        }
        return switch (source) {
            case "DEVICE_ID" -> device.getId();
            case "DEVICE_KEY" -> device.getDeviceKey();
            case "DEVICE_NAME" -> device.getName();
            case "PRODUCT_ID" -> device.getProductRefId();
            case "PROPERTY_VALUE" -> eventFacts.get("value");
            case "PREV_VALUE" -> eventFacts.get("preValue");
            case "EVENT_PAYLOAD" -> eventFacts.get("payload");
            case "REPORT_TIME" -> eventFacts.get("reportTime");
            case "RULE_NAME" -> mapping.get("fixed");
            default -> mapping.get("fixed");
        };
    }

    private void touchFired(IotEventRule rule, LocalDateTime now) {
        IotEventRule patch = new IotEventRule();
        patch.setId(rule.getId());
        patch.setLastFiredTime(now);
        ruleMapper.updateById(patch);
    }

    private List<IotEventRule> publishedRules(Long deviceId) {
        return ruleMapper.selectList(new LambdaQueryWrapper<IotEventRule>()
                .eq(IotEventRule::getDeviceId, deviceId)
                .eq(IotEventRule::getStatus, "PUBLISHED"));
    }
}
