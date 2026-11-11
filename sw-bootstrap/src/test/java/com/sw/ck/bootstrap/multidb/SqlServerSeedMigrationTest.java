package com.sw.ck.bootstrap.multidb;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL Server 种子比对专用目标库：只执行迁移与种子，不启动应用、不写入冒烟数据。
 * 未配置 {@code sqlserver.url} 时跳过。
 */
@DisplayName("SQL Server 种子比对目标库迁移")
class SqlServerSeedMigrationTest {

    private static final String DATABASE = "swmdb_sqlserver_seed";

    @Test
    @DisplayName("空库执行 32 条 SQL Server 迁移，保留库供 SeedComparisonTest 比对")
    void migrateSeedDatabase() throws Exception {
        String url = System.getProperty("sqlserver.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 sqlserver.url，跳过 SQL Server 种子目标库迁移");
        String user = System.getProperty("sqlserver.user", "sa");
        String password = System.getProperty("sqlserver.password", "");
        String masterUrl = url.replaceAll("(?i);databaseName=[^;]*", "") + ";databaseName=master";
        String targetUrl = masterUrl.replace("databaseName=master", "databaseName=" + DATABASE);

        try (Connection connection = DriverManager.getConnection(masterUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("IF DB_ID('" + DATABASE + "') IS NOT NULL BEGIN "
                    + "ALTER DATABASE [" + DATABASE + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; "
                    + "DROP DATABASE [" + DATABASE + "]; END");
            statement.executeUpdate("CREATE DATABASE [" + DATABASE + "] COLLATE Chinese_PRC_CS_AS");
        }

        var result = Flyway.configure()
                .dataSource(targetUrl, user, password)
                .locations("classpath:db/migration/sqlserver")
                .load()
                .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(32);

        // V1.0.0 终态核对（B1）：列 / 审计表 / 菜单 9115 / 导入导出按钮 / admin 授权
        try (Connection connection = DriverManager.getConnection(targetUrl, user, password);
             Statement statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("""
                    SELECT
                      (SELECT COUNT(*) FROM sys.columns c JOIN sys.tables t ON t.object_id = c.object_id
                        WHERE t.name = 'sys_user' AND c.name = 'must_change_password') AS must_col,
                      (SELECT COUNT(*) FROM sys.tables WHERE name = 'sys_audit_log') AS audit_table,
                      (SELECT COUNT(*) FROM sys_menu WHERE id IN (9115, 430, 431)) AS menus,
                      (SELECT COUNT(*) FROM sys_role_menu WHERE menu_id IN (9115, 430, 431)) AS grants
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("must_col")).isEqualTo(1);
                assertThat(rs.getInt("audit_table")).isEqualTo(1);
                assertThat(rs.getInt("menus")).isEqualTo(3);
                assertThat(rs.getInt("grants")).isEqualTo(3);
            }
        }
    }
}
