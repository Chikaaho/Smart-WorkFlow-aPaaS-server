package com.sw.ck.iot.mqtt;

import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.mapper.IotConnectionMapper;
import com.sw.ck.iot.mapper.IotTopicMapper;
import com.sw.ck.iot.service.IotConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqttDownlinkPublisher} 单元测试：设备→连接→下行主题解析、QoS 取主题配置、
 * Broker 确认语义与通道缺失的显式失败。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("自建 MQTT 下行通道")
class MqttDownlinkPublisherTest {

    private static final Long CONN_ID = 88L;

    @Mock
    private IotConnectionMapper connectionMapper;

    @Mock
    private IotTopicMapper topicMapper;

    @Mock
    private MqttBrokerManager mqttBrokerManager;

    @Mock
    private IotConnectionService connectionService;

    private MqttDownlinkPublisher publisher;

    @BeforeEach
    void setUp() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("iotConnectionService", connectionService);
        ObjectProvider<IotConnectionService> provider = factory.getBeanProvider(IotConnectionService.class);
        publisher = new MqttDownlinkPublisher(connectionMapper, topicMapper, mqttBrokerManager, provider);
    }

    private IotDevice device() {
        IotDevice device = new IotDevice();
        device.setId(7L);
        device.setTenantId(9001L);
        device.setDeviceKey("drill-room-a");
        device.setConnectionId(CONN_ID);
        return device;
    }

    private IotConnection connection(String connType, int enabled) {
        IotConnection conn = new IotConnection();
        conn.setId(CONN_ID);
        conn.setConnType(connType);
        conn.setEnabled(enabled);
        return conn;
    }

    private IotTopic downTopic(String topic, Integer qos) {
        IotTopic topicRow = new IotTopic();
        topicRow.setConnId(CONN_ID);
        topicRow.setTopic(topic);
        topicRow.setQos(qos);
        topicRow.setDirection("DOWN");
        topicRow.setEnabled(1);
        return topicRow;
    }

    @Test
    @DisplayName("设备承载判定：仅启用中的 MQTT 连接算 MQTT 设备")
    void isMqttBacked_onlyForEnabledMqttConnection() {
        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("MQTT", 1));
        assertThat(publisher.isMqttBacked(device())).isTrue();

        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("MQTT", 0));
        assertThat(publisher.isMqttBacked(device())).as("停用连接不算 MQTT 通道").isFalse();

        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("TENCENT", 1));
        assertThat(publisher.isMqttBacked(device())).as("云厂商连接不算 MQTT 通道").isFalse();

        assertThat(publisher.isMqttBacked(null)).as("设备缺失不猜通道").isFalse();
    }

    @Test
    @DisplayName("发布：主题占位替换 + QoS 取主题配置 + Broker 确认回传")
    void publishDownlink_usesConfiguredTopicAndQos() {
        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("MQTT", 1));
        when(topicMapper.selectOne(any())).thenReturn(downTopic("nat/{deviceKey}/cmd", 2));
        when(mqttBrokerManager.isConnected(CONN_ID)).thenReturn(true);
        when(mqttBrokerManager.publish(eq(CONN_ID), anyString(), anyString(), anyInt(), anyBoolean()))
                .thenReturn(Map.of("brokerAck", true, "messageId", 4242));

        Optional<MqttDownlinkPublisher.PublishOutcome> outcome =
                publisher.publishDownlink(device(), "[\"机房断电\"]", null);

        assertThat(outcome).isPresent();
        assertThat(outcome.get().topic()).isEqualTo("nat/drill-room-a/cmd");
        assertThat(outcome.get().qos()).as("未显式覆盖时取下行主题配置").isEqualTo(2);
        assertThat(outcome.get().brokerAck()).isTrue();
        assertThat(outcome.get().messageId()).isEqualTo(4242);
        verify(connectionService, never()).connect(any());
    }

    @Test
    @DisplayName("发布：常驻连接未建立时按需拉起后再发布")
    void publishDownlink_connectsLazilyWhenNotConnected() {
        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("MQTT", 1));
        when(topicMapper.selectOne(any())).thenReturn(downTopic("nat/{deviceKey}/cmd", null));
        when(mqttBrokerManager.isConnected(CONN_ID)).thenReturn(false);
        when(mqttBrokerManager.publish(eq(CONN_ID), anyString(), anyString(), anyInt(), anyBoolean()))
                .thenReturn(Map.of("brokerAck", true, "messageId", 7));

        MqttDownlinkPublisher.PublishOutcome outcome =
                publisher.publishDownlink(device(), "{}", null).orElseThrow();

        verify(connectionService).connect(CONN_ID);
        assertThat(outcome.qos()).as("主题未配 QoS 时缺省 1").isEqualTo(1);
    }

    @Test
    @DisplayName("通道缺失：无下行主题显式失败，不伪装成功")
    void publishDownlink_withoutDownTopic_failsExplicitly() {
        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("MQTT", 1));
        when(topicMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> publisher.publishDownlink(device(), "{}", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无可用下行主题");
        verify(mqttBrokerManager, never()).publish(anyLong(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    @DisplayName("非 MQTT 设备：返回空（调用方走云厂商通道），不触碰 Broker")
    void publishDownlink_returnsEmptyForNonMqttDevice() {
        when(connectionMapper.selectById(CONN_ID)).thenReturn(connection("TENCENT", 1));

        assertThat(publisher.publishDownlink(device(), "{}", null)).isEmpty();
        verify(mqttBrokerManager, never()).publish(anyLong(), anyString(), anyString(), anyInt(), anyBoolean());
    }
}
