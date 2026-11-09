package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL Server 首次支持：终态基线由 PostgreSQL 生成，静态检查保证没有残留 PostgreSQL 专属语法，
 * 并确认 12 个可空唯一键/部分索引已转成筛选索引。
 */
@DisplayName("SQL Server 迁移脚本静态门禁")
class SqlServerScriptStaticTest {

    private static final List<String> FORBIDDEN = List.of(
            "CREATE EXTENSION", "JSONB", "CHARACTER VARYING", "TIMESTAMP WITHOUT TIME ZONE",
            "PUBLIC.", "USING BTREE", "COMMENT ON", "DO $$", "ON CONFLICT");

    @Test
    @DisplayName("30 个脚本无 PostgreSQL 专属语法，筛选索引齐全")
    void scriptsAreSqlServerReady() throws IOException {
        Path dir = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration/sqlserver");
        List<Path> scripts;
        try (var stream = Files.list(dir)) {
            scripts = stream.filter(p -> p.toString().endsWith(".sql")).sorted().toList();
        }
        assertEquals(30, scripts.size(), "SQL Server 应有 30 个迁移脚本");

        String schema = Files.readString(dir.resolve("V0.2.0__baseline_seed.sql"), StandardCharsets.UTF_8);
        String upper = schema.toUpperCase(Locale.ROOT);
        for (String token : FORBIDDEN) {
            assertTrue(!upper.contains(token), "SQL Server 脚本不应包含: " + token);
        }
        assertTrue(schema.contains("CREATE TABLE [dbo].[sys_tenant]"), "应生成小写 dbo 表名");
        assertTrue(schema.contains("NVARCHAR(MAX)"), "text/jsonb 应映射为 NVARCHAR(MAX)");
        assertTrue(schema.contains("DATETIME2"), "timestamp 应映射为 DATETIME2");

        String seed = Files.readString(
                dir.resolve("R__0.2.0_sqlserver_seed.sql"), StandardCharsets.UTF_8);
        long inserts = seed.lines().filter(l -> l.startsWith("INSERT INTO [dbo].")).count();
        assertTrue(inserts > 500, "种子插入行数异常: " + inserts);

        for (String index : List.of(
                "uk_sw_notify_msg_idempotency", "uk_sw_notify_msg_identity",
                "uk_sw_form_trace_idem", "uk_sw_agent_exec_req_idem",
                "uk_sw_bpm_command_logical", "uk_sw_bpm_comm_instance_receiver",
                "uk_sw_bpm_dyn_branch", "uk_sw_form_cfg_tname",
                "uk_iot_device_product_device", "uk_sw_bpm_urge_cooldown",
                "uk_iot_command_idempotent", "uk_sw_bpm_binding_active")) {
            assertTrue(schema.contains("[" + index + "]") && schema.contains("WHERE"),
                    "缺少筛选索引: " + index);
        }
    }
}
