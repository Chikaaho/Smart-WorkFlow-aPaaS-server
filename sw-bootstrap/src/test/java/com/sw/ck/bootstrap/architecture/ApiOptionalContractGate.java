package com.sw.ck.bootstrap.architecture;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 后端 {@code -api} 模块跨模块契约的机械守门（方向 §3 阶段 C）。
 * <p>
 * 纳入边界（对应探索回执 §1）：{@code -api} 模块中承担跨模块调用、实现或扩展契约的
 * {@code public interface} 方法与公开类的 {@code public static} 方法，返回值必须是
 * 参数化 {@code java.util.Optional<T>}，且 T 不得为 {@code Void}、不得再嵌套 {@code Optional}。
 * </p>
 * <p>
 * 排除边界：record / enum / annotation、{@code Object} 覆写、以及纯值类型（DTO / 事件 /
 * 请求 / 结果 / 快照 / 解析结果等，按类型名后缀识别）的工厂与访问器，与方向 §2 一致。
 * </p>
 * <p>
 * 守门对象由 classpath 扫描 + code source 归属过滤得到：任何新增到 {@code -api} 模块的
 * 契约类型都会被自动纳入，无需维护人工清单；新增非 Optional 契约方法会稳定失败。
 * </p>
 */
public final class ApiOptionalContractGate {

    /**
     * 纳入守门的 -api 模块的 production 包根。
     * <p>
     * Phase 5 追加 {@code com.sw.ck.iot.api} 与 {@code com.sw.ck.iot.event}：
     * IoT 契约层抽取为 {@code sw-basic-iot-api} 后，其 4 个接口共 7 个开放方法必须与既有
     * {@code -api} 模块同规范，不设白名单豁免。归属仍由 {@code belongsToApiModule} 的
     * code source 过滤保证——包根只用于扫描入口，实现模块的类型不会被纳入。
     * </p>
     */
    private static final List<String> API_BASE_PACKAGES = List.of(
            "com.sw.ck.job",
            "com.sw.ck.notify.api",
            "com.sw.ck.storage.api",
            "com.sw.ck.form.api",
            "com.sw.ck.system.api",
            "com.sw.ck.bpm.api",
            "com.sw.ck.iot.api",
            "com.sw.ck.iot.event");

    /** 纯值类型（不作为契约方法来源）的类型名后缀，来自方向 §2 的排除边界。 */
    private static final Pattern VALUE_TYPE_SUFFIX = Pattern.compile(
            ".*(DTO|Dto|Req|Request|Response|Result|Event|Command|Option|Options|Snapshot|Selection"
                    + "|Resolution|Candidate|Metadata|Supports|Topology|Config|Binding|Field|ErrorCode"
                    + "|Exception|Status|Type|Channel|BizType|Op|Item)$");

    /**
     * 纯值类型工厂/builder 名称（方向 §2 明确排除 "builder" 与纯值类型工厂）：
     * Lombok {@code @Builder} 生成的 {@code builder()} 等不属于跨模块契约。
     */
    private static final Set<String> VALUE_TYPE_FACTORY_NAMES = Set.of(
            "builder", "of", "from", "create", "valueOf", "newBuilder");

    /** -api 模块产物归属：reactor 目录或本地仓库 jar 两种形态。 */
    private static final Pattern API_ARTIFACT = Pattern.compile(
            ".*-api(-[0-9][^/]*)?\\.jar$|.*-api/(target/(test-)?classes|build/classes)/?$");

    private ApiOptionalContractGate() {
    }

    /**
     * 扫描 classpath 上所有属于 {@code -api} 模块产物的类型。
     *
     * @return 纳入守门范围的类型集合（按类名排序）
     */
    public static Set<Class<?>> scanApiModuleTypes() {
        Set<Class<?>> types = new TreeSet<>(java.util.Comparator.comparing(Class::getName));
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // 允许接口/抽象类型参与守门（默认实现只接受具体类）
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        provider.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        for (String basePackage : API_BASE_PACKAGES) {
            provider.findCandidateComponents(basePackage).forEach(definition -> {
                try {
                    Class<?> type = Class.forName(definition.getBeanClassName(), false,
                            ApiOptionalContractGate.class.getClassLoader());
                    if (belongsToApiModule(type)) {
                        types.add(type);
                    }
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // 无法载入的类型不参与守门，但不能静默：由规模断言兜底
                }
            });
        }
        return types;
    }

    /**
     * 计算给定类型集合违反契约的方法清单。
     *
     * @param types 待检查类型（调用方可直接传入夹具类型，用于反例验证）
     * @return 违规描述（为空表示全部合规）
     */
    public static List<String> violationsIn(Collection<Class<?>> types) {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                if (!isContractMethod(type, method)) {
                    continue;
                }
                String problem = returnTypeViolation(method);
                if (problem != null) {
                    violations.add(type.getName() + "#" + method.getName() + " → " + problem);
                }
            }
        }
        return violations;
    }

    /**
     * 收集给定类型集合中纳入守门的契约方法总数。
     */
    public static int contractMethodCount(Collection<Class<?>> types) {
        int count = 0;
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                if (isContractMethod(type, method)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 判断类型是否属于 -api 模块产物（reactor 输出目录或本地仓库 jar）。
     */
    public static boolean belongsToApiModule(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            return false;
        }
        URL location = codeSource.getLocation();
        return location != null && API_ARTIFACT.matcher(location.getPath()).matches();
    }

    private static boolean isContractMethod(Class<?> type, Method method) {
        if (method.isSynthetic() || method.isBridge()) {
            return false;
        }
        if ("<init>".equals(method.getName())) {
            return false;
        }
        if (type.isInterface()) {
            // 接口方法（含 default / static）全部纳入
            return true;
        }
        if (type.isRecord() || type.isEnum() || type.isAnnotation()
                || VALUE_TYPE_SUFFIX.matcher(type.getSimpleName()).matches()) {
            return false;
        }
        if (VALUE_TYPE_FACTORY_NAMES.contains(method.getName())) {
            // 纯值类型工厂/builder（如 Lombok @Builder 的 builder()）不属于跨模块契约
            return false;
        }
        return Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers());
    }

    private static String returnTypeViolation(Method method) {
        if (method.getReturnType() != Optional.class) {
            return "返回值必须是 Optional<T>，实际 " + method.getReturnType().getSimpleName();
        }
        Type genericReturn = method.getGenericReturnType();
        if (!(genericReturn instanceof ParameterizedType parameterized)) {
            return "禁止 raw Optional（必须参数化 Optional<T>）";
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        Class<?> argumentClass = rawClassOf(argument);
        if (argumentClass == Void.class) {
            return "禁止 Optional<Void>（必须承载有意义的类型化结果）";
        }
        if (argumentClass == Optional.class) {
            return "禁止嵌套 Optional";
        }
        if (argumentClass == Object.class && argument instanceof ParameterizedType nested
                && rawClassOf(nested.getActualTypeArguments()[0]) == Object.class) {
            return "Optional<?> 通配不构成契约结果类型，必须给出具体类型";
        }
        return null;
    }

    private static Class<?> rawClassOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof java.lang.reflect.WildcardType wildcard
                && wildcard.getUpperBounds().length == 1) {
            return rawClassOf(wildcard.getUpperBounds()[0]);
        }
        return Object.class;
    }

    /**
     * 收集类型集合中的接口类型（用于规模断言，防止守门静默退化为空扫描）。
     */
    public static Set<Class<?>> interfacesOf(Collection<Class<?>> types) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type : types) {
            if (type.isInterface() && !type.isAnnotation()) {
                interfaces.add(type);
            }
        }
        return interfaces;
    }
}
