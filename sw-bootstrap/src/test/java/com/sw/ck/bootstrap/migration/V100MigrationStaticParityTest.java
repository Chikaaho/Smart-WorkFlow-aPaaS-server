package com.sw.ck.bootstrap.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.0.0 迁移种子静态核对（planning-review-100-01 §4 B1）。
 * <p>
 * 九种方言的 V1.0.0 必须同时包含：首登改密列、审计日志表、日志查询菜单 9115、
 * 人员导入/导出按钮与 admin 授权种子。SQL Server 的菜单/授权位于可重复脚本
 * （历史基线在 R__0.2.0 种子中，版本化脚本先执行时父菜单尚不存在，本轮实测发现
 * 按钮与授权会被静默跳过），因此静态核对同时覆盖 R__1.0.0。
 * </p>
 */
@DisplayName("V1.0.0 九方言种子静态核对")
class V100MigrationStaticParityTest {

    private static final List<String> DIALECTS = List.of(
            "postgresql", "h2", "kingbase", "opengauss", "mysql",
            "mariadb", "oracle", "dm", "sqlserver");

    private static final List<String> REQUIRED_TOKENS = List.of(
            "must_change_password", "sys_audit_log", "9115",
            "system:user:import", "system:user:export", "sys_role_menu");

    @Test
    @DisplayName("九方言均含 F1-4 列、F3 表、日志查询菜单、导入导出按钮与 admin 授权")
    void allDialectsCarryV100Seed() throws IOException {
        Path dir = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration");
        for (String dialect : DIALECTS) {
            StringBuilder content = new StringBuilder(Files.readString(
                    dir.resolve(dialect).resolve("V1.0.0__audit_log_and_must_change_password.sql"),
                    StandardCharsets.UTF_8));
            if ("sqlserver".equals(dialect)) {
                // SQL Server：菜单/按钮/授权在可重复脚本（基线种子之后执行）
                content.append(Files.readString(
                        dir.resolve(dialect).resolve("R__1.0.0_audit_log_menu.sql"),
                        StandardCharsets.UTF_8));
            }
            String sql = content.toString();
            for (String token : REQUIRED_TOKENS) {
                assertTrue(sql.contains(token),
                        dialect + " 的 V1.0.0 种子缺少: " + token);
            }
        }
    }

    @Test
    @DisplayName("SQL Server 菜单种子不放在版本化 V1.0.0（避免首装父菜单未就绪被跳过）")
    void sqlServerMenusStayInRepeatableScript() throws IOException {
        Path dir = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/db/migration/sqlserver");
        String versioned = Files.readString(
                dir.resolve("V1.0.0__audit_log_and_must_change_password.sql"),
                StandardCharsets.UTF_8);
        assertTrue(!versioned.contains("INSERT INTO [dbo].[sys_menu]"),
                "SQL Server 的菜单插入应放在 R__1.0.0_audit_log_menu.sql（可重复脚本）");
        String repeatable = Files.readString(
                dir.resolve("R__1.0.0_audit_log_menu.sql"), StandardCharsets.UTF_8);
        assertTrue(repeatable.contains("9115") && repeatable.contains("430")
                        && repeatable.contains("431"),
                "SQL Server 可重复脚本应含 9115/430/431");
    }
}
