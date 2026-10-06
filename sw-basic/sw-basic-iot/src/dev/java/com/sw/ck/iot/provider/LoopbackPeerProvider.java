package com.sw.ck.iot.provider;

import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 受控真实传输对端 Provider（P63 G07，仅 dev 源根）。
 * <p>
 * 与 {@link MockCloudProvider} 的进程内模拟不同：本实现把命令经真实 HTTP POST 发送到
 * 独立运行的受控对端进程（{@code sw.iot.loopback.peer-url}），对端在自己的记录里留下
 * 收到指令的事实，并回发 {requestId}；对端随后以 HMAC 签名调用
 * {@code POST /api/iot/commands/receipt} 把设备回执写回同一条命令——构成
 * 「外发受理→对端接收→回执进同命令→结果可查」的真实闭环。
 * 命令行定位：发送时刻该设备处于 SENDING 的 RESERVATION: 命令（预约链串行认领）。
 * 本类位于 {@code src/dev/java}，只有 {@code -Pdev} 构建编入 main 输出；
 * 生产选择器 {@code IotAutoConfiguration} 不引用本实现，正式制品不含。
 * </p>
 */
public class LoopbackPeerProvider implements DeviceControlProvider {

    private static final Logger log = LoggerFactory.getLogger(LoopbackPeerProvider.class);

    private final String peerUrl;
    private final IotDeviceCommandMapper commandMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public LoopbackPeerProvider(String peerUrl, IotDeviceCommandMapper commandMapper) {
        this.peerUrl = peerUrl;
        this.commandMapper = commandMapper;
    }

    @Override
    public String queryDeviceStatus(String productId, String deviceName) {
        return "online";
    }

    @Override
    public DeviceControlResult controlDeviceData(String productId, String deviceName, String propertyJson) {
        return send(productId, deviceName, "PROPERTY", null, propertyJson);
    }

    @Override
    public DeviceControlResult callDeviceActionSync(String productId, String deviceName,
                                                    String actionId, String inputJson) {
        return send(productId, deviceName, "ACTION", actionId, inputJson);
    }

    private DeviceControlResult send(String productId, String deviceName,
                                     String commandType, String actionId, String payload) {
        IotDeviceCommand command = currentSendingCommand(productId, deviceName);
        Long commandId = command == null ? null : command.getId();
        Long tenantId = command == null ? null : command.getTenantId();
        String requestId = "peer-" + UUID.randomUUID().toString().substring(0, 12);
        String body = "{\"commandId\":" + commandId
                + ",\"tenantId\":" + tenantId
                + ",\"productId\":\"" + jsonEscape(productId)
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
            log.info("受控对端已接收命令: commandId={}, device={}, requestId={}",
                    commandId, deviceName, requestId);
            return DeviceControlResult.success(requestId);
        } catch (Exception e) {
            return DeviceControlResult.failure("受控对端不可达: " + e.getMessage());
        }
    }

    /**
     * 发送时刻的当前命令行：该设备 SENDING 状态的 RESERVATION: 命令（预约链在认领事务内
     * 串行发送，同设备同时刻至多一条 SENDING；命中多条取最新并发场景由对端记录回查兜底）。
     */
    private IotDeviceCommand currentSendingCommand(String productId, String deviceName) {
        try {
            List<IotDeviceCommand> rows = commandMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<IotDeviceCommand>lambdaQuery()
                            .eq(IotDeviceCommand::getProductId, productId)
                            .eq(IotDeviceCommand::getDeviceName, deviceName)
                            .eq(IotDeviceCommand::getStatus, "SENDING")
                            .likeRight(IotDeviceCommand::getIdempotentKey, "RESERVATION:")
                            .orderByDesc(IotDeviceCommand::getId)
                            .last("LIMIT 1"));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("受控对端定位命令行失败（回执将由对端凭对端记录兜底关联）: {}", e.getMessage());
            return null;
        }
    }

    private static String jsonEscape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
