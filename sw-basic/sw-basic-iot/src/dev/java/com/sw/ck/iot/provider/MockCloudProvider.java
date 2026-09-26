package com.sw.ck.iot.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 设备控制提供者。
 * <p>
 * 用于开发和测试环境，模拟腾讯云 IoT Explorer API 行为。本类位于 {@code src/dev/java}
 * dev 源根：只有 {@code -Pdev} 构建才把它编入 {@code target/classes}，默认（生产）构建的
 * 编译输入天然看不到它，正式 Boot Jar 中不存在该类。是否装配由
 * {@link com.sw.ck.iot.config.MockDeviceControlProviderConfiguration} 按
 * dev/mock 配置决定，生产选择器（{@link com.sw.ck.iot.config.IotAutoConfiguration}）
 * 不引用本实现。
 * </p>
 */
public class MockCloudProvider implements DeviceControlProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCloudProvider.class);

    /**
     * 模拟设备在线状态（productId:deviceName → status）。
     */
    private final Map<String, String> deviceStatusMap = new ConcurrentHashMap<>();

    @Override
    public String queryDeviceStatus(String productId, String deviceName) {
        String key = productId + ":" + deviceName;
        String status = deviceStatusMap.getOrDefault(key, "offline");
        log.debug("Mock 查询设备状态: productId={}, deviceName={}, status={}", productId, deviceName, status);
        return status;
    }

    @Override
    public DeviceControlResult controlDeviceData(String productId, String deviceName, String propertyJson) {
        String status = queryDeviceStatus(productId, deviceName);
        if (!"online".equals(status)) {
            return DeviceControlResult.failure("设备离线: " + status);
        }

        String requestId = "mock-req-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Mock 属性下发: productId={}, deviceName={}, propertyJson={}, requestId={}",
                productId, deviceName, propertyJson, requestId);
        return DeviceControlResult.success(requestId);
    }

    @Override
    public DeviceControlResult callDeviceActionSync(String productId, String deviceName,
                                                    String actionId, String inputJson) {
        String status = queryDeviceStatus(productId, deviceName);
        if (!"online".equals(status)) {
            return DeviceControlResult.failure("设备离线: " + status);
        }

        String requestId = "mock-req-" + UUID.randomUUID().toString().substring(0, 8);
        String deviceOutput = "{\"result\":\"mock executed\",\"actionId\":\"" + actionId + "\"}";
        log.info("Mock 行为调用: productId={}, deviceName={}, actionId={}, requestId={}",
                productId, deviceName, actionId, requestId);
        return DeviceControlResult.success(requestId, deviceOutput);
    }

    /**
     * 设置模拟设备在线状态（仅用于测试）。
     */
    public void setDeviceStatus(String productId, String deviceName, String status) {
        deviceStatusMap.put(productId + ":" + deviceName, status);
    }
}
