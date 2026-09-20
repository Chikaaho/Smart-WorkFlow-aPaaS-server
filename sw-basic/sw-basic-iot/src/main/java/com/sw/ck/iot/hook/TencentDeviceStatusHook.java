package com.sw.ck.iot.hook;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.service.IotDeviceService;
import com.sw.ck.iot.util.DeferredControlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * 腾讯云设备状态事件 Hook（对齐 Demo 实现）。
 * <p>
 * 接收腾讯规则引擎转发的设备上线/下线状态变化通知。
 * 支持 JSON 对象和 Base64 编码的 Payload。
 * 收到上线事件后快速返回 HTTP 成功，再异步触发该设备待发送队列。
 * </p>
 * <p>
 * 对齐 Demo：IotDeviceCallbackController + IotDeviceCallbackServiceImpl
 * 使用 Fastjson2 处理 JSON（与 Demo 使用 JSONObject 风格一致）。
 * </p>
 */
@RestController
@RequestMapping("/iot/hook/tencent")
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class TencentDeviceStatusHook {

    private static final Logger log = LoggerFactory.getLogger(TencentDeviceStatusHook.class);

    /**
     * 腾讯云上线事件类型
     */
    private static final String EVENT_ONLINE = "EV_ONLINE";

    private final IotDeviceService iotDeviceService;
    private final DeferredControlUtil deferredControlUtil;

    public TencentDeviceStatusHook(IotDeviceService iotDeviceService,
                                   DeferredControlUtil deferredControlUtil) {
        this.iotDeviceService = iotDeviceService;
        this.deferredControlUtil = deferredControlUtil;
    }

    /**
     * 校验腾讯云 IoT 回调地址（GET）。
     * <p>
     * 腾讯云规则引擎配置回调地址时会发送 Echostr 进行验证，原样返回即可。
     * </p>
     *
     * @param echoStr 腾讯云下发的随机字符串
     * @return 腾讯云下发的随机字符串
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public String verifyTencentCallback(@RequestHeader(value = "Echostr", required = false) String echoStr) {
        log.info("腾讯云回调地址验证: echoStr={}", echoStr);
        return echoStr;
    }

    /**
     * 接收腾讯云设备状态和物模型事件（POST）。
     * <p>
     * 对齐 Demo：IotDeviceCallbackController.receiveTencentDeviceCallback
     * </p>
     *
     * @param body 请求体（JSON 字符串）
     * @return 是否接收成功
     */
    @PostMapping
    public Map<String, Boolean> handleDeviceStatus(@RequestBody String body) {
        try {
            // 1. 解析外层 JSON
            JSONObject root = JSON.parseObject(body);
            if (root == null) {
                log.warn("[handleDeviceStatus] Payload 解析失败");
                return Map.of("result", false);
            }

            // 2. 提取外层字段（ProductId, DeviceName, Payload）
            String productId = extractField(root, "productID", "ProductId");
            String deviceName = extractField(root, "deviceName", "DeviceName");

            if (productId == null || deviceName == null) {
                log.warn("[handleDeviceStatus] 设备状态回调缺少 IoT 标识: productId={}, deviceName={}",
                        productId, deviceName);
                return Map.of("result", false);
            }

            // 3. 解析 Payload（支持 JSON 对象和 Base64 编码）
            JSONObject eventPayload = parseStatusEvent(root.get("Payload"));

            // 4. 检查事件类型（对齐 Demo：使用 EV_ONLINE）
            String event = eventPayload.getString("event");
            log.info("[handleDeviceStatus][productId({}) deviceName({}) event({})] 接收设备状态回调",
                    productId, deviceName, event);

            if (!EVENT_ONLINE.equals(event)) {
                // 非上线事件，仅记录日志
                log.debug("[handleDeviceStatus] 非上线事件，忽略: event={}", event);
                return Map.of("result", true);
            }

            // 5. 处理设备上线事件
            handleDeviceOnline(productId, deviceName);

            return Map.of("result", true);

        } catch (Exception e) {
            log.error("[handleDeviceStatus] 处理设备状态事件异常", e);
            return Map.of("result", false);
        }
    }

    /**
     * 处理设备上线事件。
     * <p>
     * 对齐 Demo：IotDeviceCallbackServiceImpl.handleDeviceOnline
     * </p>
     */
    private void handleDeviceOnline(String productId, String deviceName) {
        IotDevice device = iotDeviceService.getByProductAndDeviceName(productId, deviceName);
        if (device == null) {
            log.warn("[handleDeviceOnline][productId({}) deviceName({})] 未找到上线设备",
                    productId, deviceName);
            return;
        }

        // 更新设备在线状态
        device.setStatus("ONLINE");
        device.setTencentStatus("online");
        device.setLastOnlineTime(LocalDateTime.now());
        iotDeviceService.updateById(device);

        log.info("[handleDeviceOnline][productId({}) deviceName({})] 设备状态已更新",
                productId, deviceName);

        // 异步触发待发送命令补发
        deferredControlUtil.flushDeviceCommands(productId, deviceName);
    }

    /**
     * 解析设备状态事件，兼容 JSON 对象和 Base64 编码字符串。
     * <p>
     * 对齐 Demo：IotDeviceCallbackServiceImpl.parseStatusEvent
     * </p>
     *
     * @param payload 设备状态事件
     * @return 设备状态事件 JSON
     */
    private JSONObject parseStatusEvent(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("腾讯云 IoT 设备状态回调 Payload 为空");
        }

        if (payload instanceof JSONObject) {
            return (JSONObject) payload;
        }

        if (payload instanceof String) {
            String payloadStr = (String) payload;
            // 尝试 Base64 解码
            try {
                String decoded = new String(Base64.getDecoder().decode(payloadStr), StandardCharsets.UTF_8);
                return JSON.parseObject(decoded);
            } catch (Exception e) {
                // 非 Base64，尝试直接解析 JSON
                return JSON.parseObject(payloadStr);
            }
        }

        throw new IllegalArgumentException("腾讯云 IoT 设备状态回调 Payload 格式不正确");
    }

    /**
     * 从 JSON 对象中提取字段值，支持多个备选字段名。
     */
    private String extractField(JSONObject json, String... fieldNames) {
        for (String fieldName : fieldNames) {
            String value = json.getString(fieldName);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
