package com.sw.ck.iot.config;

import com.sw.ck.common.exception.BaseException;
import com.sw.ck.iot.entity.IotDevice;
import com.sw.ck.iot.hook.TencentDeviceStatusHook;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.provider.MockCloudProvider;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import com.sw.ck.iot.util.DeferredControlUtil;
import com.sw.ck.iot.util.OnlineConfirmControlUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IoT 开关语义：{@code sw.iot.enabled} 与 provider 装配的关系。
 *
 * <p>G2 目标：IoT 关闭时上下文可启动（属性绑定不再挂在不生效的条件类上，见
 * {@link IotPropertiesAutoConfiguration}）；provider 未装配时，代表性设备控制操作按既有
 * 异常体系 fail closed，绝不返回模拟成功。</p>
 */
class IotFeatureToggleContextTest {

    private static final IotDeviceService IOT_DEVICE_SERVICE = mock(IotDeviceService.class);
    private static final CommandQueueService COMMAND_QUEUE_SERVICE = mock(CommandQueueService.class);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotPropertiesAutoConfiguration.class,
                    IotAutoConfiguration.class))
            .withUserConfiguration(IotComponentsConfiguration.class);

    @BeforeEach
    void stubDeviceLookup() {
        IotDevice device = new IotDevice();
        device.setId(1L);
        device.setProductId("test-product");
        device.setDeviceName("test-device");
        device.setDeviceKey("test-device-key");
        when(IOT_DEVICE_SERVICE.getByProductAndDeviceName(anyString(), anyString())).thenReturn(device);
    }

    @Test
    void disabledIotDoesNotCreateProviderDependentComponents() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DeviceControlProvider.class);
            assertThat(context).doesNotHaveBean(DeferredControlUtil.class);
            assertThat(context).doesNotHaveBean(OnlineConfirmControlUtil.class);
            assertThat(context).doesNotHaveBean(TencentDeviceStatusHook.class);
        });
    }

    /**
     * 生产制品在 provider-mode=mock（或无 provider-mode）下的真实装配：上下文可启动、
     * provider Bean 为 0，两个控制入口存在但在调用点 fail closed（503），不产生模拟成功。
     */
    @Test
    void enabledIotWithoutProvider_startsAndFailsClosedOnControlCalls() {
        contextRunner
                .withPropertyValues(
                        "sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeviceControlProvider.class);
                    assertThat(context).hasSingleBean(DeferredControlUtil.class);
                    assertThat(context).hasSingleBean(OnlineConfirmControlUtil.class);
                    assertThat(context).hasSingleBean(TencentDeviceStatusHook.class);

                    BaseException online = assertThrows(BaseException.class, () ->
                            context.getBean(OnlineConfirmControlUtil.class)
                                    .controlPropertyOnline("test-product", "test-device", "{\"power\":true}"));
                    assertThat(online.getCode()).isEqualTo(503);

                    BaseException deferred = assertThrows(BaseException.class, () ->
                            context.getBean(DeferredControlUtil.class)
                                    .controlProperty("test-product", "test-device", "{\"power\":true}", null));
                    assertThat(deferred.getCode()).isEqualTo(503);
                    // 无控制通道时连"已入队"都不产生：调用点在任何副作用前失败
                    verify(COMMAND_QUEUE_SERVICE, org.mockito.Mockito.never())
                            .enqueue(org.mockito.ArgumentMatchers.any());
                });
    }

    /**
     * dev/mock 入口（{@code -Pdev} 构建才含 {@link MockDeviceControlProviderConfiguration}）：
     * 设备控制可用，证明 mock 归属 dev 源根后开发入口未被边界拆分破坏。
     */
    @Test
    void devMockEntry_controlsDeviceThroughDevProvider() {
        contextRunner
                .withUserConfiguration(MockDeviceControlProviderConfiguration.class)
                .withPropertyValues(
                        "sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeviceControlProvider.class);
                    DeviceControlProvider provider = context.getBean(DeviceControlProvider.class);
                    assertThat(provider).isInstanceOf(MockCloudProvider.class);
                    ((MockCloudProvider) provider).setDeviceStatus("test-product", "test-device", "online");

                    com.sw.ck.iot.entity.IotDeviceCommand command =
                            context.getBean(OnlineConfirmControlUtil.class)
                                    .controlPropertyOnline("test-product", "test-device", "{\"power\":true}");
                    assertThat(command.getStatus()).isEqualTo("SENT");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DeferredControlUtil.class, OnlineConfirmControlUtil.class, TencentDeviceStatusHook.class})
    static class IotComponentsConfiguration {

        @Bean
        IotDeviceService iotDeviceService() {
            return IOT_DEVICE_SERVICE;
        }

        @Bean
        CommandQueueService commandQueueService() {
            return COMMAND_QUEUE_SERVICE;
        }
    }
}
