package com.sw.ck.bpm.engine.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BF20261019-01：worker 拉起计划解析测试。
 * <p>
 * 覆盖：显式覆盖优先、exploded/plain jar 走 java.class.path 原语义、Spring Boot 嵌套
 * URL（nested:/jar:file:）提取外层 jar、URL 编码路径、fat jar launcher 条目探测。
 * fat jar 全链（真实 bootstrap.jar 内经 PropertiesLauncher 拉起 worker）由打包后的
 * 真实行为链验证覆盖，不在单测内重复构建 200MB+ 制品。
 * </p>
 */
@DisplayName("BF20261019-01 worker 拉起计划解析")
class WorkerLaunchPlanTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("显式覆盖（系统属性/环境变量入参）优先且主类直拉")
    void overrideWins() throws Exception {
        WorkerLaunchPlan plan = WorkerLaunchPlan.resolve("/opt/sw/app/classes:/opt/sw/app/lib/*");
        assertThat(plan.source).isEqualTo("override");
        assertThat(plan.classpath).isEqualTo("/opt/sw/app/classes:/opt/sw/app/lib/*");
        assertThat(plan.mainClass).isEqualTo(ScriptWorkerMain.class.getName());
        assertThat(plan.systemProperties).isEmpty();
    }

    @Test
    @DisplayName("测试/exploded 形态：java.class.path 原语义，无系统属性")
    void explodedFallsBackToJavaClassPath() throws Exception {
        WorkerLaunchPlan plan = WorkerLaunchPlan.resolve(null);
        assertThat(plan.source).isEqualTo("exploded");
        assertThat(plan.classpath).isEqualTo(System.getProperty("java.class.path"));
        assertThat(plan.mainClass).isEqualTo(ScriptWorkerMain.class.getName());
        assertThat(plan.systemProperties).isEmpty();
    }

    @Test
    @DisplayName("exploded 形态下代码源目录/普通 jar 不判为 fat jar")
    void plainLocationsAreNotFatJar() {
        assertThat(WorkerLaunchPlan.nestedJarPathOf(
                WorkerLaunchPlanTest.class.getProtectionDomain().getCodeSource()
                        .getLocation().toExternalForm())).isNull();
        assertThat(WorkerLaunchPlan.fatJarPath()).isNull();
    }

    @Test
    @DisplayName("Spring Boot 嵌套 URL 提取外层 jar：nested: 与 jar:file: 两种形态、URL 编码路径")
    void nestedUrlsResolveToOuterJar() throws Exception {
        Path fatJar = Files.createFile(tempDir.resolve("fat jar.jar"));
        String encoded = tempDir.toUri().getPath().replaceAll("/$", "") + "/fat%20jar.jar";

        assertThat(WorkerLaunchPlan.nestedJarPathOf("nested:" + encoded + "!/BOOT-INF/classes"))
                .isEqualTo(fatJar.toString());
        assertThat(WorkerLaunchPlan.nestedJarPathOf("jar:file:" + encoded + "!/BOOT-INF/classes"))
                .isEqualTo(fatJar.toString());
        // Spring Boot 嵌套 URL 实际形态：jar 路径与 '!entry' 之间带 '/'（真实 fat jar 排障发现）
        assertThat(WorkerLaunchPlan.nestedJarPathOf("nested:file:" + encoded + "/!BOOT-INF.classes"))
                .isEqualTo(fatJar.toString());
        // 组合协议前缀（真实 fat jar 代码源实测：jar:nested:…!），双层嵌套条目收尾 '!/'
        assertThat(WorkerLaunchPlan.nestedJarPathOf(
                "jar:nested:" + encoded + "/!BOOT-INF/lib/sw-bpm-engine.jar!/"))
                .isEqualTo(fatJar.toString());
        // 嵌套指向不存在的文件 → 非法目标，回退 null（原语义）
        assertThat(WorkerLaunchPlan.nestedJarPathOf(
                "nested:/nonexistent/path/app.jar/!BOOT-INF/classes")).isNull();
    }

    @Test
    @DisplayName("含 launcher 条目的 jar 探测为真；合法空 jar 为假")
    void launcherEntryDetection() throws Exception {
        Path withLauncher = tempDir.resolve("boot.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(withLauncher.toFile()))) {
            out.putNextEntry(new JarEntry(WorkerLaunchPlan.PROPERTIES_LAUNCHER_ENTRY));
            out.write(new byte[] {1});
            out.closeEntry();
        }
        Path empty = tempDir.resolve("plain.jar");
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(empty.toFile()))) {
            out.close();
        }

        assertThat(WorkerLaunchPlan.containsEntry(withLauncher, WorkerLaunchPlan.PROPERTIES_LAUNCHER_ENTRY))
                .isTrue();
        assertThat(WorkerLaunchPlan.containsEntry(empty, WorkerLaunchPlan.PROPERTIES_LAUNCHER_ENTRY))
                .isFalse();
    }
}
