package com.sw.ck.iot.util;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.entity.IotDeviceCommand;
import com.sw.ck.iot.mqtt.MqttDownlinkPublisher;
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

import java.util.Optional;

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

    @Mock
    private MqttDownlinkPublisher mqttDownlinkPublisher;

    private DeferredControlUtil deferredControlUtil;

    private DeferredControlUtil deferredControlUtilWithoutProvider;

    private IotDevice testDevice;

    @BeforeEach
    void setUp() {
        // provider 可缺省：装配存在与否决定控制通道是否可用
        deferredControlUtil = new DeferredControlUtil(iotDeviceService, commandQueueService,
                provider(deviceControlProvider), mqttDownlinkPublisher, java.time.Clock.systemUTC());
        deferredControlUtilWithoutProvider = new DeferredControlUtil(iotDeviceService, commandQueueService,
                provider(null), mqttDownlinkPublisher, java.time.Clock.systemUTC());

        testDevice = new IotDevice();
        testDevice.setId(1L);
        testDevice.setTenantId(9001L);
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
        command.setTenantId(9001L);
        command.setDeviceKey("test-device-key");
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

    /**
     * 原生 MQTT 通道：设备由自建 MQTT 连接承载且无云厂商 provider 时，
     * 延迟控制仍可入队并即时外发——Broker 确认即 markSent（不依赖 provider）。
     */
    @Test
    void testControlProperty_mqttDevice_withoutProvider_sendsViaBroker() {
        when(iotDeviceService.getByProductAndDeviceName("test-product", "test-device")).thenReturn(testDevice);
        when(mqttDownlinkPublisher.isMqttBacked(testDevice)).thenReturn(true);
        when(commandQueueService.enqueue(any(IotDeviceCommand.class))).thenAnswer(invocation -> {
            IotDeviceCommand cmd = invocation.getArgument(0);
            cmd.setId(11L);
            return cmd;
        });
        when(iotDeviceService.getByDeviceKeyForTenant(9001L, "test-device-key")).thenReturn(testDevice);
        when(commandQueueService.markSending(11L)).thenAnswer(invocation -> {
            IotDeviceCommand cmd = new IotDeviceCommand();
            cmd.setId(11L);
            return cmd;
        });
        when(mqttDownlinkPublisher.publishDownlink(testDevice, "{\"power\":true}", null))
                .thenReturn(Optional.of(new MqttDownlinkPublisher.PublishOutcome(
                        88L, "sw/9001/cmd/test-device-key", 1, true, 4242)));

        IotDeviceCommand result = deferredControlUtilWithoutProvider.controlProperty(
                "test-product", "test-device", "{\"power\":true}", "inst-1");

        assertNotNull(result);
        assertEquals(9001L, result.getTenantId(), "命令须带显式租户，调度线程才能解析设备");
        verify(mqttDownlinkPublisher).publishDownlink(testDevice, "{\"power\":true}", null);
        verify(commandQueueService).markSent(11L, "MQTT:sw/9001/cmd/test-device-key:4242");
        verify(commandQueueService, never()).markFailed(anyLong(), anyString());
        verifyNoInteractions(deviceControlProvider);
    }

    /**
     * 原生 MQTT 通道反向断言：Broker 未确认时记可重试失败，绝不落「已发送」。
     */
    @Test
    void deniedPersistentClaimDoesNotPublishOrWriteFailure() {
        var command = new IotDeviceCommand();
        command.setId(14L);
        command.setTenantId(9001L);
        command.setDeviceKey("test-device-key");
        when(iotDeviceService.getByDeviceKeyForTenant(9001L, "test-device-key")).thenReturn(testDevice);
        when(mqttDownlinkPublisher.isMqttBacked(testDevice)).thenReturn(true);
        when(commandQueueService.markSending(14L)).thenReturn(null);
        deferredControlUtilWithoutProvider.sendCommand(command);
        verify(mqttDownlinkPublisher, never()).publishDownlink(any(), any(), any());
        verify(commandQueueService, never()).markFailed(anyLong(), anyString());
        verify(commandQueueService, never()).markSent(anyLong(), anyString());
    }

    @Test
    void alreadyClaimedCompensationUsesSameSendWithoutSecondClaim() {
        var command = new IotDeviceCommand();
        command.setId(15L);
        command.setStatus("SENDING");
        command.setTenantId(9001L);
        command.setDeviceKey("test-device-key");
        command.setPayload("{}");
        when(iotDeviceService.getByDeviceKeyForTenant(9001L, "test-device-key")).thenReturn(testDevice);
        when(mqttDownlinkPublisher.isMqttBacked(testDevice)).thenReturn(true);
        when(mqttDownlinkPublisher.publishDownlink(testDevice, "{}", null)).thenReturn(Optional.of(
                new MqttDownlinkPublisher.PublishOutcome(88L, "owned/topic", 1, true, 88)));
        deferredControlUtilWithoutProvider.sendClaimedCommand(command);
        verify(commandQueueService, never()).markSending(anyLong());
        verify(commandQueueService).markSent(15L, "MQTT:owned/topic:88");
    }

    @Test
    void testSendCommand_mqttDevice_withoutBrokerAck_marksFailedNeverSent() {
        IotDeviceCommand command = new IotDeviceCommand();
        command.setId(12L);
        when(commandQueueService.markSending(12L)).thenReturn(command);
        command.setTenantId(9001L);
        command.setDeviceKey("test-device-key");
        command.setCommandType("PROPERTY");
        command.setPayload("{\"power\":true}");
        when(iotDeviceService.getByDeviceKeyForTenant(9001L, "test-device-key")).thenReturn(testDevice);
        when(mqttDownlinkPublisher.isMqttBacked(testDevice)).thenReturn(true);
        when(mqttDownlinkPublisher.publishDownlink(testDevice, "{\"power\":true}", null))
                .thenReturn(Optional.of(new MqttDownlinkPublisher.PublishOutcome(
                        88L, "sw/9001/cmd/test-device-key", 1, false, null)));

        deferredControlUtilWithoutProvider.sendCommand(command);

        verify(commandQueueService).markFailed(12L, DeferredControlUtil.MQTT_NO_BROKER_ACK_REASON);
        verify(commandQueueService, never()).markSent(anyLong(), anyString());
        verifyNoInteractions(deviceControlProvider);
    }

    /**
     * 通道不可用反向断言：MQTT 发布抛出（无下行主题/连接失败）时同样只记可重试失败。
     */
    @Test
    void testSendCommand_mqttDevice_publishThrows_marksFailedNeverSent() {
        IotDeviceCommand command = new IotDeviceCommand();
        command.setId(13L);
        when(commandQueueService.markSending(13L)).thenReturn(command);
        command.setTenantId(9001L);
        command.setDeviceKey("test-device-key");
        command.setCommandType("ACTION");
        command.setPayload("{\"power\":true}");
        when(iotDeviceService.getByDeviceKeyForTenant(9001L, "test-device-key")).thenReturn(testDevice);
        when(mqttDownlinkPublisher.isMqttBacked(testDevice)).thenReturn(true);
        when(mqttDownlinkPublisher.publishDownlink(testDevice, "{\"power\":true}", null))
                .thenThrow(new IllegalStateException("无可用下行主题: connId=88"));

        deferredControlUtilWithoutProvider.sendCommand(command);

        verify(commandQueueService).markFailed(13L, "无可用下行主题: connId=88");
        verify(commandQueueService, never()).markSent(anyLong(), anyString());
    }
}
