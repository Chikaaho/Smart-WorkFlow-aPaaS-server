package com.sw.ck.bootstrap.multidb;

import com.sw.ck.bootstrap.migration.SwVendorMigrationRunner;
import com.sw.ck.common.jdbc.DatabaseVendor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * openGauss 种子比对专用目标库：只执行迁移与种子，不启动应用、不写入冒烟数据。
 * 未配置 {@code opengauss.url} 时跳过。
 */
@DisplayName("openGauss 种子比对目标库迁移")
class OpenGaussSeedMigrationTest {

    private static final String DATABASE = "swmdb_og_seed";

    @Test
    @DisplayName("空库执行 32 条 openGauss 迁移，保留库供 SeedComparisonTest 比对")
    void migrateSeedDatabase() throws Exception {
        String url = System.getProperty("opengauss.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 opengauss.url，跳过 openGauss 种子目标库迁移");
        String user = System.getProperty("opengauss.user", "gaussdb");
        String password = System.getProperty("opengauss.password", "");
        String serverUrl = url.substring(0, url.lastIndexOf('/') + 1) + "postgres";

        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
            statement.executeUpdate("CREATE DATABASE " + DATABASE
                    + " WITH ENCODING 'UTF8' DBCOMPATIBILITY 'PG' TEMPLATE=template0");
        }

        String targetUrl = url.substring(0, url.lastIndexOf('/') + 1) + DATABASE;
        DriverManagerDataSource dataSource = new DriverManagerDataSource(targetUrl, user, password);
        dataSource.setDriverClassName("org.opengauss.Driver");
        SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.OPENGAUSS);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_schema_history", Integer.class))
                .isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_tenant", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class)).isEqualTo(146);
    }
}
