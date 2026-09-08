package com.sw.ck.iot.mqtt;

import com.sw.ck.iot.entity.IotConnection;
import com.sw.ck.iot.entity.IotTopic;
import com.sw.ck.iot.service.IotConnectionService;
import com.sw.ck.iot.service.MessageIngestService;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自建 MQTT Broker 连接管理器。
 * <p>
 * 每个启用的 MQTT 连接维护一个 Paho v3 接口协议客户端（autoReconnect + 订阅恢复）；
 * 上行消息统一交给 {@link MessageIngestService}；发布返回 Broker 接收状态。
 * </p>
 */
@Service
public class MqttBrokerManager {

    private static final Logger log = LoggerFactory.getLogger(MqttBrokerManager.class);

    public static final String TEST_SUCCESS = "SUCCESS";
    public static final String TEST_DNS = "DNS_UNREACHABLE";
    public static final String TEST_NETWORK = "NETWORK_UNREACHABLE";
    public static final String TEST_AUTH = "AUTH_FAILED";
    public static final String TEST_TLS = "TLS_FAILED";
    public static final String TEST_PROTOCOL = "PROTOCOL_FAILED";

    private final MessageIngestService messageIngestService;
    private final Map<Long, MqttAsyncClient> clients = new ConcurrentHashMap<>();

    public MqttBrokerManager(MessageIngestService messageIngestService) {
        this.messageIngestService = messageIngestService;
    }

    /**
     * 独立连接测试（不入连接池）。返回 [category, detail]。
     */
    public String[] testConnect(IotConnection conn) {
        String clientId = clientId(conn, "probe");
        try {
            MqttAsyncClient probe = new MqttAsyncClient(brokerUri(conn), clientId, new MemoryPersistence());
            try {
                probe.connect(buildOptions(conn)).waitForCompletion(10_000);
                return new String[]{TEST_SUCCESS, "连接建立成功"};
            } finally {
                try {
                    if (probe.isConnected()) {
                        probe.disconnect().waitForCompletion(3_000);
                    }
                    probe.close();
                } catch (MqttException ignore) {
                    // probe 清理失败不影响测试结论
                }
            }
        } catch (MqttException e) {
            return new String[]{classify(e), "连接失败: " + e.getMessage()};
        } catch (IllegalArgumentException e) {
            return new String[]{TEST_PROTOCOL, "连接参数非法: " + e.getMessage()};
        }
    }

    /**
     * 确保连接已建立并完成订阅恢复（幂等）。
     */
    public synchronized MqttAsyncClient ensureStarted(IotConnection conn, List<IotTopic> subscriptions) {
        MqttAsyncClient client = clients.get(conn.getId());
        try {
            if (client == null) {
                client = new MqttAsyncClient(brokerUri(conn), clientId(conn, "main"), new MemoryPersistence());
                final long connId = conn.getId();
                client.setCallback(new MqttCallback() {
                    @Override
                    public void connectionLost(Throwable cause) {
                        log.warn("MQTT 连接断开，等待 autoReconnect: connId={}, {}",
                                connId, cause == null ? "" : cause.getMessage());
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        try {
                            messageIngestService.ingest(connId, topic,
                                    new String(message.getPayload(), StandardCharsets.UTF_8),
                                    message.getQos(), String.valueOf(message.getId()));
                        } catch (Exception e) {
                            // 单条消息失败不得拖垮消费线程
                            log.error("消息处理失败（已隔离）: topic={}, error={}", topic, e.getMessage(), e);
                        }
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                        // 发布确认由 publish() 同步等待
                    }
                });
                client.connect(buildOptions(conn)).waitForCompletion(15_000);
                clients.put(conn.getId(), client);
                log.info("MQTT 连接已建立: connId={}, broker={}", conn.getId(), brokerUri(conn));
            }
            for (IotTopic topic : subscriptions) {
                subscribe(client, topic);
            }
            return client;
        } catch (MqttException e) {
            clients.remove(conn.getId());
            throw new IllegalStateException("MQTT 连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 增加或恢复一条订阅。
     */
    public void subscribe(long connId, IotTopic topic) {
        MqttAsyncClient client = clients.get(connId);
        if (client != null && client.isConnected()) {
            subscribe(client, topic);
        }
    }

    private void subscribe(MqttAsyncClient client, IotTopic topic) {
        try {
            client.subscribe(topic.getTopic(), topic.getQos() == null ? 1 : topic.getQos())
                    .waitForCompletion(10_000);
            log.info("MQTT 订阅生效: topic={}, qos={}", topic.getTopic(), topic.getQos());
        } catch (MqttException e) {
            throw new IllegalStateException("订阅失败: " + topic.getTopic() + " - " + e.getMessage(), e);
        }
    }

    /**
     * 发布消息（等待 Broker 确认）。
     *
     * @return 发布结果：brokerAck 是否确认、服务端消息标识
     */
    public Map<String, Object> publish(long connId, String topic, String payload, int qos, boolean retain) {
        MqttAsyncClient client = clients.get(connId);
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("连接未建立，无法发布: connId=" + connId);
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retain);
            IMqttDeliveryToken token = client.publish(topic, message);
            token.waitForCompletion(15_000);
            Map<String, Object> result = new HashMap<>();
            result.put("brokerAck", token.isComplete());
            result.put("messageId", token.getMessageId());
            return result;
        } catch (MqttException e) {
            throw new IllegalStateException("发布失败: " + e.getMessage(), e);
        }
    }

    public boolean isConnected(long connId) {
        MqttAsyncClient client = clients.get(connId);
        return client != null && client.isConnected();
    }

    /**
     * 断开并移除常驻客户端（配置变更/停用时调用）。
     */
    public synchronized void shutdown(long connId) {
        MqttAsyncClient client = clients.remove(connId);
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(5_000);
            }
            client.close();
        } catch (MqttException e) {
            log.warn("MQTT 客户端关闭失败: connId={}, error={}", connId, e.getMessage());
        }
    }

    // ---------------- 内部 ----------------

    private String classify(MqttException e) {
        int code = e.getReasonCode();
        if (code == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                || code == MqttException.REASON_CODE_NOT_AUTHORIZED) {
            return TEST_AUTH;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.UnknownHostException) {
                return TEST_DNS;
            }
            if (cause instanceof javax.net.ssl.SSLException) {
                return TEST_TLS;
            }
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.SocketTimeoutException) {
                return TEST_NETWORK;
            }
            cause = cause.getCause();
        }
        String name = e.getClass().getSimpleName();
        if (name.contains("SSL")) {
            return TEST_TLS;
        }
        if (name.contains("Timeout") || name.contains("Connect")) {
            return TEST_NETWORK;
        }
        return TEST_PROTOCOL;
    }

    private MqttConnectOptions buildOptions(IotConnection conn) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setUserName(conn.getUsername());
        String password = conn.getPlainPassword();
        if (password != null) {
            options.setPassword(password.toCharArray());
        }
        options.setKeepAliveInterval(conn.getKeepalive() == null ? 60 : conn.getKeepalive());
        options.setCleanSession(conn.getCleanSession() == null || conn.getCleanSession() == 1);
        options.setAutomaticReconnect(true);
        int min = conn.getReconnectMinSec() == null ? 1 : Math.max(1, conn.getReconnectMinSec());
        int max = conn.getReconnectMaxSec() == null ? 60 : Math.max(min, conn.getReconnectMaxSec());
        options.setMaxReconnectDelay(max * 1000);
        options.setConnectionTimeout(10);
        if (conn.getUseTls() != null && conn.getUseTls() == 1) {
            options.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());
        }
        return options;
    }

    private String brokerUri(IotConnection conn) {
        String scheme = conn.getUseTls() != null && conn.getUseTls() == 1 ? "ssl" : "tcp";
        return scheme + "://" + conn.getHost() + ":" + conn.getPort();
    }

    private String clientId(IotConnection conn, String purpose) {
        String prefix = conn.getClientIdPrefix() == null || conn.getClientIdPrefix().isBlank()
                ? "sw-iot" : conn.getClientIdPrefix();
        return prefix + "-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
