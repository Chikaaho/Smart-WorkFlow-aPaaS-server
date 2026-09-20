package com.sw.ck.iot.config;

import com.sw.ck.iot.hook.TencentDeviceStatusHook;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.service.CommandQueueService;
import com.sw.ck.iot.service.IotDeviceService;
import com.sw.ck.iot.util.DeferredControlUtil;
import com.sw.ck.iot.util.OnlineConfirmControlUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IotFeatureToggleContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class))
            .withUserConfiguration(IotComponentsConfiguration.class);

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

    @Test
    void enabledIotCreatesMockProviderAndDependentComponents() {
        contextRunner
                .withPropertyValues(
                        "sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeviceControlProvider.class);
                    assertThat(context).hasSingleBean(DeferredControlUtil.class);
                    assertThat(context).hasSingleBean(OnlineConfirmControlUtil.class);
                    assertThat(context).hasSingleBean(TencentDeviceStatusHook.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({DeferredControlUtil.class, OnlineConfirmControlUtil.class, TencentDeviceStatusHook.class})
    static class IotComponentsConfiguration {

        @Bean
        IotDeviceService iotDeviceService() {
            return mock(IotDeviceService.class);
        }

        @Bean
        CommandQueueService commandQueueService() {
            return mock(CommandQueueService.class);
        }
    }
}
