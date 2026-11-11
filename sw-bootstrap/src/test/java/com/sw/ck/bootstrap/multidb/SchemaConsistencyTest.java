package com.sw.ck.bootstrap.multidb;

import com.sw.ck.common.jdbc.DatabaseVendor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U1：以 PG 1.0.0 终态为基准，对比每个实测库的表集合、每表列集合、每列可空性。
 * <p>
 * 白名单仅两项：
 * <ul>
 *   <li>PG 的 {@code sw_knowledge_chunk.embedding}（pgvector 列，非 PG 库用文本向量表）；</li>
 *   <li>MySQL/MariaDB 的 {@code sw_bpm_form_binding.active_key}（部分唯一索引的生成列）。</li>
 * </ul>
 * 未配置 {@code schema.base.url} / {@code schema.target.url} 时跳过。
 */
@DisplayName("U1：跨库表结构一致性（表/列/可空性）")
class SchemaConsistencyTest {

    private static final Set<String> IGNORED_COLUMNS = Set.of(
            "sw_knowledge_chunk.embedding",
            "sw_bpm_form_binding.active_key");

    @Test
    @DisplayName("目标库与 PG 基准的表、列、可空性一致（白名单除外）")
    void compareSchema() throws Exception {
        String baseUrl = System.getProperty("schema.base.url");
        String targetUrl = System.getProperty("schema.target.url");
        Assumptions.assumeTrue(baseUrl != null && targetUrl != null,
                "未配置 schema.base.url / schema.target.url，跳过表结构比对");

        try (Connection base = connect("schema.base.", baseUrl);
             Connection target = connect("schema.target.", targetUrl)) {
            DatabaseVendor targetVendor = DatabaseVendor.detect(target);
            Map<String, Map<String, Boolean>> baseSchema = readSchema(base, DatabaseVendor.POSTGRESQL);
            Map<String, Map<String, Boolean>> targetSchema = readSchema(target, targetVendor);

            List<String> diffs = new ArrayList<>();
            Set<String> baseTables = new TreeSet<>(baseSchema.keySet());
            Set<String> targetTables = new TreeSet<>(targetSchema.keySet());
            baseTables.removeAll(targetTables);
            targetTables.removeAll(baseSchema.keySet());
            for (String missing : baseTables) {
                diffs.add("目标缺表: " + missing);
            }
            for (String extra : targetTables) {
                diffs.add("目标多表: " + extra);
            }

            for (Map.Entry<String, Map<String, Boolean>> entry : baseSchema.entrySet()) {
                String table = entry.getKey();
                Map<String, Boolean> targetColumns = targetSchema.get(table);
                if (targetColumns == null) {
                    continue;
                }
                Map<String, Boolean> baseColumns = entry.getValue();
                for (Map.Entry<String, Boolean> column : baseColumns.entrySet()) {
                    String key = table + "." + column.getKey();
                    if (IGNORED_COLUMNS.contains(key)) {
                        continue;
                    }
                    if (!targetColumns.containsKey(column.getKey())) {
                        diffs.add("目标缺列: " + key);
                    } else if (!column.getValue().equals(targetColumns.get(column.getKey()))) {
                        diffs.add("可空性不一致: " + key + " base=" + column.getValue()
                                + " target=" + targetColumns.get(column.getKey()));
                    }
                }
                for (String column : targetColumns.keySet()) {
                    String key = table + "." + column;
                    if (!IGNORED_COLUMNS.contains(key) && !baseColumns.containsKey(column)) {
                        diffs.add("目标多列: " + key);
                    }
                }
            }
            assertThat(diffs).as("表结构差异（目标厂商 %s）", targetVendor.displayName()).isEmpty();
        }
    }

    private static Connection connect(String prefix, String url) throws SQLException {
        String user = System.getProperty(prefix + "user", "");
        String password = System.getProperty(prefix + "password", "");
        return DriverManager.getConnection(url, user, password);
    }

    private static Map<String, Map<String, Boolean>> readSchema(Connection connection, DatabaseVendor vendor)
            throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String catalog = null;
        String schema = null;
        switch (vendor) {
            case MYSQL, MARIADB -> catalog = connection.getCatalog();
            case SQL_SERVER -> {
                catalog = connection.getCatalog();
                schema = "dbo";
            }
            case ORACLE -> schema = connection.getSchema() == null
                    ? meta.getUserName().toUpperCase(Locale.ROOT) : connection.getSchema();
            default -> schema = "public";
        }
        Map<String, Map<String, Boolean>> result = new TreeMap<>();
        try (ResultSet tables = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String actual = tables.getString("TABLE_NAME");
                String table = actual.toLowerCase(Locale.ROOT);
                if (!(table.startsWith("sys_") || table.startsWith("sw_"))
                        || table.equals("sw_schema_history")
                        || table.equals("sw_schema_history_lock")) {
                    continue;
                }
                Map<String, Boolean> columns = new LinkedHashMap<>();
                try (ResultSet rs = meta.getColumns(catalog, schema, actual, "%")) {
                    while (rs.next()) {
                        columns.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                                rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
                    }
                }
                result.put(table, columns);
            }
        }
        return result;
    }
}
