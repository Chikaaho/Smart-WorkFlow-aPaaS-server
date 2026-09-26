package com.sw.ck.bootstrap.phase5;

import com.sw.ck.bootstrap.architecture.ApiOptionalContractGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5 · IoT 契约层抽取的机械守门（方向 §4 验收门禁 1/2/3/4/8）。
 *
 * <p>只做可复算的结构与依赖断言，不冒充行为验收：契约的两层语义与两条事务分界由
 * {@code Phase5PgDeviceCommandBoundaryBehaviourTest} 与各模块行为用例证明。</p>
 */
class Phase5IotApiBoundaryGateTest {

    /** 新契约模块的 5 个契约源文件 FQCN（4 接口 + 1 事件）。 */
    private static final List<String> CONTRACT_FQCNS = List.of(
            "com.sw.ck.iot.api.IotDeviceFacade",
            "com.sw.ck.iot.api.IotProcessTriggerFacade",
            "com.sw.ck.iot.api.IotDeviceQueryFacade",
            "com.sw.ck.iot.api.IotFormContractChecker",
            "com.sw.ck.iot.event.IotProcessTriggerEvent");

    /** 实现模块的包前缀：出现在 bpm-process 生产源码即为跨模块实现耦合。 */
    private static final List<String> IMPLEMENTATION_PACKAGE_PREFIXES = List.of(
            "com.sw.ck.iot.entity",
            "com.sw.ck.iot.mapper",
            "com.sw.ck.iot.service",
            "com.sw.ck.iot.job",
            "com.sw.ck.iot.script",
            "com.sw.ck.iot.mqtt",
            "com.sw.ck.iot.provider",
            "com.sw.ck.iot.controller",
            "com.sw.ck.iot.config",
            "com.sw.ck.iot.util");

    /** IoT 对外 HTTP 路由快照（Phase 5 不得增删改）。 */
    private static final Set<String> IOT_HTTP_ROUTES = Set.of(
            "/iot/connections",
            "/iot/devices",
            "/iot/device-manage",
            "/iot/rules",
            "/iot/products",
            "/iot/runtime",
            "/iot/scripts",
            "/iot/topics",
            "/iot/hook/tencent");

    /** 抽取前已存在的 IoT 迁移文件（Phase 5 不得新增/改名）。 */
    private static final Set<String> IOT_MIGRATION_FILES = Set.of(
            "sw-basic/sw-basic-iot/src/main/resources/db/migration/iot/h2/V40__init_iot_device_command.sql",
            "sw-basic/sw-basic-iot/src/main/resources/db/migration/iot/h2/V59__p21_iot_platform.sql",
            "sw-basic/sw-basic-iot/src/main/resources/db/migration/iot/h2/V66__p21_iot_audit_and_correlation.sql",
            "sw-basic/sw-basic-iot/src/main/resources/db/migration/iot/postgresql/V40__init_iot_device_command.sql",
            "sw-basic/sw-basic-iot/src/main/resources/db/migration/iot/postgresql/V59__p21_iot_platform.sql",
            "sw-basic/sw-basic-iot/src/main/resources/db/migration/iot/postgresql/V66__p21_iot_audit_and_correlation.sql");

    // ==================== 门禁 1 · 契约模块类型清单与零非 JDK 依赖 ====================

    @Test
    @DisplayName("门禁1：契约模块清单准确为 4 接口 + 1 事件，且无任何非 JDK 编译依赖")
    void contractModuleHasExactInventoryAndJdkOnlyImports() throws IOException {
        List<Path> sources = CONTRACT_FQCNS.stream()
                .map(fqcn -> {
                    try {
                        return resolveByFqcn(fqcn);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
        assertThat(sources).as("契约源文件必须唯一存在").hasSize(CONTRACT_FQCNS.size());

        long interfaces = sources.stream()
                .filter(path -> readUnchecked(path).contains("public interface "))
                .count();
        long events = sources.stream()
                .filter(path -> path.getFileName().toString().endsWith("Event.java"))
                .count();
        assertThat(interfaces).as("契约接口数量").isEqualTo(4L);
        assertThat(events).as("契约事件数量").isEqualTo(1L);

        // 用户可见的契约方法总数固定为 7：既不多拆也不合并接口
        int openMethods = CONTRACT_FQCNS.stream()
                .filter(fqcn -> !fqcn.endsWith("Event"))
                .mapToInt(fqcn -> {
                    try {
                        String code = stripCommentsAndLiterals(read(resolveByFqcn(fqcn)));
                        return countInterfaceMethods(code);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .sum();
        assertThat(openMethods).as("契约开放方法总数").isEqualTo(7);

        // 编译依赖扫描：只允许 java.* / javax.* 与 JDK 类型
        for (Path source : sources) {
            List<String> imports = readLines(source).stream()
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.replace("import ", "").replace(";", "").trim())
                    .filter(imported -> !imported.startsWith("static "))
                    .toList();
            for (String imported : imports) {
                assertThat(imported)
                        .as("契约源码只允许 JDK 类型导入: " + source.getFileName())
                        .matches("^java\\.[A-Za-z0-9_.]*$|^javax\\.[A-Za-z0-9_.]*$");
            }
        }
        System.out.println("[P5-GATE] gate1 contract-files=" + sources.size()
                + " interfaces=" + interfaces + " events=" + events
                + " open-methods=" + openMethods + " nonJdkImports=0");
    }

    @Test
    @DisplayName("门禁1：契约模块 POM 零依赖声明；契约成员的签名类型全部为 JDK 类型")
    void contractModulePomDeclaresNoDependency() throws IOException {
        Path pom = repoRoot().resolve("sw-basic/sw-basic-iot-api/pom.xml");
        assertThat(Files.isRegularFile(pom)).as("契约模块 POM 必须存在").isTrue();
        // 结构断言：只剥离 XML 注释后检查是否声明了任何依赖（注释中提及被禁止依赖的名字不算声明）
        String text = Files.readString(pom, StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", " ");
        assertThat(text).doesNotContain("<dependencies>");
        assertThat(text).doesNotContain("<dependencies/>");
        assertThat(text).doesNotContain("<dependency>");

        // 决定性断言：契约类型的所有成员签名只允许 JDK 类型（无需依赖任何模块即可成立）
        for (Class<?> type : contractTypes()) {
            assertJdkOnly(type, type.getSuperclass(), "superclass");
            for (Class<?> itf : type.getInterfaces()) {
                assertJdkOnly(type, itf, "interface");
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                assertJdkOnly(type, method.getReturnType(), method.getName() + " 返回类型");
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertJdkOnly(type, parameter, method.getName() + " 参数类型");
                }
                for (Class<?> exception : method.getExceptionTypes()) {
                    assertJdkOnly(type, exception, method.getName() + " 异常类型");
                }
            }
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                assertJdkOnly(type, field.getType(), field.getName() + " 字段类型");
            }
        }
        System.out.println("[P5-GATE] gate1.pom dependencies=0 jdk-only-signatures="
                + contractTypes().size());
    }

    private static void assertJdkOnly(Class<?> owner, Class<?> candidate, String where) {
        if (candidate == null) {
            return;
        }
        Class<?> type = candidate;
        while (type.isArray()) {
            type = type.getComponentType();
        }
        if (type.isPrimitive() || type.isEnum() && type.getPackageName().startsWith("java.")) {
            return;
        }
        assertThat(type.getPackageName())
                .as("契约成员必须只用 JDK 类型: " + owner.getSimpleName() + " " + where)
                .matches("^java\\..*$|^javax\\..*$");
    }

    private static List<Class<?>> contractTypes() {
        return Set.copyOf(ApiOptionalContractGate.scanApiModuleTypes()).stream()
                .filter(type -> type.getName().startsWith("com.sw.ck.iot."))
                .sorted(java.util.Comparator.comparing(Class::getName))
                .toList();
    }

    // ==================== 门禁 2 · 7/7 Optional 合规并纳入 Phase 1 守门 ====================

    @Test
    @DisplayName("门禁2：IoT 契约 7/7 方法为参数化 Optional<T>，且已被 Phase 1 守门自动纳入")
    void contractIsFullyOptionalAndInScope() throws IOException {
        Set<Class<?>> apiTypes = ApiOptionalContractGate.scanApiModuleTypes();
        Set<Class<?>> iotTypes = apiTypes.stream()
                .filter(type -> type.getName().startsWith("com.sw.ck.iot."))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        assertThat(iotTypes.stream().map(Class::getSimpleName).toList())
                .as("IoT 契约类型必须被 Phase 1 守门扫描到（无白名单豁免）")
                .containsExactlyInAnyOrder("IotDeviceFacade", "IotProcessTriggerFacade",
                        "IotDeviceQueryFacade", "IotFormContractChecker", "IotProcessTriggerEvent");
        assertThat(ApiOptionalContractGate.contractMethodCount(iotTypes))
                .as("纳入守门的 IoT 契约方法数")
                .isEqualTo(7);
        assertThat(ApiOptionalContractGate.violationsIn(iotTypes))
                .as("无 void / primitive / 裸集合 / 裸对象返回")
                .isEmpty();
        System.out.println("[P5-GATE] gate2 iot-types=" + iotTypes.size()
                + " optional-methods=" + ApiOptionalContractGate.contractMethodCount(iotTypes)
                + " violations=0");
    }

    // ==================== 门禁 3 · 依赖所有权 ====================

    @Test
    @DisplayName("门禁3：bpm-process 只声明 -api 契约；fastjson2 为诚实的直接依赖，版本由 BOM 单一供给")
    void bpmProcessOwnsItsDependencies() throws IOException {
        String pom = Files.readString(
                repoRoot().resolve("sw-biz/sw-bpm/sw-bpm-process/pom.xml"), StandardCharsets.UTF_8);
        String deps = pom.substring(pom.indexOf("<dependencies>"));
        assertThat(deps).contains("<artifactId>sw-basic-iot-api</artifactId>");
        assertThat(deps).as("不得再声明完整 IoT 实现模块").doesNotContain("<artifactId>sw-basic-iot</artifactId>");
        // 诚实所有权：fastjson2 必须是本模块的直接声明，不得退回经 sw-basic-iot 传递获得
        assertThat(deps).contains("<artifactId>fastjson2</artifactId>");
        // Phase 6A：版本单一所有者是 sw-dependencies，本模块的 fastjson2 声明不得再写 version
        assertThat(dependencyBlock(deps, "fastjson2"))
                .as("fastjson2 依赖块内不得写 version——唯一版本所有者是 sw-dependencies")
                .doesNotContain("<version>");
        assertThat(dependencyBlock(deps, "sw-basic-iot-api"))
                .as("内部 -api 契约不得写死 1.x 坐标")
                .doesNotContain("<version>1.");
        // 版本必须与抽取前实际解析值一致：不得顺手升级。
        // 双证据：BOM 管理值 + 运行期真实加载到的构件文件名（后者不受 POM 结构变化影响）
        assertThat(bomManagedVersion("fastjson2"))
                .as("sw-dependencies 中 fastjson2 的管理版本")
                .isEqualTo("2.0.53");
        assertThat(resolvedArtifactFileName(com.alibaba.fastjson2.JSON.class))
                .as("运行期实际解析到的 fastjson2 构件")
                .isEqualTo("fastjson2-2.0.53.jar");
        System.out.println("[P5-GATE] gate3 bpm-process declares iot-api + fastjson2(BOM 2.0.53), iot-impl absent");
    }

    /** 取 deps 文本中包含该 artifactId 的那一个 &lt;dependency&gt; 块。 */
    private static String dependencyBlock(String deps, String artifactId) {
        int at = deps.indexOf("<artifactId>" + artifactId + "</artifactId>");
        assertThat(at).as("依赖块存在: " + artifactId).isGreaterThanOrEqualTo(0);
        return deps.substring(deps.lastIndexOf("<dependency>", at),
                deps.indexOf("</dependency>", at));
    }

    /** 读 sw-dependencies BOM 中该 artifactId 的 xxx.version 属性值。 */
    private static String bomManagedVersion(String artifactId) throws IOException {
        String bom = Files.readString(
                repoRoot().resolve("sw-dependencies/pom.xml"), StandardCharsets.UTF_8);
        var matcher = Pattern.compile("<" + Pattern.quote(artifactId) + "\\.version>([^<]+)<")
                .matcher(bom);
        assertThat(matcher.find())
                .as("sw-dependencies 必须管理 " + artifactId + " 的版本").isTrue();
        return matcher.group(1);
    }

    /** 运行期解析到的构件文件名，如 fastjson2-2.0.53.jar。 */
    private static String resolvedArtifactFileName(Class<?> type) {
        var source = type.getProtectionDomain().getCodeSource();
        assertThat(source).as("构件来源可定位: " + type.getName()).isNotNull();
        return Path.of(source.getLocation().getPath()).getFileName().toString();
    }

    // ==================== 门禁 4 · 零实现耦合 ====================

    @Test
    @DisplayName("门禁4：bpm-process 生产源码零 IoT 实现引用；契约结果一律显式消费")
    void bpmProcessHasNoImplementationCoupling() throws IOException {
        Path sourceRoot = repoRoot().resolve("sw-biz/sw-bpm/sw-bpm-process/src/main/java");
        assertThat(Files.isDirectory(sourceRoot)).isTrue();
        List<Path> production;
        try (var walk = Files.walk(sourceRoot)) {
            production = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path file : production) {
            String code = stripCommentsAndLiterals(read(file));
            for (String prefix : IMPLEMENTATION_PACKAGE_PREFIXES) {
                assertThat(code)
                        .as("跨模块实现耦合: " + file.getFileName())
                        .doesNotContain(prefix);
            }
        }
        // 契约结果的消费方式：在四个 IoT 契约消费文件内不得 orElse(null)，
        // 且必须存在显式存在性分支（isEmpty/isPresent/ifPresent/orElseThrow）
        List<String> consumers = List.of(
                "sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/listener/BpmDeviceCommandIntentRecorder.java",
                "sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/listener/BpmDeviceCommandListener.java",
                "sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/listener/IotProcessTriggerListener.java",
                "sw-biz/sw-bpm/sw-bpm-process/src/main/java/com/sw/ck/bpm/process/service/IotFormContractCheckerImpl.java");
        for (String relative : consumers) {
            String code = stripCommentsAndLiterals(read(repoRoot().resolve(relative)));
            String name = relative.substring(relative.lastIndexOf('/') + 1);
            assertThat(code).as("不得 orElse(null): " + name).doesNotContain("orElse(null)");
            assertThat(code).as("缺少显式存在性分支: " + name)
                    .matches("(?s).*(\\.isEmpty\\(\\)|\\.isPresent\\(\\)|\\.ifPresent|\\.orElseThrow).*");
        }
        System.out.println("[P5-GATE] gate4 production-files=" + production.size()
                + " implementationRefs=0 consumers=" + consumers.size() + " orElseNull=0");
    }

    // ==================== 门禁 8 · 无迁移与 HTTP 契约变化 ====================

    @Test
    @DisplayName("门禁8：无新 Flyway 迁移、契约模块零 HTTP 路由、IoT 路由快照不变")
    void noMigrationOrHttpContractChange() throws IOException {
        Set<String> actualMigrations = new java.util.LinkedHashSet<>();
        Path iotMigrationRoot = repoRoot().resolve("sw-basic/sw-basic-iot/src/main/resources/db/migration");
        try (var walk = Files.walk(iotMigrationRoot)) {
            walk.filter(path -> path.toString().endsWith(".sql"))
                    .forEach(path -> actualMigrations.add(repoRoot().relativize(path).toString()));
        }
        assertThat(actualMigrations).as("IoT 迁移文件集合不得变化").isEqualTo(IOT_MIGRATION_FILES);
        assertThat(Files.exists(repoRoot().resolve(
                "sw-basic/sw-basic-iot-api/src/main/resources/db/migration")))
                .as("契约模块不得携带迁移").isFalse();

        Path iotApiRoot = repoRoot().resolve("sw-basic/sw-basic-iot-api/src/main/java");
        List<Path> apiSources;
        try (var walk = Files.walk(iotApiRoot)) {
            apiSources = walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Path file : apiSources) {
            assertThat(read(file)).as("契约模块不得定义 HTTP 路由: " + file.getFileName())
                    .doesNotContain("@RequestMapping");
        }

        Path controllerRoot = repoRoot().resolve("sw-basic/sw-basic-iot/src/main/java");
        Set<String> routes = new java.util.LinkedHashSet<>();
        try (var walk = Files.walk(controllerRoot)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                // 路由字面量必须从原始源码提取：注释/字面量剥离会把 "/iot/..." 抹掉
                java.util.regex.Matcher matcher = Pattern
                        .compile("@RequestMapping\\(\"([^\"]+)\"\\)")
                        .matcher(read(file));
                while (matcher.find()) {
                    routes.add(matcher.group(1));
                }
            }
        }
        assertThat(routes).as("IoT HTTP 路由快照不得变化").isEqualTo(IOT_HTTP_ROUTES);
        System.out.println("[P5-GATE] gate8 iot-migrations=" + actualMigrations.size()
                + " apiRoutes=0 httpRoutes=" + routes.size() + " routesUnchanged=true");
    }

    // ==================== 夹具 ====================

    private static int countInterfaceMethods(String code) {
        // 契约方法声明：4 空格缩进且以 Optional< 开头（声明可跨多行，故不要求同一行以 ; 结束）
        return (int) code.lines()
                .filter(line -> line.matches("^    Optional<.*"))
                .count();
    }

    private static Path resolveByFqcn(String fqcn) throws IOException {
        String relative = fqcn.replace('.', '/') + ".java";
        try (var walk = Files.walk(repoRoot())) {
            List<Path> matches = walk
                    .filter(path -> path.toString().endsWith("/src/main/java/" + relative))
                    .filter(path -> !path.toString().contains("/target/"))
                    .toList();
            assertThat(matches).as("FQCN 唯一定位: " + fqcn).hasSize(1);
            return matches.get(0);
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 供 stream/lambda 使用的读取包装：失败立即抛出，不静默跳过检查。 */
    private static String readUnchecked(Path path) {
        try {
            return read(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> readLines(Path path) throws IOException {
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        String basedir = System.getProperty("basedir");
        Path base = (basedir == null || basedir.isBlank())
                ? Path.of(".").toAbsolutePath().normalize()
                : Path.of(basedir).toAbsolutePath().normalize();
        Path root = base.getParent();
        assertThat(root).as("未定位到后端仓库根: " + root).isNotNull();
        assertThat(Files.isDirectory(root.resolve("sw-biz"))).as("仓库根校验").isTrue();
        return root;
    }

    /** 复用守门的注释/字面量剥离，避免把 Javadoc 中的类型名当作真实引用。 */
    private static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean line = false;
        boolean block = false;
        boolean str = false;
        boolean ch = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char n = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (line) {
                if (c == '\n') { line = false; out.append('\n'); continue; }
                out.append(' ');
                continue;
            }
            if (block) {
                if (c == '*' && n == '/') { block = false; out.append("  "); i++; continue; }
                out.append(c == '\n' ? '\n' : ' ');
                continue;
            }
            if (str || ch) {
                if (c == '\\') { out.append("  "); i++; continue; }
                if ((str && c == '"') || (ch && c == '\'')) { str = false; ch = false; }
                out.append(c == '\n' ? '\n' : ' ');
                continue;
            }
            if (c == '/' && n == '/') { line = true; out.append("  "); i++; continue; }
            if (c == '/' && n == '*') { block = true; out.append("  "); i++; continue; }
            if (c == '"') { str = true; out.append(' '); i++; continue; }
            if (c == '\'') { ch = true; out.append(' '); i++; continue; }
            out.append(c);
        }
        return out.toString();
    }
}
