package com.sw.ck.iot.util;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeferredControlUtilTest {

    @Mock
    private IotDeviceService iotDeviceService;

    @Mock
    private CommandQueueService commandQueueService;

    @Mock
    private DeviceControlProvider deviceControlProvider;

    private DeferredControlUtil deferredControlUtil;

    private DeferredControlUtil deferredControlUtilWithoutProvider;

    private IotDevice testDevice;

    @BeforeEach
    void setUp() {
        // provider 可缺省：装配存在与否决定控制通道是否可用
        deferredControlUtil = new DeferredControlUtil(iotDeviceService, commandQueueService,
                provider(deviceControlProvider));
        deferredControlUtilWithoutProvider = new DeferredControlUtil(iotDeviceService, commandQueueService,
                provider(null));

        testDevice = new IotDevice();
        testDevice.setId(1L);
        testDevice.setProductId("test-product");
        testDevice.setDeviceName("test-device");
        testDevice.setDeviceKey("test-device-key");
        testDevice.setStatus("OFFLINE");
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
    void testControlProperty_DeviceOffline() {
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(testDevice);
        when(commandQueueService.enqueue(any(IotDeviceCommand.class))).thenAnswer(invocation -> {
            IotDeviceCommand cmd = invocation.getArgument(0);
            cmd.setId(1L);
            cmd.setStatus("QUEUED");
            return cmd;
        });
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("offline");

        IotDeviceCommand result = deferredControlUtil.controlProperty(
                "test-product", "test-device", "{\"power\":true}", null);

        assertNotNull(result);
        assertEquals("QUEUED", result.getStatus());
        verify(commandQueueService).enqueue(any(IotDeviceCommand.class));
        verify(deviceControlProvider).queryDeviceStatus("test-product", "test-device");
    }

    @Test
    void testControlProperty_DeviceOnline() {
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(testDevice);
        when(commandQueueService.enqueue(any(IotDeviceCommand.class))).thenAnswer(invocation -> {
            IotDeviceCommand cmd = invocation.getArgument(0);
            cmd.setId(1L);
            return cmd;
        });
        when(deviceControlProvider.queryDeviceStatus("test-product", "test-device")).thenReturn("online");
        when(deviceControlProvider.controlDeviceData("test-product", "test-device", "{\"power\":true}"))
                .thenReturn(DeviceControlProvider.DeviceControlResult.success("req-123"));
        when(commandQueueService.markSending(1L)).thenAnswer(invocation -> {
            IotDeviceCommand cmd = new IotDeviceCommand();
            cmd.setId(1L);
            cmd.setStatus("SENDING");
            return cmd;
        });
        when(commandQueueService.markSent(1L, "req-123")).thenAnswer(invocation -> {
            IotDeviceCommand cmd = new IotDeviceCommand();
            cmd.setId(1L);
            cmd.setStatus("SENT");
            return cmd;
        });

        IotDeviceCommand result = deferredControlUtil.controlProperty(
                "test-product", "test-device", "{\"power\":true}", null);

        assertNotNull(result);
        verify(commandQueueService).enqueue(any(IotDeviceCommand.class));
        verify(deviceControlProvider).controlDeviceData("test-product", "test-device", "{\"power\":true}");
    }

    @Test
    void testControlProperty_DeviceNotFound() {
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(null);

        assertThrows(com.sw.ck.common.exception.BaseException.class, () ->
                deferredControlUtil.controlProperty(
                        "test-product", "test-device", "{\"power\":true}", null));
    }

    /**
     * G2 反向断言：无 provider 时延迟控制必须在入队前显式失败（503），
     * 既不产生"已受理"的队列记录，也不做任何腾讯调用。
     */
    @Test
    void testControlProperty_withoutProvider_failsClosedBeforeEnqueue() {
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(testDevice);

        BaseException ex = assertThrows(BaseException.class, () ->
                deferredControlUtilWithoutProvider.controlProperty(
                        "test-product", "test-device", "{\"power\":true}", null));

        assertEquals(503, ex.getCode());
        assertTrue(ex.getMessage().contains("未装配"));
        verify(commandQueueService, never()).enqueue(any(IotDeviceCommand.class));
        verifyNoInteractions(deviceControlProvider);
    }

    /**
     * G2 反向断言：无 provider 时唯一发送路径必须记为可重试失败，
     * 不进入 SENDING、绝不标记为已发送，也不出现模拟成功。
     */
    @Test
    void testSendCommand_withoutProvider_marksFailedNeverSent() {
        IotDeviceCommand command = new IotDeviceCommand();
        command.setId(7L);
        command.setProductId("test-product");
        command.setDeviceName("test-device");
        command.setCommandType("PROPERTY");
        command.setPayload("{\"power\":true}");

        deferredControlUtilWithoutProvider.sendCommand(command);

        verify(commandQueueService).markFailed(7L, DeferredControlUtil.CHANNEL_UNAVAILABLE_REASON);
        verify(commandQueueService, never()).markSending(anyLong());
        verify(commandQueueService, never()).markSent(anyLong(), anyString());
        verifyNoInteractions(deviceControlProvider);
    }
}
