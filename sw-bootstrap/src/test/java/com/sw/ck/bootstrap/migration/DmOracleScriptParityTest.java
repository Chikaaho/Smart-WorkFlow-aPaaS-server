package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5：DM8 脚本由 Oracle 脚本机械转换而来。H2 代理无法覆盖 DM 的
 * {@code MODIFY(col ...)} 与函数索引，因此改为逐文件比对：
 * 除 {@code VARCHAR2(n CHAR)} 去掉 {@code CHAR} 长度单位外，DM 与 Oracle 必须逐字节一致。
 */
@DisplayName("T5：DM8 与 Oracle 迁移脚本逐文件一致（仅 CHAR 单位差异）")
class DmOracleScriptParityTest {

    @Test
    @DisplayName("31 个脚本文件名与内容一一对应，差异仅为 VARCHAR2 的 CHAR 单位")
    void dmScriptsAreOracleScriptsWithoutCharUnit() throws IOException {
        Path base = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration");
        Path oracle = base.resolve("oracle");
        Path dm = base.resolve("dm");

        Set<String> oracleFiles = sqlFiles(oracle);
        Set<String> dmFiles = sqlFiles(dm);
        assertEquals(oracleFiles, dmFiles, "DM 与 Oracle 脚本文件名必须一致");
        assertEquals(32, dmFiles.size(), "迁移脚本应为 32 条");

        int charUnits = 0;
        for (String file : oracleFiles) {
            String oracleSql = Files.readString(oracle.resolve(file), StandardCharsets.UTF_8);
            String expected = oracleSql.replace(" CHAR)", ")");
            charUnits += count(oracleSql, " CHAR)");
            String actual = Files.readString(dm.resolve(file), StandardCharsets.UTF_8);
            assertEquals(expected, actual,
                    "DM 脚本应与 Oracle 脚本仅差 VARCHAR2(n CHAR) 的 CHAR 单位: " + file);
        }
        assertTrue(charUnits > 0, "应至少存在一处 VARCHAR2(n CHAR) 转换");
        System.out.println("DM/Oracle parity OK: files=" + dmFiles.size() + ", CHAR units removed=" + charUnits);
    }

    private static Set<String> sqlFiles(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), "目录不存在: " + dir);
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
