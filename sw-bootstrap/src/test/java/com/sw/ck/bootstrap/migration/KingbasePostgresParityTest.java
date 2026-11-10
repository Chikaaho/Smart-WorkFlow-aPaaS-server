package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T5（Kingbase 口径）：KingbaseES 脚本由 PostgreSQL 脚本复制，仅 V0.2.5 去掉 pgvector 块。
 * 逐文件断言其余 29 个脚本一致，并确认 V0.2.5 不再包含扩展/向量列。
 */
@DisplayName("T5：KingbaseES 与 PostgreSQL 脚本一致（仅去掉 pgvector）")
class KingbasePostgresParityTest {

    @Test
    @DisplayName("31 个脚本一一对应，仅 V0.2.5 去掉 pgvector")
    void kingbaseScriptsArePostgresScriptsWithoutPgvector() throws IOException {
        Path base = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration");
        Path pg = base.resolve("postgresql");
        Path kb = base.resolve("kingbase");
        Set<String> pgFiles = sqlFiles(pg);
        Set<String> kbFiles = sqlFiles(kb);
        assertEquals(pgFiles, kbFiles, "Kingbase 与 PostgreSQL 脚本文件名必须一致");
        assertEquals(31, kbFiles.size(), "迁移脚本应为 31 条");

        for (String file : pgFiles) {
            String pgSql = Files.readString(pg.resolve(file), StandardCharsets.UTF_8);
            String kbSql = Files.readString(kb.resolve(file), StandardCharsets.UTF_8);
            if (file.equals("V0.2.5__f1_stage3_knowledge_rag.sql")) {
                assertFalse(kbSql.contains("CREATE EXTENSION"), "Kingbase 不应创建 pgvector 扩展");
                assertFalse(kbSql.contains("ADD COLUMN embedding"), "Kingbase 不应新增 vector 列");
                assertTrue(kbSql.contains("sw_knowledge_vector"), "应说明改用文本向量表");
            } else {
                assertEquals(pgSql, kbSql, "除 V0.2.5 外应与 PostgreSQL 逐字节一致: " + file);
            }
        }
    }

    private static Set<String> sqlFiles(Path dir) throws IOException {
        assertTrue(Files.isDirectory(dir), "目录不存在: " + dir);
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".sql"))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }
}
