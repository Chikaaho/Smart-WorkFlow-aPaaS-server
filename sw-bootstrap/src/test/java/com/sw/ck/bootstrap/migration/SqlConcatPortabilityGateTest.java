package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1：MySQL/MariaDB 默认模式下 {@code ||} 是逻辑或，不是字符串拼接。
 * 迁移脚本里不得再出现可执行的 {@code ||}；注释中的说明文字允许保留。
 */
@DisplayName("MySQL/MariaDB 迁移脚本禁止 || 拼接")
class SqlConcatPortabilityGateTest {

    @Test
    @DisplayName("mysql 与 mariadb 脚本中无未注释的 ||")
    void noExecutablePipePipe() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String dialect : List.of("mysql", "mariadb")) {
            Path dir = Path.of(System.getProperty("basedir", "."))
                    .resolve("src/main/resources/db/migration/" + dialect);
            assertTrue(Files.isDirectory(dir), "迁移目录不存在: " + dir);
            try (var files = Files.list(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                    collectViolations(file, violations);
                }
            }
        }
        assertTrue(violations.isEmpty(), "发现可执行的 ||，请改为 CONCAT: " + violations);
    }

    private static void collectViolations(Path file, List<String> violations) throws IOException {
        boolean inBlockComment = false;
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (inBlockComment) {
                int end = line.indexOf("*/");
                if (end < 0) {
                    continue;
                }
                line = line.substring(end + 2);
                inBlockComment = false;
            }
            int blockStart = line.indexOf("/*");
            if (blockStart >= 0) {
                line = line.substring(0, blockStart);
                inBlockComment = true;
            }
            int lineComment = line.indexOf("--");
            if (lineComment >= 0) {
                line = line.substring(0, lineComment);
            }
            if (line.contains("||")) {
                violations.add(file.getFileName() + ":" + (i + 1));
            }
        }
    }
}
