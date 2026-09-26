package com.sw.ck.iot.util;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.IotDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnlineConfirmControlUtilTest {

    @Mock
    private IotDeviceService iotDeviceService;

    @Mock
    private DeviceControlProvider deviceControlProvider;

    private OnlineConfirmControlUtil onlineConfirmControlUtil;

    private OnlineConfirmControlUtil onlineConfirmControlUtilWithoutProvider;

    private IotDevice testDevice;

    @BeforeEach
    void setUp() {
        // provider 可缺省：装配存在与否决定控制通道是否可用
        onlineConfirmControlUtil = new OnlineConfirmControlUtil(iotDeviceService,
                provider(deviceControlProvider));
        onlineConfirmControlUtilWithoutProvider = new OnlineConfirmControlUtil(iotDeviceService,
                provider(null));

        testDevice = new IotDevice();
        testDevice.setId(1L);
        testDevice.setProductId("test-product");
        testDevice.setDeviceName("test-device");
        testDevice.setDeviceKey("test-device-key");
        testDevice.setStatus("ONLINE");
    }

    /** 真实 ObjectProvider：注册了 provider 即 getIfAvailable() 返回它，否则返回 null。 */
    private static ObjectProvider<DeviceControlProvider> provider(DeviceControlProvider value) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (value != null) {
            factory.registerSingleton("deviceControlProvider", value);
        }
        return factory.getBeanProvider(DeviceControlProvider.class);
    }

    @Test
    void testControlPropertyOnline_DeviceOffline() {
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("offline");

        assertThrows(BaseException.class, () ->
                onlineConfirmControlUtil.controlPropertyOnline(
                        "test-product", "test-device", "{\"power\":true}"));
    }

    @Test
    void testControlPropertyOnline_DeviceOnline() {
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("online");
        when(deviceControlProvider.controlDeviceData("test-product", "test-device", "{\"power\":true}"))
                .thenReturn(DeviceControlProvider.DeviceControlResult.success("req-123"));
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(testDevice);

        IotDeviceCommand result = onlineConfirmControlUtil.controlPropertyOnline(
                "test-product", "test-device", "{\"power\":true}");

        assertNotNull(result);
        assertEquals("SENT", result.getStatus());
        assertEquals("req-123", result.getTencentRequestId());
        assertEquals("ONLINE_CONFIRM", result.getSemanticMode());
    }

    @Test
    void testControlPropertyOnline_TencentApiFailure() {
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("online");
        when(deviceControlProvider.controlDeviceData("test-product", "test-device", "{\"power\":true}"))
                .thenReturn(DeviceControlProvider.DeviceControlResult.failure("API 调用失败"));

        assertThrows(BaseException.class, () ->
                onlineConfirmControlUtil.controlPropertyOnline(
                        "test-product", "test-device", "{\"power\":true}"));
    }

    @Test
    void testControlActionOnline_DeviceOffline() {
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("offline");

        assertThrows(BaseException.class, () ->
                onlineConfirmControlUtil.controlActionOnline(
                        "test-product", "test-device", "power_on", "{}"));
    }

    @Test
    void testControlActionOnline_DeviceOnline() {
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("online");
        when(deviceControlProvider.callDeviceActionSync("test-product", "test-device", "power_on", "{}"))
                .thenReturn(DeviceControlProvider.DeviceControlResult.success("req-123", "client-token", "{\"result\":\"ok\"}"));
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(testDevice);

        IotDeviceCommand result = onlineConfirmControlUtil.controlActionOnline(
                "test-product", "test-device", "power_on", "{}");

        assertNotNull(result);
        assertEquals("ACKED", result.getStatus());
        assertEquals("req-123", result.getTencentRequestId());
        assertEquals("client-token", result.getClientToken());
        assertEquals("{\"result\":\"ok\"}", result.getDeviceOutput());
        assertEquals("ONLINE_CONFIRM", result.getSemanticMode());
    }

    /**
     * G2 反向断言：无 provider 时属性控制必须显式失败（503），且不返回任何命令对象、
     * 不产生"已确认/已下发"的模拟结果。
     */
    @Test
    void testControlPropertyOnline_withoutProvider_failsClosed() {
        BaseException ex = assertThrows(BaseException.class, () ->
                onlineConfirmControlUtilWithoutProvider.controlPropertyOnline(
                        "test-product", "test-device", "{\"power\":true}"));

        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("未装配"));
        verifyNoInteractions(deviceControlProvider);
    }

    /**
     * G2 反向断言：无 provider 时行为控制同样在调用点显式失败，不做任何腾讯调用。
     */
    @Test
    void testControlActionOnline_withoutProvider_failsClosed() {
        BaseException ex = assertThrows(BaseException.class, () ->
                onlineConfirmControlUtilWithoutProvider.controlActionOnline(
                        "test-product", "test-device", "power_on", "{}"));

        assertEquals(503, ex.getCode());
        verify(deviceControlProvider, never()).callDeviceActionSync(anyString(), anyString(), anyString(), anyString());
    }
}
