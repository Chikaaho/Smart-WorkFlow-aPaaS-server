package com.sw.ck.iot.config;

import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.provider.MockCloudProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * dev/mock 运行入口的 IoT provider 装配。
 *
 * <p>归属边界：本类与 {@link MockCloudProvider} 同处 {@code src/dev/java}，只在
 * {@code -Pdev} 构建下进入编译输出与 classpath；正式生产制品中没有它们，生产选择器
 * {@link IotAutoConfiguration} 也不引用模拟实现。生产侧 {@code providerMode=tencent}
 * 时由 {@code IotAutoConfiguration} 装配 {@code TencentCloudProvider}，两者条件互斥，
 * 不会双装配。</p>
 *
 * <p>生效条件：{@code sw.iot.enabled=true} 且 {@code sw.iot.tencent.provider-mode=mock}
 * （dev/local 配置显式给出）。不满足条件时不装配任何 provider，需要 provider 的操作按调用点
 * 的既有异常体系 fail closed。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sw.iot", name = "enabled", havingValue = "true")
public class MockDeviceControlProviderConfiguration {

    /**
     * mock 模式下的 provider 装配。
     *
     * <p>用内嵌配置承载第二个条件：{@code @ConditionalOnProperty} 不可重复标注，
     * 同类别名条件必须在同一位置给出，故按嵌套配置与生产选择器的 tencent 分支互斥。
     * 属性名统一用 kebab 形式，保证 yml（{@code provider-mode}）、环境变量与测试属性三种
     * 来源都能命中同一条条件。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "sw.iot.tencent", name = "provider-mode", havingValue = "mock")
    static class MockProviderBean {

        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(MockProviderBean.class);

        @Bean
        @ConditionalOnMissingBean(DeviceControlProvider.class)
        DeviceControlProvider mockDeviceControlProvider() {
            log.info("使用 dev 模拟 IoT Provider（仅 dev/mock 运行入口，正式制品不含本实现）");
            return new MockCloudProvider();
        }
    }
}
