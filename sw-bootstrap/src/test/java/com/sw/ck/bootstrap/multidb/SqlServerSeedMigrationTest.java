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
    @DisplayName("空库执行 31 条 SQL Server 迁移，保留库供 SeedComparisonTest 比对")
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
        assertThat(result.migrationsExecuted).isEqualTo(31);
    }
}
