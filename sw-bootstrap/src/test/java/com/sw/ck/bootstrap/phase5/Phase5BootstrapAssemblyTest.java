package com.sw.ck.bootstrap.phase5;

import com.sw.ck.bootstrap.StarterApplication;
import com.sw.ck.iot.api.IotDeviceFacade;
import com.sw.ck.iot.api.IotDeviceQueryFacade;
import com.sw.ck.iot.api.IotFormContractChecker;
import com.sw.ck.iot.api.IotProcessTriggerFacade;
import com.sw.ck.iot.controller.IotEventRuleController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 补证 G1 · 最终 Bootstrap 生产装配断言（真实 {@link StarterApplication}）。
 *
 * <p>本类不使用任何测试专用入口或替身：上下文由生产类 {@code StarterApplication} 启动，
 * 加载生产 {@code application.yml} 与全部模块的真实自动配置，因此「Bean 数量、实现归属、
 * 反向 SPI 注入、无循环依赖/重复 Bean/缺 Bean」都是最终装配事实，而不是夹具结论。</p>
 *
 * <p>唯一的环境适配是把主数据源指向 Phase 5 专用库并把迁移交给本类的显式迁移（与真实运行
 * 的差异仅在连接目标，不在装配组合）。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Phase5 G1 · 最终 Bootstrap 生产装配 · 真实 StarterApplication")
class Phase5BootstrapAssemblyTest extends Phase5PgSupport {

    /** 三个 IoT 门面的实现模块（契约在 -api，实现在实现模块）。 */
    private static final String IOT_IMPL_FACADE = "com.sw.ck.iot.api.impl.IotDeviceFacadeImpl";
    private static final String IOT_IMPL_TRIGGER = "com.sw.ck.iot.api.impl.IotProcessTriggerFacadeImpl";
    private static final String IOT_IMPL_QUERY = "com.sw.ck.iot.api.impl.IotDeviceQueryFacadeImpl";
    /** 反向 SPI 的实现模块：契约在 IoT，实现由 BPM 提供。 */
    private static final String BPM_IMPL_FORM_CONTRACT = "com.sw.ck.bpm.process.service.IotFormContractCheckerImpl";

    @BeforeAll
    void bootProductionEntry() throws Exception {
        ensureEvidenceDatabase();
        cleanMigrateOnce();

        Map<String, Object> props = properties();
        props.put("sw.iot.enabled", "true");

        ConfigurableApplicationContext context = new SpringApplicationBuilder(StarterApplication.class)
                .initializers(ctx -> {
                    ctx.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("phase5-bootstrap-assembly", props));
                    // 只排除测试类路径上的 bootstrap 测试夹具（生产运行不含 test-classes），
                    // 业务模块与自动配置一律不排除，装配组合与生产一致。
                    ctx.getBeanFactory().registerSingleton("phase5BootstrapTestFixtureExcludeFilter",
                            new BootstrapTestFixtureExcludeFilter());
                })
                .run();
        this.app = context;
        this.jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        seedTenantAndUser(TENANT_A, USER_A, "assembly");
        System.out.println("[P5-G1] production-entry-context=started entry=com.sw.ck.bootstrap.StarterApplication"
                + " database=" + EVIDENCE_DB + " credentials=redacted");
    }

    @AfterAll
    void stopAll() {
        shutdown();
    }

    @Test
    @DisplayName("G1-1 四类契约 Bean 在真实 Bootstrap 上下文中各自恰好存在 1 个")
    void contractBeansAreWiredExactlyOnce() {
        assertExactlyOne(IotDeviceFacade.class);
        assertExactlyOne(IotProcessTriggerFacade.class);
        assertExactlyOne(IotDeviceQueryFacade.class);
        assertExactlyOne(IotFormContractChecker.class);
        // 既无缺 Bean（getBean 成功），也无重复 Bean（数量严格为 1，且可直接按类型取单例）
        assertThat(app.getBean(IotDeviceFacade.class)).isNotNull();
        assertThat(app.getBean(IotProcessTriggerFacade.class)).isNotNull();
        assertThat(app.getBean(IotDeviceQueryFacade.class)).isNotNull();
        assertThat(app.getBean(IotFormContractChecker.class)).isNotNull();
        System.out.println("[P5-G1] g1.1 deviceFacade=1 processTriggerFacade=1 deviceQueryFacade=1"
                + " formContractChecker=1 duplicates=0 missing=0");
    }

    @Test
    @DisplayName("G1-2 三个 IoT 门面来自 IoT 实现模块，反向 SPI 来自 BPM 实现")
    void implementationProvenanceIsCorrect() {
        assertImplementation(IotDeviceFacade.class, IOT_IMPL_FACADE, "sw-basic-iot");
        assertImplementation(IotProcessTriggerFacade.class, IOT_IMPL_TRIGGER, "sw-basic-iot");
        assertImplementation(IotDeviceQueryFacade.class, IOT_IMPL_QUERY, "sw-basic-iot");
        assertImplementation(IotFormContractChecker.class, BPM_IMPL_FORM_CONTRACT, "sw-bpm-process");

        // 契约类型自身必须来自 -api 契约模块，而不是实现模块
        assertThat(codeSourceOf(IotDeviceFacade.class)).as("契约类型应来自 sw-basic-iot-api")
                .contains("sw-basic-iot-api");
        System.out.println("[P5-G1] g1.2 iotFacades=3 from=sw-basic-iot"
                + " reverseSpi=1 from=sw-bpm-process contractTypesFrom=sw-basic-iot-api");
    }

    @Test
    @DisplayName("G1-3 IoT 规则 Controller 从最终装配取得反向 SPI（BPM 实现，同一单例）")
    void iotRuleControllerReceivesReverseSpi() throws Exception {
        IotEventRuleController controller = app.getBean(IotEventRuleController.class);
        assertThat(controller).as("IoT 规则 Controller 必须在真实装配中可用").isNotNull();

        // Controller 带 @PreAuthorize，容器里是 Spring CGLIB 方法安全代理；
        // 该代理由 Objenesis 创建、不调用构造器，因此代理实例上的继承字段为 null。
        // 装配事实在被代理的目标对象上，必须取目标实例再断言。
        Object wiredTarget = controller;
        if (controller instanceof org.springframework.aop.framework.Advised advised) {
            wiredTarget = advised.getTargetSource().getTarget();
        }
        assertThat(wiredTarget).as("必须能取到被代理的真实目标实例").isNotNull();
        assertThat(wiredTarget).as("目标实例必须是 Controller 自身类型")
                .isInstanceOf(IotEventRuleController.class);
        System.out.println("[P5-G1] g1.3 unwired-proxy-fields-are-expected proxy="
                + controller.getClass().getSimpleName()
                + " targetClass=" + wiredTarget.getClass().getName());

        ObjectProvider<?> provider = findFormContractCheckerProvider(wiredTarget);
        assertThat(provider).as("Controller 必须按 ObjectProvider<IotFormContractChecker> 注入反向 SPI")
                .isNotNull();
        Object injected = provider.getIfAvailable();
        assertThat(injected).as("反向 SPI 必须可解析（不是缺 Bean 的降级路径）").isNotNull();
        assertThat(injected.getClass().getName()).as("反向 SPI 必须由 BPM 实现提供")
                .isEqualTo(BPM_IMPL_FORM_CONTRACT);
        assertThat(injected).as("Controller 注入的 SPI 必须与容器中的契约单例是同一个 Bean")
                .isSameAs(app.getBean(IotFormContractChecker.class));
        System.out.println("[P5-G1] g1.3 controller=IotEventRuleController reverseSpiResolved=true"
                + " impl=" + injected.getClass().getName() + " sameSingletonAsContainer=true");
    }

    @Test
    @DisplayName("G1-4 启动无循环依赖：上下文可启动且循环引用保持禁用（非靠 allow-circular-references 放行）")
    void assemblyHasNoCircularDependency() {
        assertThat(app.isActive()).as("生产入口上下文必须处于活动状态").isTrue();
        assertThat(app.getEnvironment().getProperty("spring.main.allow-circular-references", Boolean.class))
                .as("不得通过放开循环引用换取启动成功").isNotEqualTo(Boolean.TRUE);
        // 反向 SPI 的存在不得造成重复装配：契约 Bean 仍为唯一单例
        assertThat(app.getBeanNamesForType(IotFormContractChecker.class)).hasSize(1);
        System.out.println("[P5-G1] g1.4 contextActive=true allowCircularReferences="
                + app.getEnvironment().getProperty("spring.main.allow-circular-references")
                + " formContractCheckerBeans=1");
    }

    // ==================== 夹具 ====================

    private void assertExactlyOne(Class<?> type) {
        assertThat(app.getBeanNamesForType(type))
                .as("真实 Bootstrap 装配中 " + type.getSimpleName() + " Bean 数量必须严格为 1")
                .hasSize(1);
    }

    private void assertImplementation(Class<?> contractType, String expectedImpl, String expectedModule) {
        Object bean = app.getBean(contractType);
        assertThat(bean.getClass().getName())
                .as(contractType.getSimpleName() + " 的实现必须来自 " + expectedModule)
                .isEqualTo(expectedImpl);
        assertThat(codeSourceOf(bean.getClass()))
                .as(contractType.getSimpleName() + " 实现应位于 " + expectedModule + " 模块产物")
                .contains(expectedModule);
    }

    private static String codeSourceOf(Class<?> type) {
        java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null || source.getLocation() == null ? "" : source.getLocation().getPath();
    }

    /**
     * 反射定位 Controller 中泛型为 IotFormContractChecker 的 ObjectProvider 字段。
     *
     * <p>Controller 带 {@code @PreAuthorize}，容器内是方法安全代理；必须按目标类取字段，
     * 否则只会看到代理自身的 CGLIB 字段。</p>
     */
    /**
     * 反射定位 Controller 中泛型为 IotFormContractChecker 的 ObjectProvider 字段。
     *
     * <p>Controller 带 {@code @PreAuthorize}，容器内是方法安全代理；必须按目标类取字段，
     * 否则只会看到代理自身的 CGLIB 字段。泛型实参按全限定名比对，避免受类加载器身份影响。</p>
     */
    private static ObjectProvider<?> findFormContractCheckerProvider(Object controller) throws Exception {
        Class<?> targetClass = org.springframework.aop.support.AopUtils.getTargetClass(controller);
        System.out.println("[P5-G1] HELPER enter targetClass=" + targetClass.getName()
                + " beanClass=" + controller.getClass().getName()
                + " spi=" + IotFormContractChecker.class.getName()
                + " spiLoader=" + IotFormContractChecker.class.getClassLoader());
        for (Field field : targetClass.getDeclaredFields()) {
            boolean isProvider = ObjectProvider.class.isAssignableFrom(field.getType());
            String verdict;
            if (!isProvider) {
                verdict = "not-a-provider";
            } else if (!(field.getGenericType() instanceof ParameterizedType parameterized)) {
                verdict = "raw-provider";
            } else {
                Object argument = parameterized.getActualTypeArguments()[0];
                boolean isClass = argument instanceof Class<?>;
                verdict = "provider arg=" + argument
                        + " argLoader=" + (isClass ? ((Class<?>) argument).getClassLoader() : "n/a")
                        + " nameMatch=" + (isClass
                                && ((Class<?>) argument).getName().equals(IotFormContractChecker.class.getName()))
                        + " identityMatch=" + (argument == IotFormContractChecker.class);
            }
            System.out.println("[P5-G1] HELPER field=" + field.getName() + " -> " + verdict);
            if (isProvider && verdict.contains("nameMatch=true")) {
                field.setAccessible(true);
                Object resolved = field.get(controller);
                System.out.println("[P5-G1] HELPER field.get -> " + resolved);
                return (ObjectProvider<?>) resolved;
            }
        }
        System.out.println("[P5-G1] HELPER no matching field");
        return null;
    }
}
