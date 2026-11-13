package com.sw.ck.bootstrap.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle 21c XE 空库全链 Flyway 迁移验证（未配置 {@code oracle.url} 时跳过）。
 * <p>
 * 通过系统属性指定连接，例如：
 * {@code -Doracle.url=jdbc:oracle:thin:@//127.0.0.1:11521/XEPDB1 -Doracle.user=swmdb
 * -Doracle.password=...}。测试会先清空当前用户 schema（仅用于专用验证容器/账号），
 * 再执行 {@code classpath:db/migration/oracle} 的 32 条迁移，断言终点 V1.1.0、
 * 重复 migrate 零执行、{@code opinion_comment} 与向量表存在。
 * </p>
 */
@DisplayName("Oracle 空库全链迁移（真实库；未配置 oracle.url 时跳过）")
class FlywayFullChainOracleTest {

    @Test
    @DisplayName("空库执行 32 条迁移到 V1.1.0，重复 migrate 零执行，关键对象存在")
    void migrateEmptyDatabase() throws Exception {
        String url = System.getProperty("oracle.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 oracle.url，跳过 Oracle 全链迁移");
        String user = System.getProperty("oracle.user", "swmdb");
        String password = System.getProperty("oracle.password", "");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            java.util.List<String> tables = new java.util.ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT table_name FROM user_tables")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                statement.executeUpdate("DROP TABLE \"" + table + "\" CASCADE CONSTRAINTS PURGE");
            }
            statement.execute("PURGE RECYCLEBIN");
            java.util.List<String> views = new java.util.ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT view_name FROM user_views")) {
                while (rs.next()) {
                    views.add(rs.getString(1));
                }
            }
            for (String view : views) {
                statement.executeUpdate("DROP VIEW \"" + view + "\"");
            }
            java.util.List<String> sequences = new java.util.ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT sequence_name FROM user_sequences")) {
                while (rs.next()) {
                    sequences.add(rs.getString(1));
                }
            }
            for (String sequence : sequences) {
                statement.executeUpdate("DROP SEQUENCE \"" + sequence + "\"");
            }
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration/oracle")
                .load();
        MigrateResult result = flyway.migrate();
        assertTrue(result.success, "Oracle 全链迁移应成功");
        assertEquals(32, result.migrationsExecuted,
                "全新 Oracle schema 应执行 32 条（V0.2.0 + V0.2.2—V0.2.9 + V0.4.0/V1.0.0/V1.1.0 + 19 个 R__）");
        assertEquals("1.1.0", flyway.info().current().getVersion().getVersion(),
                "Oracle 迁移终点应为 V1.1.0");
        assertEquals(0, flyway.migrate().migrationsExecuted, "重复 migrate 应零执行");
        flyway.validate();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertTrue(columnExists(connection, "SW_BPM_TASK_OPINION_DRAFT", "OPINION_COMMENT"),
                    "Oracle 建表应直接使用 opinion_comment");
            assertTrue(tableExists(connection, "SW_KNOWLEDGE_VECTOR"), "Oracle 应创建向量持久化表");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM sys_tenant WHERE id = 0")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "默认租户种子应存在");
            }
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
