package com.sw.ck.bootstrap.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL 5.7/8.0 空库全链 Flyway 迁移验证（真实 MySQL；未配置 {@code mysql.url} 时跳过）。
 * <p>
 * 通过系统属性指定服务器连接，例如：
 * {@code -Dmysql.url=jdbc:mysql://127.0.0.1:13306/mysql -Dmysql.user=root -Dmysql.password=...}。
 * 测试自行创建/删除临时库 {@code swmdb_flyway}；位置固定为 {@code classpath:db/migration/mysql}。
 * 断言 31 条迁移、终点 V1.0.0、重复 migrate 零执行、{@code opinion_comment} 与向量表存在。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MySQL 空库全链迁移（真实库；未配置 mysql.url 时跳过）")
class FlywayFullChainMySqlTest {

    private static final String DATABASE = "swmdb_flyway";
    private static final Pattern JDBC_URL = Pattern.compile("^(jdbc:mysql://[^/]+)/([^?]*)(\\?.*)?$");

    private String serverUrl;
    private String targetUrl;
    private String user;
    private String password;

    @Test
    @DisplayName("空库执行 31 条迁移到 V1.0.0，重复 migrate 零执行，关键对象存在")
    void migrateEmptyDatabase() throws Exception {
        String url = System.getProperty("mysql.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 mysql.url，跳过 MySQL 全链迁移");
        user = System.getProperty("mysql.user", "root");
        password = System.getProperty("mysql.password", "");
        Matcher matcher = JDBC_URL.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("mysql.url 应形如 jdbc:mysql://host:port/db: " + url);
        }
        serverUrl = matcher.group(1) + "/mysql" + (matcher.group(3) == null ? "" : matcher.group(3));
        targetUrl = matcher.group(1) + "/" + DATABASE + (matcher.group(3) == null ? "" : matcher.group(3));

        recreateDatabase();

        Flyway flyway = Flyway.configure()
                .dataSource(targetUrl, user, password)
                .locations("classpath:db/migration/mysql")
                .load();
        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "MySQL 全链迁移应成功");
        assertEquals(31, result.migrationsExecuted,
                "全新 MySQL 库应执行 31 条（V0.2.0 基线 + V0.2.2—V0.2.9 + V0.4.0/V1.0.0 + 20 个 R__）");
        assertEquals("1.0.0", flyway.info().current().getVersion().getVersion(),
                "MySQL 迁移终点应为 V1.0.0");
        assertEquals(0, flyway.migrate().migrationsExecuted, "重复 migrate 应零执行");
        flyway.validate();

        try (Connection connection = DriverManager.getConnection(targetUrl, user, password)) {
            assertTrue(columnExists(connection, "sw_bpm_task_opinion_draft", "opinion_comment"),
                    "MySQL 建表应直接使用 opinion_comment");
            assertTrue(tableExists(connection, "sw_knowledge_vector"), "MySQL 应创建向量持久化表");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sys_tenant WHERE id = 0")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "默认租户种子应存在");
            }
        }
    }

    @AfterAll
    void dropScratchDatabase() {
        if (serverUrl == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
        } catch (SQLException ignored) {
            // 清理容错
        }
    }

    private void recreateDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
            statement.executeUpdate("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, "%")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
