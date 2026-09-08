package com.sw.ck.iot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.mqtt.MqttBrokerManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Topic 配置服务：订阅/发布 Topic 维护与运行时订阅生效。
 */
@Service
public class IotTopicService {

    private final IotTopicMapper topicMapper;
    private final IotConnectionMapper connectionMapper;
    private final MqttBrokerManager mqttBrokerManager;

    public IotTopicService(IotTopicMapper topicMapper,
                           IotConnectionMapper connectionMapper,
                           @Lazy MqttBrokerManager mqttBrokerManager) {
        this.topicMapper = topicMapper;
        this.connectionMapper = connectionMapper;
        this.mqttBrokerManager = mqttBrokerManager;
    }

    public List<IotTopic> list() {
        return topicMapper.selectList(new LambdaQueryWrapper<>());
    }

    public IotTopic getById(Long id) {
        return topicMapper.selectById(id);
    }

    public IotTopic create(IotTopic topic) {
        validate(topic);
        Long count = topicMapper.selectCount(new LambdaQueryWrapper<IotTopic>()
                .eq(IotTopic::getTopic, topic.getTopic()));
        if (count != null && count > 0) {
            throw new IllegalArgumentException("Topic 已存在: " + topic.getTopic());
        }
        topic.setId(null);
        if (topic.getEnabled() == null) {
            topic.setEnabled(1);
        }
        topicMapper.insert(topic);
        applyIfEnabled(topic);
        return topic;
    }

    public IotTopic update(Long id, IotTopic patch) {
        validate(patch);
        patch.setId(id);
        topicMapper.updateById(patch);
        IotTopic updated = topicMapper.selectById(id);
        if (updated.getEnabled() != null && updated.getEnabled() == 1) {
            applyIfEnabled(updated);
        }
        return updated;
    }

    public void changeEnabled(Long id, boolean enabled) {
        IotTopic patch = new IotTopic();
        patch.setId(id);
        patch.setEnabled(enabled ? 1 : 0);
        topicMapper.updateById(patch);
    }

    public void delete(Long id) {
        topicMapper.deleteById(id);
    }

    private void applyIfEnabled(IotTopic topic) {
        try {
            mqttBrokerManager.subscribe(topic.getConnId(), topic);
        } catch (Exception e) {
            // 订阅失败（连接未建立等）不阻塞配置保存；连接建立时会恢复订阅
        }
    }

    private void validate(IotTopic topic) {
        if (topic.getConnId() == null) {
            throw new IllegalArgumentException("Topic 必须绑定连接配置");
        }
        if (topic.getTopic() == null || topic.getTopic().isBlank()) {
            throw new IllegalArgumentException("Topic 不能为空");
        }
        if (topic.getTopic().contains(" ") || topic.getTopic().length() > 200) {
            throw new IllegalArgumentException("Topic 格式非法");
        }
        String direction = topic.getDirection();
        if (!"UP".equals(direction) && !"DOWN".equals(direction) && !"BOTH".equals(direction)) {
            throw new IllegalArgumentException("方向仅支持 UP/DOWN/BOTH: " + direction);
        }
        String payloadType = topic.getPayloadType();
        if (!MessageIngestService.TYPE_PROPERTY.equals(payloadType)
                && !MessageIngestService.TYPE_EVENT.equals(payloadType)
                && !MessageIngestService.TYPE_ACTION_RESULT.equals(payloadType)
                && !MessageIngestService.TYPE_RAW.equals(payloadType)) {
            throw new IllegalArgumentException("载荷类型仅支持 PROPERTY/EVENT/ACTION_RESULT/RAW: " + payloadType);
        }
        if (topic.getQos() != null && (topic.getQos() < 0 || topic.getQos() > 2)) {
            throw new IllegalArgumentException("QoS 非法: " + topic.getQos());
        }
    }
}
