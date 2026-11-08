package com.sw.ck.form.dynamic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 动态宽表 DDL/DML 的标识符引用必须与数据库方言一致：
 * 内部构造统一使用 ANSI 双引号，MySQL/MariaDB 执行前改写为反引号；
 * DDL 契约检查必须同时接受双引号、反引号、方括号与未引用表名。
 */
@DisplayName("动态宽表方言标识符引用")
class DynamicTableSqlVendorQuoteTest {

    private static final String TABLE = "sw_form_abcdefghij";

    @Test
    @DisplayName("DDL 目标表：四种引用写法均可识别")
    void ddlTargetAcceptsAllVendorQuotes() {
        assertEquals(TABLE, DynamicTableSql.ddlTargetTable(
                "CREATE TABLE \"" + TABLE + "\" (\n  \"id\" VARCHAR(36)\n)"));
        assertEquals(TABLE, DynamicTableSql.ddlTargetTable(
                "CREATE TABLE `" + TABLE + "` (\n  `id` VARCHAR(36)\n)"));
        assertEquals(TABLE, DynamicTableSql.ddlTargetTable(
                "ALTER TABLE [" + TABLE + "] ADD COLUMN [name] VARCHAR(100)"));
        assertEquals(TABLE, DynamicTableSql.ddlTargetTable(
                "CREATE TABLE " + TABLE + " (id VARCHAR(36))"));
    }

    @Test
    @DisplayName("MySQL/MariaDB 执行前把 ANSI 标识符改写为反引号")
    void mysqlIdentifiersRewrittenToBackticks() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplateFor("jdbc:mysql://localhost:3306/db");
        String sql = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?";
        assertEquals("SELECT `id` FROM `" + TABLE + "` WHERE `deleted` = 0 AND `tenant_id` = ?",
                DynamicTableSql.toVendorSql(jdbcTemplate, sql));
    }

    @Test
    @DisplayName("H2/PostgreSQL 等保持 ANSI 双引号")
    void ansiVendorsKeepDoubleQuotes() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplateFor("jdbc:h2:mem:vendor-quote");
        String sql = "SELECT \"id\" FROM \"" + TABLE + "\" WHERE \"deleted\" = 0 AND \"tenant_id\" = ?";
        assertEquals(sql, DynamicTableSql.toVendorSql(jdbcTemplate, sql));
    }

    private static JdbcTemplate jdbcTemplateFor(String url) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(url);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.getDataSource()).thenReturn(dataSource);
        return jdbcTemplate;
    }
}
