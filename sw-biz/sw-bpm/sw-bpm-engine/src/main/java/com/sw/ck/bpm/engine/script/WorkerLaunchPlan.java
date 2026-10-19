package com.sw.ck.bpm.engine.script;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * BF20261019-01：判断 worker 子进程拉起计划。
 * <p>
 * 背景：worker 以 {@code java -cp <classpath> ScriptWorkerMain} 拉起，classpath 缺省取
 * 宿主 {@code java.class.path}。Spring Boot fat jar 部署形态下该属性只有 {@code bootstrap.jar}
 * 一个条目，{@code ScriptWorkerMain} 位于 {@code BOOT-INF/classes} 内、对 {@code -cp} 不可见，
 * worker 必然 {@code Could not find or load main class}（UAT 2026-10-19 实测，此前只能靠
 * 运维手工解包 + {@code SW_BPM_SCRIPT_WORKER_CLASSPATH} 环境变量绕过）。
 * </p>
 * <p>
 * 解析优先级：
 * <ol>
 *   <li>显式覆盖（系统属性 {@code sw.bpm.script.worker-classpath} / 环境变量
 *       {@code SW_BPM_SCRIPT_WORKER_CLASSPATH}）：原样作为 {@code -cp}，主类直拉；</li>
 *   <li>宿主自身运行在 Spring Boot fat jar 内（{@link ScriptWorkerMain} 的代码源是嵌套 URL）：
 *       以 {@code -cp <外层jar> -Dloader.main=ScriptWorkerMain PropertiesLauncher} 拉起——
 *       PropertiesLauncher 会把归档自身的 {@code BOOT-INF/classes} 与 {@code BOOT-INF/lib/*}
 *       并入类路径，无需解包（前置校验外层 jar 含 launcher 类，缺失则给出可诊断错误）；</li>
 *   <li>exploded/plain jar 形态（IDE、测试、手工 -cp）：沿用 {@code java.class.path}，语义不变。</li>
 * </ol>
 */
final class WorkerLaunchPlan {

    /** Spring Boot 3.2+ 嵌套 jar 启动器（fat jar 根部自带；缺失说明不是标准 boot jar）。 */
    static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    static final String PROPERTIES_LAUNCHER_ENTRY = "org/springframework/boot/loader/launch/PropertiesLauncher.class";

    final String classpath;
    final String mainClass;
    final Map<String, String> systemProperties;
    /** 解析来源（override / fat-jar / exploded），用于启动日志与握手失败诊断。 */
    final String source;

    private WorkerLaunchPlan(String classpath, String mainClass, Map<String, String> systemProperties,
                             String source) {
        this.classpath = classpath;
        this.mainClass = mainClass;
        this.systemProperties = systemProperties;
        this.source = source;
    }

    /**
     * 解析 worker 拉起计划。
     *
     * @param override 显式 classpath 覆盖（系统属性/环境变量；可为 null/空白）
     * @throws IllegalStateException fat jar 形态但外层 jar 不含 Spring Boot launcher
     *                               （无法在归档内构造 worker 类路径时拒绝启动并说明覆盖入口）
     */
    static WorkerLaunchPlan resolve(String override) throws IOException {
        if (override != null && !override.isBlank()) {
            return new WorkerLaunchPlan(override, ScriptWorkerMain.class.getName(), Map.of(), "override");
        }
        Path outerJar = fatJarPath();
        if (outerJar == null) {
            return new WorkerLaunchPlan(System.getProperty("java.class.path"),
                    ScriptWorkerMain.class.getName(), Map.of(), "exploded");
        }
        if (!containsEntry(outerJar, PROPERTIES_LAUNCHER_ENTRY)) {
            throw new IllegalStateException("宿主运行于 fat jar（" + outerJar + "）但未找到 Spring Boot launcher（"
                    + PROPERTIES_LAUNCHER + "），无法拉起判断 worker；"
                    + "请设置系统属性 sw.bpm.script.worker-classpath 或环境变量 SW_BPM_SCRIPT_WORKER_CLASSPATH "
                    + "指向可用类路径");
        }
        return new WorkerLaunchPlan(outerJar.toString(), PROPERTIES_LAUNCHER,
                Map.of("loader.main", ScriptWorkerMain.class.getName()), "fat-jar");
    }

    /**
     * 宿主自身为 Spring Boot fat jar 时返回外层 jar 绝对路径；exploded / plain jar / 无法判定
     * 返回 null（走 java.class.path 原语义）。
     */
    static Path fatJarPath() {
        CodeSource codeSource = ScriptWorkerMain.class.getProtectionDomain() == null
                ? null : ScriptWorkerMain.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        String candidate = nestedJarPathOf(codeSource.getLocation().toExternalForm());
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        Path path = Path.of(candidate);
        return Files.isRegularFile(path) ? path : null;
    }

    /**
     * 从类源 URL 外部形态提取外层 jar 文件路径；类在目录（exploded）或普通文件 jar 上时返回 null。
     * 兼容 {@code nested:/path/app.jar/!BOOT-INF/classes} 与
     * {@code jar:file:/path/app.jar!/BOOT-INF/classes} 两种嵌套形态（nested 协议无公共
     * URL handler，按外部形态字符串解析，不构造 {@link URL}）。
     */
    static String nestedJarPathOf(String externalForm) {
        if (externalForm == null) {
            return null;
        }
        String text = externalForm;
        if (text.startsWith("file:")) {
            // 普通 jar（file:...jar）或目录：java.class.path 本就可达，无需转换
            return null;
        }
        int bang = text.indexOf('!');
        String before = bang >= 0 ? text.substring(0, bang) : text;
        before = stripPrefix(before, "nested:");
        before = stripPrefix(before, "jar:");
        before = stripPrefix(before, "file:");
        String decoded = URLDecoder.decode(before, StandardCharsets.UTF_8);
        return Files.isRegularFile(Path.of(decoded)) ? decoded : null;
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    static boolean containsEntry(Path jar, String entry) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            return jarFile.getJarEntry(entry) != null;
        }
    }

    /** 供宿主启动日志/诊断展示的完整描述。 */
    String describe() {
        return "source=" + source + ", mainClass=" + mainClass + ", classpath=" + classpath
                + (systemProperties.isEmpty() ? "" : ", systemProperties=" + systemProperties);
    }
}
