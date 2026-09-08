package com.sw.ck.iot.script;

import com.alibaba.fastjson2.JSON;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.iot.entity.IotCommand;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRecord;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotPropertyRecord;
import com.sw.ck.iot.entity.IotScript;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.event.IotProcessTriggerEvent;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import com.sw.ck.iot.script.api.IotScriptApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主函数服务端实现。
 * <p>
 * JS 与 Java 共用本实现，保证两种语言同语义。每次执行绑定租户、操作者、
 * 脚本版本与幂等上下文；副作用默认关闭，试运行仅生成命令记录不真实发布。
 * 所有返回均为可序列化 Map，凭证明文永不进入脚本上下文。
 * </p>
 */
public class ScriptHostFunctions implements IotScriptApi {

    private static final Logger log = LoggerFactory.getLogger(ScriptHostFunctions.class);

    private final IotDeviceMapper deviceMapper;
    private final IotConnectionMapper connectionMapper;
    private final IotTopicMapper topicMapper;
    private final IotCommandMapper commandMapper;
    private final IotPropertyRecordMapper propertyRecordMapper;
    private final IotEventRecordMapper eventRecordMapper;
    private final IotProductMapper productMapper;
    private final IotThingModelMapper thingModelMapper;
    private final MqttBrokerManager mqttBrokerManager;
    private final DomainEventPublisher eventPublisher;
    private final ScriptExecutionSpec spec;

    private final List<String> logs = new ArrayList<>();
    private static final int MAX_LOGS = 200;

    /** 每脚本执行的订阅声明（仅记录声明，不直接建立网络订阅）。 */
    private final List<Map<String, Object>> subscriptionDeclarations = new ArrayList<>();

    /** 每脚本执行的幂等键上下文（外部触发时携带）。 */
    private final Map<String, String> idempotentKeys = new ConcurrentHashMap<>();

    public ScriptHostFunctions(IotDeviceMapper deviceMapper,
                               IotConnectionMapper connectionMapper,
                               IotTopicMapper topicMapper,
                               IotCommandMapper commandMapper,
                               IotPropertyRecordMapper propertyRecordMapper,
                               IotEventRecordMapper eventRecordMapper,
                               IotProductMapper productMapper,
                               IotThingModelMapper thingModelMapper,
                               MqttBrokerManager mqttBrokerManager,
                               DomainEventPublisher eventPublisher,
                               ScriptExecutionSpec spec) {
        this.deviceMapper = deviceMapper;
        this.connectionMapper = connectionMapper;
        this.topicMapper = topicMapper;
        this.commandMapper = commandMapper;
        this.propertyRecordMapper = propertyRecordMapper;
        this.eventRecordMapper = eventRecordMapper;
        this.productMapper = productMapper;
        this.thingModelMapper = thingModelMapper;
        this.mqttBrokerManager = mqttBrokerManager;
        this.eventPublisher = eventPublisher;
        this.spec = spec;
    }

    @Override
    public Map<String, Object> funPublish(String topic, String payload, Map<String, Object> options) {
        requireSideEffect("fun_publish");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic 不能为空");
        }
        IotTopic topicCfg = topicMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotTopic>()
                        .eq(IotTopic::getTopic, topic)
                        .last("limit 1"));
        if (topicCfg == null || topicCfg.getEnabled() == null || topicCfg.getEnabled() != 1) {
            throw new IllegalArgumentException("主题未配置或未启用: " + topic);
        }
        IotConnection conn = connectionMapper.selectById(topicCfg.getConnId());
        if (conn == null || conn.getEnabled() == null || conn.getEnabled() != 1) {
            throw new IllegalArgumentException("连接未配置或未启用");
        }
        IotDevice device = spec.getDeviceId() == null ? null : deviceMapper.selectById(spec.getDeviceId());
        Long deviceId = device == null ? null : device.getId();
        int qos = topicCfg.getQos() == null ? 1 : topicCfg.getQos();
        if (options != null && options.get("qos") instanceof Number n) {
            qos = n.intValue();
        }
        // 命令记录先行：Broker 接收 ≠ 设备执行
        IotCommand command = new IotCommand();
        command.setDeviceId(deviceId);
        command.setConnId(conn.getId());
        command.setProvider("MQTT");
        command.setCapabilityType("PUBLISH");
        command.setCapabilityId(topic);
        command.setParamsJson(payload);
        command.setStatus("PENDING");
        command.setSourceType("SCRIPT");
        command.setSourceRef("script:" + spec.getScriptId() + ":v" + spec.getScriptVersion());
        command.setCorrelationId(spec.getCorrelationId());
        command.setQos(qos);
        commandMapper.insert(command);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", command.getId());
        result.put("correlationId", spec.getCorrelationId());
        try {
            Map<String, Object> publishResult = mqttBrokerManager.publish(
                    conn.getId(), topic, payload, qos,
                    topicCfg.getRetain() != null && topicCfg.getRetain() == 1);
            IotCommand patch = new IotCommand();
            patch.setId(command.getId());
            patch.setStatus(Boolean.TRUE.equals(publishResult.get("brokerAck")) ? "BROKER_ACK" : "PENDING");
            patch.setSentTime(LocalDateTime.now());
            commandMapper.updateById(patch);
            result.put("brokerAck", publishResult.get("brokerAck"));
        } catch (Exception e) {
            IotCommand patch = new IotCommand();
            patch.setId(command.getId());
            patch.setStatus("FAILED");
            patch.setError(trim(e.getMessage(), 500));
            commandMapper.updateById(patch);
            result.put("brokerAck", false);
            result.put("error", trim(e.getMessage(), 500));
        }
        return result;
    }

    @Override
    public Map<String, Object> funSubscribe(String topicFilter, Map<String, Object> options) {
        if (topicFilter == null || topicFilter.isBlank()) {
            throw new IllegalArgumentException("topicFilter 不能为空");
        }
        IotTopic topicCfg = topicMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotTopic>()
                        .eq(IotTopic::getTopic, topicFilter)
                        .last("limit 1"));
        if (topicCfg == null || topicCfg.getEnabled() == null || topicCfg.getEnabled() != 1) {
            throw new IllegalArgumentException("主题未配置或未启用: " + topicFilter);
        }
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("topicFilter", topicFilter);
        declaration.put("qos", topicCfg.getQos());
        subscriptionDeclarations.add(declaration);
        return new LinkedHashMap<>(declaration);
    }

    @Override
    public Map<String, Object> funGetProperty(Long deviceId, String propertyId) {
        requireAuthorizedDevice(deviceId);
        IotPropertyRecord latest = propertyRecordMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotPropertyRecord>()
                        .eq(IotPropertyRecord::getDeviceId, deviceId)
                        .eq(IotPropertyRecord::getPropertyId, propertyId)
                        .orderByDesc(IotPropertyRecord::getReportTime)
                        .last("limit 1"));
        Map<String, Object> result = new LinkedHashMap<>();
        if (latest == null) {
            result.put("value", null);
            result.put("reportTime", null);
        } else {
            result.put("value", JSON.parse(latest.getValueJson()));
            result.put("reportTime", latest.getReportTime().toString());
        }
        return result;
    }

    @Override
    public Map<String, Object> funSetProperty(Long deviceId, String propertyId, Object value,
                                              Map<String, Object> options) {
        requireSideEffect("fun_setProperty");
        IotDevice device = requireAuthorizedDevice(deviceId);
        validateCapability(device, "setProperty", propertyId);
        IotCommand command = new IotCommand();
        command.setDeviceId(device.getId());
        command.setProvider("TENCENT".equals(deviceProductConnType(device)) ? "TENCENT" : "MQTT");
        command.setCapabilityType("SET_PROPERTY");
        command.setCapabilityId(propertyId);
        command.setParamsJson(JSON.toJSONString(value));
        command.setStatus("PENDING");
        command.setSourceType("SCRIPT");
        command.setSourceRef("script:" + spec.getScriptId() + ":v" + spec.getScriptVersion());
        command.setCorrelationId(spec.getCorrelationId());
        commandMapper.insert(command);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", command.getId());
        result.put("correlationId", spec.getCorrelationId());
        result.put("status", command.getStatus());
        return result;
    }

    @Override
    public Map<String, Object> funEmitEvent(Long deviceId, String eventId, Object payload) {
        IotDevice device = requireAuthorizedDevice(deviceId);
        validateCapability(device, "event", eventId);
        IotEventRecord record = new IotEventRecord();
        record.setDeviceId(device.getId());
        record.setEventId(eventId);
        record.setCorrelationId(spec.getCorrelationId());
        record.setPayload(payload == null ? null : JSON.toJSONString(payload));
        record.setOccurTime(LocalDateTime.now());
        eventRecordMapper.insert(record);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", record.getId());
        result.put("eventId", record.getEventId());
        result.put("correlationId", record.getCorrelationId());
        return result;
    }

    @Override
    public Map<String, Object> funInvokeAction(Long deviceId, String actionId, Object input,
                                               Map<String, Object> options) {
        requireSideEffect("fun_invokeAction");
        IotDevice device = requireAuthorizedDevice(deviceId);
        validateCapability(device, "action", actionId);
        IotCommand command = new IotCommand();
        command.setDeviceId(device.getId());
        command.setProvider("TENCENT".equals(deviceProductConnType(device)) ? "TENCENT" : "MQTT");
        command.setCapabilityType("INVOKE_ACTION");
        command.setCapabilityId(actionId);
        command.setParamsJson(input == null ? null : JSON.toJSONString(input));
        command.setStatus("PENDING");
        command.setSourceType("SCRIPT");
        command.setSourceRef("script:" + spec.getScriptId() + ":v" + spec.getScriptVersion());
        command.setCorrelationId(spec.getCorrelationId());
        if (options != null && options.get("idempotentKey") != null) {
            command.setIdempotentKey(String.valueOf(options.get("idempotentKey")));
        }
        commandMapper.insert(command);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandId", command.getId());
        result.put("correlationId", spec.getCorrelationId());
        result.put("status", command.getStatus());
        return result;
    }

    @Override
    public Map<String, Object> funStartProcess(String templateKey, Map<String, Object> formData,
                                               Map<String, Object> options) {
        requireSideEffect("fun_startProcess");
        if (templateKey == null || templateKey.isBlank()) {
            throw new IllegalArgumentException("流程模板 key 不能为空");
        }
        String idempotentKey = options == null || options.get("idempotentKey") == null
                ? "script-" + spec.getScriptId() + "-v" + spec.getScriptVersion() + "-"
                        + UUID.randomUUID()
                : String.valueOf(options.get("idempotentKey"));
        IotProcessTriggerEvent event = new IotProcessTriggerEvent();
        event.setTenantId(spec.getTenantId());
        event.setScriptId(spec.getScriptId());
        event.setDeviceId(spec.getDeviceId());
        event.setProcessTemplateKey(templateKey);
        event.setFormData(formData == null ? Map.of() : formData);
        event.setIdempotentKey(idempotentKey);
        event.setTriggerSource("SCRIPT");
        event.setConfiguredBy(spec.getActorId());
        eventPublisher.publish(event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("idempotentKey", idempotentKey);
        result.put("status", "PENDING");
        return result;
    }

    @Override
    public void funLog(String level, String message, Map<String, Object> fields) {
        if (logs.size() >= MAX_LOGS) {
            return;
        }
        String line = "[" + (level == null ? "INFO" : level) + "] "
                + (message == null ? "" : message)
                + (fields == null || fields.isEmpty() ? "" : " " + JSON.toJSONString(fields));
        logs.add(line);
        log.info("IoT 脚本日志: scriptId=v{} {}", spec.getScriptVersion(), line);
    }

    // ---------------- 给执行引擎的出口 ----------------

    public List<String> getLogs() {
        return logs;
    }

    public List<Map<String, Object>> getSubscriptionDeclarations() {
        return subscriptionDeclarations;
    }

    public void registerIdempotentKey(String op, String key) {
        idempotentKeys.put(op, key);
    }

    // ---------------- 校验 ----------------

    private void requireSideEffect(String fn) {
        if (!spec.isSideEffectAllowed()) {
            throw new IllegalStateException(fn + " 在试运行下被禁止（副作用开关未开启）");
        }
    }

    private IotDevice requireAuthorizedDevice(Long deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null || device.getTenantId() == null
                || !device.getTenantId().equals(spec.getTenantId())) {
            throw new IllegalArgumentException("设备不存在或越权访问: deviceId=" + deviceId);
        }
        return device;
    }

    private void validateCapability(IotDevice device, String kind, String capabilityId) {
        if (device.getProductRefId() == null) {
            throw new IllegalArgumentException("设备未绑定产品，无法校验能力: " + capabilityId);
        }
        IotProduct product = productMapper.selectById(device.getProductRefId());
        if (product == null || product.getPublishedModelId() == null) {
            throw new IllegalArgumentException("产品未发布物模型，无法校验能力: " + capabilityId);
        }
        IotThingModel model = thingModelMapper.selectById(product.getPublishedModelId());
        if (model == null) {
            throw new IllegalArgumentException("已发布物模型不存在");
        }
        com.alibaba.fastjson2.JSONObject content = JSON.parseObject(model.getContentJson());
        String arrayKey = switch (kind) {
            case "setProperty" -> "properties";
            case "event" -> "events";
            case "action" -> "actions";
            default -> throw new IllegalArgumentException("未知能力类型: " + kind);
        };
        com.alibaba.fastjson2.JSONArray arr = content.getJSONArray(arrayKey);
        boolean found = arr != null && arr.stream().anyMatch(p -> capabilityId.equals(
                ((com.alibaba.fastjson2.JSONObject) p).getString("id")));
        if (!found) {
            throw new IllegalArgumentException("能力未在已发布物模型中声明: " + arrayKey + "." + capabilityId);
        }
    }

    private String deviceProductConnType(IotDevice device) {
        if (device.getProductRefId() == null) {
            return "MQTT";
        }
        IotProduct product = productMapper.selectById(device.getProductRefId());
        return product == null || product.getConnType() == null ? "MQTT" : product.getConnType();
    }

    private String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /**
     * 幂等键出口（规则联动重试沿同一键）。
     */
    public Map<String, String> getIdempotentKeys() {
        return idempotentKeys;
    }

    /**
     * 构建 input 上下文的通用快照键。
     */
    public static Map<String, Object> baseInput(ScriptExecutionSpec spec, Map<String, Object> extra) {
        Map<String, Object> input = new HashMap<>();
        input.put("tenantId", spec.getTenantId());
        input.put("actorId", spec.getActorId());
        input.put("triggerSource", spec.getTriggerSource());
        input.put("triggerRef", spec.getTriggerRef());
        input.put("deviceId", spec.getDeviceId());
        if (extra != null) {
            input.putAll(extra);
        }
        return input;
    }
}
