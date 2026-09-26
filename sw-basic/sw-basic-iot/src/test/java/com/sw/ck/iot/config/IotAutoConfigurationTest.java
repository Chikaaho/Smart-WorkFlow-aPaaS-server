package com.sw.ck.iot.config;

import com.sw.ck.iot.provider.DeviceControlProvider;
import com.sw.ck.iot.provider.MockCloudProvider;
import com.sw.ck.iot.provider.TencentCloudProvider;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

/**
 * G1 聚焦测试：生产选择器的 provider 装配行为。
 *
 * <p>断言对象是**条件生效后的真实装配结果**（{@link ApplicationContextRunner}），不是对
 * {@code @Bean} 方法的直接调用，因此 {@code @ConditionalOnProperty} 与
 * {@code @ConditionalOnMissingBean} 都参与判定。</p>
 *
 * <p>场景：
 * <ol>
 *   <li>enabled + provider-mode=mock（生产制品里没有 dev 装配类）→ 不装配任何 provider；</li>
 *   <li>enabled + provider-mode 未配置 → 不装配任何 provider；</li>
 *   <li>enabled + provider-mode=tencent + 完整凭证 → 恰好一个 TencentCloudProvider；</li>
 *   <li>enabled + provider-mode=tencent + 缺凭证 → 上下文 fail closed，且零模拟实现构造；</li>
 *   <li>enabled + provider-mode=mock + dev 装配类（-Pdev 才有）→ 恰好一个 dev 模拟实现。</li>
 * </ol>
 */
class IotAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotPropertiesAutoConfiguration.class,
                    IotAutoConfiguration.class));

    @Test
    void productionSelector_mockMode_assemblesNoProvider() {
        runner.withPropertyValues("sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeviceControlProvider.class);
                });
    }

    @Test
    void productionSelector_providerModeAbsent_assemblesNoProvider() {
        runner.withPropertyValues("sw.iot.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeviceControlProvider.class);
                });
    }

    @Test
    void productionSelector_tencentModeWithCredentials_assemblesTencentProvider() {
        runner.withPropertyValues("sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=tencent",
                        "sw.iot.tencent.secret-id=AKIDtest123456",
                        "sw.iot.tencent.secret-key=testSecretKey789")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeviceControlProvider.class);
                    assertThat(context.getBean(DeviceControlProvider.class))
                            .isInstanceOf(TencentCloudProvider.class);
                });
    }

    @Test
    void productionSelector_tencentModeWithoutCredentials_failsClosed() {
        runner.withPropertyValues("sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=tencent")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("SecretId");
                });
    }

    /**
     * R1 反向零残留断言：provider-mode=tencent 且缺凭证时，除启动 fail closed 外，
     * 模拟实现的构造/工厂调用次数严格为零，不得返回任何模拟实例兜底。
     */
    @Test
    void productionSelector_tencentModeWithoutCredentials_neverConstructsMockProvider() {
        try (MockedConstruction<MockCloudProvider> mocked = mockConstruction(MockCloudProvider.class)) {
            runner.withPropertyValues("sw.iot.enabled=true",
                            "sw.iot.tencent.provider-mode=tencent")
                    .run(context -> assertThat(context).hasFailed());

            assertThat(mocked.constructed()).isEmpty();
        }
    }

    /**
     * dev/mock 入口（{@code -Pdev} 才有 {@link MockDeviceControlProviderConfiguration}）：
     * 同一份选择器条件下装配 dev 模拟实现，证明 mock 归属 dev 后开发入口仍可用。
     */
    @Test
    void devMockEntry_mockMode_assemblesSingleMockProvider() {
        runner.withUserConfiguration(MockDeviceControlProviderConfiguration.class)
                .withPropertyValues("sw.iot.enabled=true",
                        "sw.iot.tencent.provider-mode=mock")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DeviceControlProvider.class);
                    assertThat(context.getBean(DeviceControlProvider.class))
                            .isInstanceOf(MockCloudProvider.class);
                });
    }
}
