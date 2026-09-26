package com.sw.ck.iot.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * IoT 配置属性绑定，与 {@code sw.iot.enabled} 开关无关。
 *
 * <p>背景：属性 Bean 原先只在 {@link IotAutoConfiguration}（{@code sw.iot.enabled=true} 才生效）
 * 上通过 {@code @EnableConfigurationProperties} 注册。而组件扫描无条件覆盖 {@code com.sw.ck}，
 * IoT 关闭时仍会装配 {@code IotConnectionService}（依赖 {@link IotCipherProperties}）与
 * {@code CommandCompensationJob}（依赖 {@link TencentCloudProperties}），两者拿不到属性 Bean
 * 便无法创建——「IoT 关闭」在生产侧不可启动。</p>
 *
 * <p>属性绑定本身没有开关语义（纯 POJO，未配置即空值/默认值），因此独立成恒生效的自动配置：
 * 属性始终可绑定，provider 与 IoT 组件的装配条件仍由 {@link IotAutoConfiguration} 与各组件
 * 自身的条件承担。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({MqttProperties.class, TencentCloudProperties.class,
        IotCipherProperties.class})
public class IotPropertiesAutoConfiguration {
}
