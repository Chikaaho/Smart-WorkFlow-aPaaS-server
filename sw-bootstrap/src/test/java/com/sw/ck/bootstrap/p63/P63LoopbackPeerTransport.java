package com.sw.ck.bootstrap.p63;

import com.sw.ck.iot.provider.DeviceControlProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * P63 受控对端传输 fixture（测试源承载，default/CI 构建可用）。
 *
 * <p>背景：{@code src/dev/java} 的 LoopbackPeerProvider 只在 {@code -Pdev} 构建进入
 * sw-basic-iot 主输出；发布流水线的默认构建（{@code mvn clean test}）中该 dev 实现
 * 不在 sw-bootstrap 测试类路径，设备控制 provider 未装配，命令必然 FAILED——本 fixture
 * 在测试上下文内以同名契约装配受控传输：真实 HTTP POST 到 {@link P63ControlledDevicePeer}，
 * HTTP 200 即受理成功（对端是否回发回执由对端模式决定）。被测业务断言（SENT 停留、
 * UNKNOWN 收敛、不重发、人工核实分支）保持不变；本类不进入正式制品。</p>
 */
final class P63LoopbackPeerTransport implements DeviceControlProvider {

    private final String peerUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    P63LoopbackPeerTransport(String peerUrl) {
        this.peerUrl = peerUrl;
    }

    @Override
    public String queryDeviceStatus(String productId, String deviceName) {
        return "online";
    }

    @Override
    public DeviceControlResult controlDeviceData(String productId, String deviceName,
                                                 String propertyJson) {
        return send(productId, deviceName, "PROPERTY", null, propertyJson);
    }

    @Override
    public DeviceControlResult callDeviceActionSync(String productId, String deviceName,
                                                    String actionId, String inputJson) {
        return send(productId, deviceName, "ACTION", actionId, inputJson);
    }

    private DeviceControlResult send(String productId, String deviceName,
                                     String commandType, String actionId, String payload) {
        String requestId = "peer-" + UUID.randomUUID().toString().substring(0, 12);
        String body = "{\"productId\":\"" + jsonEscape(productId)
                + "\",\"deviceName\":\"" + jsonEscape(deviceName)
                + "\",\"commandType\":\"" + commandType
                + "\",\"actionId\":" + (actionId == null ? "null" : "\"" + jsonEscape(actionId) + "\"")
                + ",\"payload\":" + (payload == null ? "null" : payload)
                + ",\"requestId\":\"" + requestId + "\"}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(peerUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return DeviceControlResult.failure("受控对端拒绝受理: HTTP " + response.statusCode());
            }
            return DeviceControlResult.success(requestId);
        } catch (Exception e) {
            return DeviceControlResult.failure("受控对端不可达: " + e.getMessage());
        }
    }

    private static String jsonEscape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
