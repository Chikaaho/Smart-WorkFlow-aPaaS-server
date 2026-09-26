package com.sw.ck.bootstrap.phase5;

import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Phase 5 补证 G1 的测试夹具过滤器。
 *
 * <p>生产入口 {@code StarterApplication} 以 {@code scanBasePackages="com.sw.ck"} 扫描组件；
 * 在本模块**测试类路径**上，另有若干测试夹具同样位于 {@code com.sw.ck.*} 下，会被一并扫到，
 * 从而与真实模块 Bean 冲突（实测：{@code p4overlap/OverlapH2TestConfig} 与生产
 * {@code MybatisPlusConfig} 争夺同一个 {@code commonMetaObjectHandler} Bean 名）。
 * 该冲突是测试类路径产物，真实运行不含 {@code test-classes}，因此不构成装配缺陷。</p>
 *
 * <p>本过滤器只排除<b>测试夹具</b>，不排除任何业务模块或自动配置；被排除的类名会记录并在用例中
 * 断言，确保过滤范围可见、可复核，且不会掩盖真实装配问题。</p>
 */
final class BootstrapTestFixtureExcludeFilter extends TypeExcludeFilter {

    /** 仅排除这两个测试夹具：均为 bootstrap 测试专用配置类。 */
    private static final Set<String> TEST_FIXTURE_CLASSES = Set.of(
            "com.sw.ck.bootstrap.p4overlap.OverlapH2TestConfig",
            "com.sw.ck.bootstrap.i5.ProdBootTestApplication");

    /** 实际被本过滤器排除的类（供用例断言过滤范围）。 */
    static final Set<String> EXCLUDED = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

    @Override
    public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        String className = metadataReader.getClassMetadata().getClassName();
        if (TEST_FIXTURE_CLASSES.contains(className)) {
            EXCLUDED.add(className);
            return true;
        }
        return false;
    }
}
