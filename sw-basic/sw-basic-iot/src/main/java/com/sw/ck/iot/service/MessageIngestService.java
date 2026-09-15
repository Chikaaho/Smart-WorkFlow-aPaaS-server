package com.sw.ck.iot.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotCommand;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotEventRecord;
import com.sw.ck.iot.entity.IotMessageLog;
import com.sw.ck.iot.entity.IotProduct;
import com.sw.ck.iot.entity.IotPropertyRecord;
import com.sw.ck.iot.entity.IotThingModel;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotCommandMapper;
import com.sw.ck.iot.mapper.IotDeviceMapper;
import com.sw.ck.iot.mapper.IotEventRecordMapper;
import com.sw.ck.iot.mapper.IotMessageLogMapper;
import com.sw.ck.iot.mapper.IotProductMapper;
import com.sw.ck.iot.mapper.IotPropertyRecordMapper;
import com.sw.ck.iot.mapper.IotThingModelMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自建 MQTT 订阅消息统一入口。
 * <p>
 * 按 Topic 配置将消息解析为属性上报 / 事件 / 行为结果 / 原始消息；
 * 解析失败进入可查询失败记录，不更新属性、不触发规则成功路径；
 * 重复消息（同 dedupKey）仅记录不重复触发。
 * </p>
 */
@Service
public class MessageIngestService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestService.class);

    public static final String TYPE_PROPERTY = "PROPERTY";
    public static final String TYPE_EVENT = "EVENT";
    public static final String TYPE_ACTION_RESULT = "ACTION_RESULT";
    public static final String TYPE_RAW = "RAW";

    private final IotTopicMapper topicMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotMessageLogMapper messageLogMapper;
    private final IotPropertyRecordMapper propertyRecordMapper;
    private final IotEventRecordMapper eventRecordMapper;
    private final IotCommandMapper commandMapper;
    private final IotProductMapper productMapper;
    private final IotThingModelMapper thingModelMapper;
    private final RuleEngineService ruleEngineService;
    private final IotScriptService scriptService;

    public MessageIngestService(IotTopicMapper topicMapper,
                                IotDeviceMapper deviceMapper,
                                IotMessageLogMapper messageLogMapper,
                                IotPropertyRecordMapper propertyRecordMapper,
                                IotEventRecordMapper eventRecordMapper,
                                IotCommandMapper commandMapper,
                                IotProductMapper productMapper,
                                IotThingModelMapper thingModelMapper,
                                @Lazy RuleEngineService ruleEngineService,
                                @Lazy IotScriptService scriptService) {
        this.topicMapper = topicMapper;
        this.deviceMapper = deviceMapper;
        this.messageLogMapper = messageLogMapper;
        this.propertyRecordMapper = propertyRecordMapper;
        this.eventRecordMapper = eventRecordMapper;
        this.commandMapper = commandMapper;
        this.productMapper = productMapper;
        this.thingModelMapper = thingModelMapper;
        this.ruleEngineService = ruleEngineService;
        this.scriptService = scriptService;
    }

    /**
     * 消息统一入口（由 MqttBrokerManager 回调）。
     * <p>
     * 事务边界保证：解析写入与流程触发事件（AFTER_COMMIT）同生共死，
     * 无事务时 TransactionalEventListener 将静默丢弃事件。
     * </p>
     */
    @Transactional
    public void ingest(Long connId, String topic, String payload, int qos, String messageId) {
        IotTopic topicCfg = resolveTopicConfig(connId, topic);
        String dedupKey = sha256(topic + "|" + payload);

        IotMessageLog msgLog = new IotMessageLog();
        msgLog.setConnId(connId);
        msgLog.setTopic(topic);
        msgLog.setDirection("UP");
        msgLog.setMessageId(messageId);
        msgLog.setDedupKey(dedupKey);
        msgLog.setPayload(payload);
        msgLog.setQos(qos);

        // 重复消息去重：同 dedupKey 已存在则仅记录
        Long dup = messageLogMapper.selectCount(new LambdaQueryWrapper<IotMessageLog>()
                .eq(IotMessageLog::getDedupKey, dedupKey)
                .ne(IotMessageLog::getParseStatus, "DUPLICATED"));
        if (dup != null && dup > 0) {
            msgLog.setParseStatus("DUPLICATED");
            messageLogMapper.insert(msgLog);
            return;
        }

        IotDevice device = resolveDevice(topicCfg, topic);
        msgLog.setDeviceId(device == null ? null : device.getId());
        msgLog.setPayloadType(topicCfg == null ? TYPE_RAW : topicCfg.getPayloadType());

        try {
            String payloadType = topicCfg == null ? TYPE_RAW : topicCfg.getPayloadType();
            switch (payloadType) {
                case TYPE_PROPERTY -> {
                    requireDevice(device, topic);
                    handleProperty(device, payload, msgLog, dedupKey);
                    msgLog.setParseStatus("PARSED");
                }
                case TYPE_EVENT -> {
                    requireDevice(device, topic);
                    handleEvent(device, payload, msgLog, dedupKey);
                    msgLog.setParseStatus("PARSED");
                }
                case TYPE_ACTION_RESULT -> {
                    handleActionResult(payload, msgLog);
                    msgLog.setParseStatus("PARSED");
                }
                default -> {
                    msgLog.setParseStatus("RECEIVED");
                    // RAW 消息同样进入 MESSAGE 触发脚本路径
                    if (device != null) {
                        scriptService.onMessageTriggered(device, topic, payload, dedupKey);
                    }
                }
            }
        } catch (Exception e) {
            msgLog.setParseStatus("FAILED");
            msgLog.setParseError(trim(e.getMessage(), 500));
            log.warn("消息解析失败: topic={}, error={}", topic, e.getMessage());
        }
        messageLogMapper.insert(msgLog);
    }

    // ---------------- 解析路径 ----------------

    private void handleProperty(IotDevice device, String payload, IotMessageLog msgLog, String dedupKey) {
        JSONObject body = JSON.parseObject(payload);
        JSONObject properties = body.getJSONObject("properties");
        if (properties == null) {
            // 允许平铺对象作为属性集合
            properties = new JSONObject();
            properties.putAll(body);
            properties.remove("time");
        }
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("属性载荷为空");
        }
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> changed = new HashMap<>();
        Map<String, String> preValues = new HashMap<>();
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String propertyId = entry.getKey();
            String valueJson = JSON.toJSONString(entry.getValue());
            IotPropertyRecord latest = propertyRecordMapper.selectOne(new LambdaQueryWrapper<IotPropertyRecord>()
                    .eq(IotPropertyRecord::getDeviceId, device.getId())
                    .eq(IotPropertyRecord::getPropertyId, propertyId)
                    .orderByDesc(IotPropertyRecord::getReportTime)
                    .last("limit 1"));
            // 物模型校验：仅接受已发布物模型中声明的属性
            validatePropertyDeclared(device, propertyId, entry.getValue());
            IotPropertyRecord record = new IotPropertyRecord();
            record.setDeviceId(device.getId());
            record.setPropertyId(propertyId);
            record.setValueJson(valueJson);
            record.setPreValueJson(latest == null ? null : latest.getValueJson());
            record.setReportTime(now);
            propertyRecordMapper.insert(record);
            changed.put(propertyId, valueJson);
            preValues.put(propertyId, latest == null ? null : latest.getValueJson());
        }
        IotDevice patch = new IotDevice();
        patch.setId(device.getId());
        patch.setLastReportTime(now);
        patch.setStatus("ONLINE");
        deviceMapper.updateById(patch);
        msgLog.setPayloadType(TYPE_PROPERTY);
        // 属性变化进入规则引擎（有变化才触发 PROPERTY_CHANGED）
        for (Map.Entry<String, String> entry : changed.entrySet()) {
            ruleEngineService.onProperty(device, entry.getKey(), entry.getValue(),
                    preValues.get(entry.getKey()), now, dedupKey);
        }
        // MESSAGE 触发脚本
        scriptService.onMessageTriggered(device, topicOf(msgLog), payload, dedupKey);
    }

    private void handleEvent(IotDevice device, String payload, IotMessageLog msgLog, String dedupKey) {
        JSONObject body = JSON.parseObject(payload);
        String eventId = body.getString("eventId");
        if (eventId == null || eventId.isBlank()) {
            eventId = body.getString("id");
        }
        Object eventPayload = body.get("payload");
        if (eventPayload == null) {
            eventPayload = body.get("value");
        }
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("事件载荷缺少 eventId");
        }
        // 物模型校验：仅接受已发布物模型中声明的事件
        validateEventDeclared(device, eventId);
        IotEventRecord record = new IotEventRecord();
        record.setDeviceId(device.getId());
        record.setEventId(eventId);
        record.setPayload(eventPayload == null ? null : JSON.toJSONString(eventPayload));
        record.setOccurTime(LocalDateTime.now());
        eventRecordMapper.insert(record);
        IotDevice patch = new IotDevice();
        patch.setId(device.getId());
        patch.setLastReportTime(record.getOccurTime());
        patch.setStatus("ONLINE");
        deviceMapper.updateById(patch);
        ruleEngineService.onEvent(device, eventId, record.getPayload(), dedupKey);
        scriptService.onMessageTriggered(device, topicOf(msgLog), payload, dedupKey);
    }

    private void validateEventDeclared(IotDevice device, String eventId) {
        if (device.getProductRefId() == null) {
            return;
        }
        IotProduct product = productMapper.selectById(device.getProductRefId());
        if (product == null || product.getPublishedModelId() == null) {
            throw new IllegalArgumentException("产品未发布物模型，拒绝事件上报: " + eventId);
        }
        IotThingModel model = thingModelMapper.selectById(product.getPublishedModelId());
        if (model == null) {
            throw new IllegalArgumentException("已发布物模型不存在");
        }
        JSONObject content = JSON.parseObject(model.getContentJson());
        JSONArray events = content.getJSONArray("events");
        if (events == null || events.stream().noneMatch(e -> eventId.equals(
                ((JSONObject) e).getString("id")))) {
            throw new IllegalArgumentException("事件未在已发布物模型中声明: " + eventId);
        }
    }

    private void handleActionResult(String payload, IotMessageLog msgLog) {
        JSONObject body = JSON.parseObject(payload);
        Long commandId = body.getLong("commandId");
        if (commandId == null) {
            throw new IllegalArgumentException("行为结果载荷缺少 commandId");
        }
        IotCommand command = commandMapper.selectById(commandId);
        if (command == null) {
            throw new IllegalArgumentException("命令不存在: commandId=" + commandId);
        }
        String correlationId = body.getString("correlationId");
        if (command.getCorrelationId() != null
                && !command.getCorrelationId().equals(correlationId)) {
            throw new IllegalArgumentException("行为结果 correlationId 与命令不一致: commandId=" + commandId);
        }
        // SUCCESS/FAILED 是设备命令终态。设备重投或网关重复回调只能被记录，
        // 不得覆盖结果、回包时间或触发第二次业务副作用。
        if ("SUCCESS".equals(command.getStatus()) || "FAILED".equals(command.getStatus())) {
            log.info("忽略已终态命令的重复行为结果: commandId={}, status={}",
                    commandId, command.getStatus());
            msgLog.setDeviceId(command.getDeviceId());
            return;
        }
        String status = body.getString("status");
        IotCommand patch = new IotCommand();
        patch.setId(command.getId());
        if ("SUCCESS".equalsIgnoreCase(status)) {
            patch.setStatus("SUCCESS");
            patch.setResultJson(body.get("result") == null ? null : JSON.toJSONString(body.get("result")));
        } else if ("FAILED".equalsIgnoreCase(status)) {
            patch.setStatus("FAILED");
            patch.setError(trim(body.getString("error"), 500));
        } else {
            patch.setStatus("DEVICE_REPLY");
            patch.setResultJson(JSON.toJSONString(body));
        }
        patch.setReplyTime(LocalDateTime.now());
        commandMapper.updateById(patch);
        msgLog.setDeviceId(command.getDeviceId());
    }

    // ---------------- 定位 ----------------

    private IotTopic resolveTopicConfig(Long connId, String topic) {
        List<IotTopic> topics = topicMapper.selectList(new LambdaQueryWrapper<IotTopic>()
                .eq(IotTopic::getConnId, connId)
                .eq(IotTopic::getEnabled, 1));
        for (IotTopic cfg : topics) {
            if (cfg.getTopic().equals(topic) || mqttFilterMatches(cfg.getTopic(), topic)) {
                return cfg;
            }
        }
        return null;
    }

    private boolean mqttFilterMatches(String filter, String topic) {
        String[] f = filter.split("/");
        String[] t = topic.split("/");
        int i = 0;
        for (; i < f.length; i++) {
            if (f[i].equals("#")) {
                return true;
            }
            if (i >= t.length) {
                return false;
            }
            if (f[i].equals("+")) {
                continue;
            }
            if (!f[i].equals(t[i])) {
                return false;
            }
        }
        return i == t.length;
    }

    private IotDevice resolveDevice(IotTopic topicCfg, String topic) {
        if (topicCfg == null || topicCfg.getProductId() == null) {
            return null;
        }
        // 模板变量 {deviceKey}：按主题段落提取
        String deviceKey = null;
        String template = topicCfg.getTopic();
        if (template.contains("{deviceKey}")) {
            String[] tp = template.split("/");
            String[] ap = topic.split("/");
            for (int i = 0; i < tp.length && i < ap.length; i++) {
                if (tp[i].equals("{deviceKey}")) {
                    deviceKey = ap[i];
                    break;
                }
            }
        }
        if (deviceKey != null) {
            return deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getProductRefId, topicCfg.getProductId())
                    .eq(IotDevice::getDeviceKey, deviceKey)
                    .last("limit 1"));
        }
        // 无模板变量：该产品下仅一台设备时唯一定位
        List<IotDevice> devices = deviceMapper.selectList(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getProductRefId, topicCfg.getProductId()));
        return devices.size() == 1 ? devices.get(0) : null;
    }

    private void requireDevice(IotDevice device, String topic) {
        if (device == null) {
            throw new IllegalArgumentException("无法定位设备（Topic 配置需绑定产品且可解析 deviceKey）: " + topic);
        }
    }

    private void validatePropertyDeclared(IotDevice device, String propertyId, Object value) {
        if (device.getProductRefId() == null) {
            return;
        }
        IotProduct product = productMapper.selectById(device.getProductRefId());
        if (product == null || product.getPublishedModelId() == null) {
            throw new IllegalArgumentException("产品未发布物模型，拒绝属性上报: " + propertyId);
        }
        IotThingModel model = thingModelMapper.selectById(product.getPublishedModelId());
        if (model == null) {
            throw new IllegalArgumentException("已发布物模型不存在");
        }
        JSONObject content = JSON.parseObject(model.getContentJson());
        JSONArray props = content.getJSONArray("properties");
        if (props == null || props.stream().noneMatch(p -> propertyId.equals(
                ((JSONObject) p).getString("id")))) {
            throw new IllegalArgumentException("属性未在已发布物模型中声明: " + propertyId);
        }
    }

    private String topicOf(IotMessageLog msgLog) {
        return msgLog.getTopic();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String trim(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
