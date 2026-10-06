package com.sw.ck.iot.config;

import com.sw.ck.iot.mapper.IotDeviceCommandMapper;
import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.provider.LoopbackPeerProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 受控真实传输对端 Provider 装配（P63 G07，仅 dev 源根）。
 *
 * <p>归属边界：与 {@code MockDeviceControlProviderConfiguration} 同处 {@code src/dev/java}，
 * 只在 {@code -Pdev} 构建下进入编译输出与 classpath；正式制品不含本实现，生产选择器
 * {@code IotAutoConfiguration} 不引用。生效条件：{@code sw.iot.enabled=true} 且
 * {@code sw.iot.tencent.provider-mode=loopback}，对端地址取 {@code sw.iot.loopback.peer-url}；
 * 三种 provider-mode（tencent/mock/loopback）条件互斥，不会双装配。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class LoopbackPeerProviderConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "sw.iot.tencent", name = "provider-mode", havingValue = "loopback")
    static class LoopbackProviderBean {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(LoopbackProviderBean.class);

        @Bean
        @ConditionalOnMissingBean(DeviceControlProvider.class)
        DeviceControlProvider loopbackPeerProvider(
                org.springframework.core.env.Environment env,
                IotDeviceCommandMapper commandMapper) {
            String peerUrl = env.getProperty("sw.iot.loopback.peer-url",
                    "http://127.0.0.1:9777/device");
            log.info("装配受控真实传输对端 Provider（仅 dev 构建）：peer-url={}", peerUrl);
            return new LoopbackPeerProvider(peerUrl, commandMapper);
        }
    }
}
