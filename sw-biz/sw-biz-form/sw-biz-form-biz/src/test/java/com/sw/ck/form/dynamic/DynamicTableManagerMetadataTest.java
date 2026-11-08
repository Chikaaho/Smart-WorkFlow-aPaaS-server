package com.sw.ck.form.dynamic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态宽表元数据存在性检查必须按方言查询：Oracle 没有 information_schema，
 * 且动态表以带引号小写名创建，不能用跨 schema 的模糊判断。
 */
@DisplayName("动态宽表元数据检查")
class DynamicTableManagerMetadataTest {

    private static final String TABLE = "sw_form_abcdefghij";

    @Test
    @DisplayName("H2 当前 schema 内可识别带引号小写表与列")
    void h2MetadataUsesCurrentSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dynmeta-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DynamicTableManager manager = new DynamicTableManager(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE \"" + TABLE + "\" (\"id\" VARCHAR(36), \"reason\" VARCHAR(100))");

        assertTrue(manager.tableExists(TABLE));
        assertTrue(manager.columnExists(TABLE, "reason"));
        assertFalse(manager.columnExists(TABLE, "missing_column"));
        assertFalse(manager.tableExists("sw_form_zzzzzzzzzz"));
    }
}
