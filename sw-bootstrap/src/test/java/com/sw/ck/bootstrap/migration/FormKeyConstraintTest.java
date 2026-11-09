package com.sw.ck.bootstrap.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2：0.4.0 后 {@code sw_form_def.form_key} 的最终唯一索引必须是
 * {@code (tenant_id, form_key, deleted)}，PG 与 H2 真实迁移终态一致。
 * <p>
 * 回答 Planner 对 Oracle 去掉内联 {@code UNIQUE(form_key)} 后语义是否与 PG 不一致的疑问：
 * PG/H2 的 V13 先把隐式 {@code UNIQUE(form_key)} 改为 {@code (form_key, deleted)}，
 * 后续 V83 再改为 {@code (tenant_id, form_key, deleted)}；Oracle 脚本按同一最终形态建索引。
 * </p>
 */
@DisplayName("S2：sw_form_def.form_key 最终唯一索引 (tenant_id, form_key, deleted)")
class FormKeyConstraintTest {

    private static final String[] APP_LOCATIONS = {
            "classpath:db/migration/{vendor}",
            "classpath:db/migration/bpm/{vendor}",
            "classpath:db/migration/notify/{vendor}",
            "classpath:db/migration/form/{vendor}",
            "classpath:db/migration/storage/{vendor}",
            "classpath:db/migration/job/{vendor}",
            "classpath:db/migration/agent/{vendor}",
            "classpath:db/migration/iot/{vendor}",
            "classpath:db/migration/openapi/{vendor}",
            "classpath:db/migration/system/{vendor}"
    };

    @Test
    @DisplayName("H2：uk_sw_form_def_form_key 列顺序为 tenant_id, form_key, deleted")
    void h2FinalIndex() throws Exception {
        String url = "jdbc:h2:mem:form_key_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(Arrays.stream(APP_LOCATIONS)
                        .map(location -> location.replace("{vendor}", "h2")).toArray(String[]::new))
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                             + "WHERE INDEX_NAME = 'UK_SW_FORM_DEF_FORM_KEY' ORDER BY ORDINAL_POSITION")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1).toLowerCase());
            }
            assertEquals(List.of("tenant_id", "form_key", "deleted"), columns);
        }
    }

    @Test
    @DisplayName("PostgreSQL：uk_sw_form_def_form_key 定义含 (tenant_id, form_key, deleted)，无 UNIQUE(form_key)")
    void postgresFinalIndex() throws Exception {
        try (EmbeddedPostgres pg = EmbeddedPostgres.builder().start()) {
            String url = pg.getJdbcUrl("postgres", "postgres");
            Flyway.configure()
                    .dataSource(url, "postgres", "postgres")
                    .locations(Arrays.stream(APP_LOCATIONS)
                            .map(location -> location.replace("{vendor}", "postgresql")).toArray(String[]::new))
                    .load()
                    .migrate();
            try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres");
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT indexdef FROM pg_indexes "
                                 + "WHERE schemaname = 'public' AND indexname = 'uk_sw_form_def_form_key'")) {
                assertTrue(rs.next(), "应存在 uk_sw_form_def_form_key");
                String indexdef = rs.getString(1).toLowerCase();
                assertTrue(indexdef.contains("(tenant_id, form_key, deleted)"),
                        "最终索引应为 (tenant_id, form_key, deleted)，实际: " + indexdef);
                assertTrue(!indexdef.contains("unique (form_key)"),
                        "不应残留 UNIQUE(form_key)");
            }
            try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres");
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
                                 + "WHERE conrelid = 'sw_form_def'::regclass AND contype = 'u'")) {
                while (rs.next()) {
                    assertTrue(!rs.getString(2).toLowerCase().contains("(form_key)"),
                            "不应存在 UNIQUE(form_key) 约束: " + rs.getString(1));
                }
            }
        }
    }

    @Test
    @DisplayName("MySQL/MariaDB：uk_sw_form_def_form_key 列顺序为 tenant_id, form_key, deleted")
    void mysqlFinalIndex() throws Exception {
        String url = System.getProperty("formkey.mysql.url");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 formkey.mysql.url，跳过 MySQL form_key 断言");
        String user = System.getProperty("formkey.mysql.user", "root");
        String password = System.getProperty("formkey.mysql.password", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT column_name FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = 'sw_form_def' "
                             + "AND index_name = 'uk_sw_form_def_form_key' ORDER BY seq_in_index")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1).toLowerCase());
            }
            assertEquals(List.of("tenant_id", "form_key", "deleted"), columns);
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT index_name FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = 'sw_form_def' "
                             + "AND non_unique = 0 GROUP BY index_name "
                             + "HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index) = 'form_key'")) {
            assertTrue(!rs.next(), "不应残留仅 form_key 的唯一索引");
        }
    }

    @Test
    @DisplayName("Oracle：uk_sw_form_def_form_key 列顺序为 tenant_id, form_key, deleted")
    void oracleFinalIndex() throws Exception {
        String url = System.getProperty("formkey.oracle.url");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 formkey.oracle.url，跳过 Oracle form_key 断言");
        String user = System.getProperty("formkey.oracle.user", "swmdb");
        String password = System.getProperty("formkey.oracle.password", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT column_name FROM user_ind_columns "
                             + "WHERE index_name = 'UK_SW_FORM_DEF_FORM_KEY' ORDER BY column_position")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1).toLowerCase());
            }
            assertEquals(List.of("tenant_id", "form_key", "deleted"), columns);
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT i.index_name FROM user_indexes i JOIN user_ind_columns c "
                             + "ON i.index_name = c.index_name "
                             + "WHERE i.table_name = 'SW_FORM_DEF' AND i.uniqueness = 'UNIQUE' "
                             + "GROUP BY i.index_name "
                             + "HAVING COUNT(*) = 1 AND MAX(c.column_name) = 'FORM_KEY'")) {
            assertTrue(!rs.next(), "不应残留仅 form_key 的唯一索引");
        }
    }

    @Test
    @DisplayName("SQL Server：uk_sw_form_def_form_key 列顺序为 tenant_id, form_key, deleted")
    void sqlServerFinalIndex() throws Exception {
        String url = System.getProperty("formkey.sqlserver.url");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 formkey.sqlserver.url，跳过 SQL Server form_key 断言");
        String user = System.getProperty("formkey.sqlserver.user", "sa");
        String password = System.getProperty("formkey.sqlserver.password", "");
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT c.name FROM sys.indexes i "
                             + "JOIN sys.index_columns ic ON ic.object_id = i.object_id "
                             + "AND ic.index_id = i.index_id "
                             + "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id "
                             + "WHERE i.object_id = OBJECT_ID('dbo.sw_form_def') "
                             + "AND i.name = 'uk_sw_form_def_form_key' ORDER BY ic.key_ordinal")) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString(1).toLowerCase());
            }
            assertEquals(List.of("tenant_id", "form_key", "deleted"), columns);
        }
    }
}
