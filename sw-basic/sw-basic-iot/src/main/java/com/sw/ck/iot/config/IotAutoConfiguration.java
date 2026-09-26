package com.sw.ck.iot.config;

import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.provider.TencentCloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * IoT 接入自动配置。
 *
 * <p>默认关闭，通过 {@code sw.iot.enabled=true} 开启。生产侧只装配腾讯云 Provider：
 * {@code sw.iot.tencent.provider-mode=tencent} 且凭证完整时创建 {@link TencentCloudProvider}；
 * provider-mode 不是 tencent（未配置、none 或 dev/mock）时不装配任何 provider——需要 provider
 * 的操作由调用点按既有异常体系 fail closed，不返回模拟成功。</p>
 *
 * <p>模拟实现只存在于本模块 dev 源根 {@code src/dev/java}，由该源根内的 dev 装配类在
 * {@code -Pdev} 构建下装配；本类不引用它，正式 Boot Jar 中也不存在该类。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class IotAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotAutoConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "sw.iot.tencent", name = "provider-mode", havingValue = "tencent")
    public DeviceControlProvider deviceControlProvider(TencentCloudProperties properties) {
        if (!properties.hasCredentials()) {
            throw new IllegalStateException(
                    "sw.iot.tencent.provider-mode=tencent 但未配置 SecretId/SecretKey，"
                    + "腾讯 IoT Provider 无法初始化。请通过安全配置或环境变量注入真实凭证；"
                    + "本制品不提供模拟实现，未配置凭证时设备控制操作将 fail closed。");
        }
        log.info("使用腾讯云 IoT Explorer Provider");
        return new TencentCloudProvider(properties);
    }
}
