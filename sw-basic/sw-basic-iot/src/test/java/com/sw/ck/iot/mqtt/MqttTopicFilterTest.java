package com.sw.ck.iot.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.2.4 缺陷修复回归：{deviceKey} 模板订阅过滤器必须展开为单层通配 +，
 * 否则字面量订阅导致设备发布永不命中（遥测丢失）。
 */
class MqttTopicFilterTest {

    @Test
    void templatePlaceholderExpandsToSingleLevelWildcard() {
        assertEquals("campus/gate/+/up", MqttBrokerManager.mqttFilterOf("campus/gate/{deviceKey}/up"));
    }

    @Test
    void plainTopicUnchanged() {
        assertEquals("factory/line1/temp", MqttBrokerManager.mqttFilterOf("factory/line1/temp"));
    }

    @Test
    void nullTemplateStaysNull() {
        assertNull(MqttBrokerManager.mqttFilterOf(null));
    }
}
