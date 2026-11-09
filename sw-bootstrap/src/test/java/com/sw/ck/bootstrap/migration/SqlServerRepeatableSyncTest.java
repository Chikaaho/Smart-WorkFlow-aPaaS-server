package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U2：SQL Server 使用终态基线 + 占位脚本，PG 的 R__ 变化不会自动同步。
 * 本测试固化 PG R__ 与 SQL Server 种子脚本的校验和：任何 PG R__ 改动都会失败，
 * 强制维护者同时修改 SQL Server 增量脚本并更新清单。
 */
@DisplayName("U2：PG R__ 与 SQL Server 基线同步门禁")
class SqlServerRepeatableSyncTest {

    @Test
    @DisplayName("PG R__ 校验和与 SQL Server 种子校验和必须匹配清单")
    void pgRepeatableChecksumsArePinned() throws IOException {
        Path dir = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration");
        Path manifestPath = dir.resolve("sqlserver/pg-repeatable-checksums.properties");
        Properties manifest = new Properties();
        try (InputStream in = Files.newInputStream(manifestPath)) {
            manifest.load(in);
        }

        List<String> mismatches = new ArrayList<>();
        try (var stream = Files.list(dir.resolve("postgresql"))) {
            for (Path file : stream.filter(p -> p.getFileName().toString().startsWith("R__")).toList()) {
                String name = file.getFileName().toString();
                String expected = manifest.getProperty(name);
                if (expected == null) {
                    mismatches.add("清单缺少 " + name);
                } else if (!expected.equals(sha256(file))) {
                    mismatches.add("PG R__ 已变化: " + name
                            + "；请同步 SQL Server 增量脚本并更新 " + manifestPath.getFileName());
                }
            }
        }
        String seed = sha256(dir.resolve("sqlserver/R__0.2.0_sqlserver_seed.sql"));
        if (!seed.equals(manifest.getProperty("sqlserver.seed"))) {
            mismatches.add("SQL Server 种子脚本已变化；请确认后更新 sqlserver.seed");
        }
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
        assertEquals(20, manifest.stringPropertyNames().stream()
                .filter(name -> name.startsWith("R__")).count(), "PG R__ 清单应覆盖 20 个脚本");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }
}
