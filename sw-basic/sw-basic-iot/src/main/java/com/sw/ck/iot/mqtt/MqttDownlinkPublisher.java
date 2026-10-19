package com.sw.ck.iot.mqtt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.service.IotConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 自建 MQTT 连接的下行通道：目标解析与真实发布（原生 MQTT 设备控制通道）。
 *
 * <p>设备控制不应只支持云厂商 provider：由自建 MQTT 连接承载的设备（连接存在、启用、
 * 类型 {@code MQTT}）经本组件下发——连接的下行主题（{@code DOWN}/{@code BOTH} 首条，
 * {@code {deviceKey}} 占位替换）承载 payload，发布结果以 Broker 确认为准
 * （QoS≥1 时 PUBACK 即「Broker 已接收」，不等价于设备已执行）。</p>
 *
 * <p>本组件只做通道能力，不写命令表也不改状态：调用方（延迟控制/预约到点/补偿）负责
 * 命令生命周期。连接未建立时常驻客户端按需拉起（复用 {@link IotConnectionService#connect}
 * 的凭据解密与订阅恢复语义）；通道缺失（无启用连接、非 MQTT、无可用下行主题）一律显式
 * 失败，不返回模拟成功。</p>
 */
@Component
public class MqttDownlinkPublisher {

    /** 连接类型：自建 MQTT（与 {@code IotConnectionService.TYPE_MQTT} 同值，避免跨类耦合）。 */
    private static final String TYPE_MQTT = "MQTT";

    private static final Logger log = LoggerFactory.getLogger(MqttDownlinkPublisher.class);

    private final IotConnectionMapper connectionMapper;
    private final IotTopicMapper topicMapper;
    private final MqttBrokerManager mqttBrokerManager;
    private final ObjectProvider<IotConnectionService> connectionServiceProvider;

    public MqttDownlinkPublisher(IotConnectionMapper connectionMapper,
                                 IotTopicMapper topicMapper,
                                 MqttBrokerManager mqttBrokerManager,
                                 ObjectProvider<IotConnectionService> connectionServiceProvider) {
        this.connectionMapper = connectionMapper;
        this.topicMapper = topicMapper;
        this.mqttBrokerManager = mqttBrokerManager;
        this.connectionServiceProvider = connectionServiceProvider;
    }

    /**
     * 设备是否由启用中的自建 MQTT 连接承载（原生命令通道可用性判据）。
     */
    public boolean isMqttBacked(IotDevice device) {
        return resolveEnabledMqttConnection(device) != null;
    }

    /**
     * 解析设备下行目标（{@code DOWN}/{@code BOTH} 首条启用主题，替换 {@code {deviceKey}}），
     * 连同该主题配置的 QoS 一并返回。
     */
    public Optional<DownlinkTarget> resolveDownlink(IotConnection conn, String deviceKey) {
        IotTopic downTopic = topicMapper.selectOne(new LambdaQueryWrapper<IotTopic>()
                .eq(IotTopic::getConnId, conn.getId())
                .in(IotTopic::getDirection, "DOWN", "BOTH")
                .eq(IotTopic::getEnabled, 1)
                .last("limit 1"));
        if (downTopic == null) {
            return Optional.empty();
        }
        return Optional.of(new DownlinkTarget(substituteDeviceKey(downTopic.getTopic(), deviceKey),
                downTopic.getQos() == null ? 1 : downTopic.getQos()));
    }

    /**
     * 向设备的自建 MQTT 通道发布下行 payload。
     *
     * @param device      设备行（须已解析到本租户）
     * @param payload     下行内容
     * @param qosOverride 覆盖 QoS；null 时取连接下行主题配置（缺省 1）
     * @return 发布结果；设备不由自建 MQTT 连接承载时为空（调用方走其他通道）
     */
    public Optional<PublishOutcome> publishDownlink(IotDevice device, String payload, Integer qosOverride) {
        IotConnection conn = resolveEnabledMqttConnection(device);
        if (conn == null) {
            return Optional.empty();
        }
        DownlinkTarget target = resolveDownlink(conn, device.getDeviceKey())
                .orElseThrow(() -> new IllegalStateException(
                        "无可用下行主题: connId=" + conn.getId() + ", deviceKey=" + device.getDeviceKey()));
        ensureConnected(conn);
        int qos = qosOverride == null ? target.qos() : qosOverride;
        Map<String, Object> result = mqttBrokerManager.publish(conn.getId(), target.topic(), payload, qos, false);
        boolean brokerAck = Boolean.TRUE.equals(result.get("brokerAck"));
        Integer messageId = result.get("messageId") instanceof Number number ? number.intValue() : null;
        log.info("MQTT 下行已发布: deviceKey={}, connId={}, topic={}, qos={}, brokerAck={}, messageId={}",
                device.getDeviceKey(), conn.getId(), target.topic(), qos, brokerAck, messageId);
        return Optional.of(new PublishOutcome(conn.getId(), target.topic(), qos, brokerAck, messageId));
    }

    // ---------------- 内部 ----------------

    private IotConnection resolveEnabledMqttConnection(IotDevice device) {
        if (device == null || device.getConnectionId() == null) {
            return null;
        }
        IotConnection conn = connectionMapper.selectById(device.getConnectionId());
        if (conn == null || conn.getEnabled() == null || conn.getEnabled() != 1
                || !TYPE_MQTT.equals(conn.getConnType())) {
            return null;
        }
        return conn;
    }

    private void ensureConnected(IotConnection conn) {
        if (mqttBrokerManager.isConnected(conn.getId())) {
            return;
        }
        IotConnectionService connectionService = connectionServiceProvider.getIfAvailable();
        if (connectionService == null) {
            throw new IllegalStateException("MQTT 连接服务未装配，无法建立常驻连接: connId=" + conn.getId());
        }
        // 常驻连接建立失败按分类抛出（订阅恢复与凭据解密由连接服务统一承载）
        connectionService.connect(conn.getId());
    }

    private static String substituteDeviceKey(String topic, String deviceKey) {
        if (topic == null) {
            return null;
        }
        return topic.contains("{deviceKey}") && deviceKey != null
                ? topic.replace("{deviceKey}", deviceKey) : topic;
    }

    /**
     * 下行目标：替换占位后的实际主题与其配置 QoS。
     */
    public record DownlinkTarget(String topic, int qos) {
    }

    /**
     * 单次下行发布结果。
     *
     * @param connectionId 承载连接 ID
     * @param topic        实际发布主题
     * @param qos          实际 QoS
     * @param brokerAck    Broker 是否确认接收（QoS≥1 时为 PUBACK）
     * @param messageId    Paho 消息标识（无则 null）
     */
    public record PublishOutcome(Long connectionId, String topic, int qos,
                                 boolean brokerAck, Integer messageId) {
    }
}
