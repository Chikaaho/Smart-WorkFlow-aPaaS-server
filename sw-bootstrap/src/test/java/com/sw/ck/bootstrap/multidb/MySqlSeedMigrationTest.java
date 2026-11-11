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
 * MySQL 种子比对专用目标库：只执行迁移与种子，不启动应用、不写入冒烟数据。
 * 未配置 {@code mysql.url} 时跳过。
 */
@DisplayName("MySQL 种子比对目标库迁移")
class MySqlSeedMigrationTest {

    private static final String DATABASE = "swmdb_mysql_seed";

    @Test
    @DisplayName("空库执行 31 条 MySQL 迁移，保留库供 SeedComparisonTest 比对")
    void migrateSeedDatabase() throws Exception {
        String url = System.getProperty("mysql.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 mysql.url，跳过 MySQL 种子目标库迁移");
        String user = System.getProperty("mysql.user", "root");
        String password = System.getProperty("mysql.password", "");
        int queryAt = url.indexOf('?');
        String base = queryAt >= 0 ? url.substring(0, queryAt) : url;
        String params = queryAt >= 0 ? url.substring(queryAt) : "";
        if (!params.contains("sessionVariables=")) {
            params += (params.isEmpty() ? "?" : "&") + "sessionVariables=explicit_defaults_for_timestamp=ON";
        }
        String prefix = base.substring(0, base.lastIndexOf('/') + 1);
        String serverUrl = prefix + "mysql" + params;
        String targetUrl = prefix + DATABASE + params;

        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
            statement.executeUpdate("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }

        var result = Flyway.configure()
                .dataSource(targetUrl, user, password)
                .locations("classpath:db/migration/mysql")
                .load()
                .migrate();
        assertThat(result.migrationsExecuted).isEqualTo(31);
    }
}
