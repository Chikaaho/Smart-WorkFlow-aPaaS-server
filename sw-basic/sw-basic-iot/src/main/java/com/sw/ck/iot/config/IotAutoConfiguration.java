package com.sw.ck.iot.config;

import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.api.impl.IotDeviceFacadeImpl;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.provider.MockCloudProvider;
import com.sw.ck.iot.provider.TencentCloudProvider;
import com.sw.ck.iot.service.IotDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * IoT 接入自动配置。
 * <p>
 * 默认关闭，通过 sw.iot.enabled=true 开启。
 * 根据 sw.iot.tencent.providerMode 配置选择 Mock 或 Tencent Provider。
 * </p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({MqttProperties.class, TencentCloudProperties.class,
        IotCipherProperties.class})
public class IotAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotAutoConfiguration.class);

    @Bean
    public DeviceControlProvider deviceControlProvider(TencentCloudProperties properties) {
        if ("tencent".equals(properties.getProviderMode())) {
            if (!properties.hasCredentials()) {
                throw new IllegalStateException(
                        "sw.iot.tencent.providerMode=tencent 但未配置 SecretId/SecretKey，"
                        + "腾讯 IoT Provider 无法初始化。请配置凭证或切换为 mock 模式。");
            }
            log.info("使用腾讯云 IoT Explorer Provider");
            return new TencentCloudProvider(properties);
        }
        log.info("使用 Mock IoT Provider（providerMode={})", properties.getProviderMode());
        return new MockCloudProvider();
    }
}
