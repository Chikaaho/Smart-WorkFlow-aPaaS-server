package com.sw.ck.bootstrap.multidb;

import com.sw.ck.common.jdbc.DatabaseVendor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子两层比对：所有表行数 + 关键表关键列逐值摘要 + 其余列逐值比对。
 * <p>
 * 通过系统属性指定基准库和目标库：
 * {@code -Dseed.base.url=... -Dseed.base.user=... -Dseed.base.password=...}
 * 与 {@code seed.target.*}。未配置时跳过，供阶段 B/C 每库执行。
 * 基准库由 PostgreSQL 空库执行完整迁移后得到；目标库执行对应方言迁移。
 * </p>
 * <p>
 * 第一层：全部 {@code sys_*}/{@code sw_*} 表行数；{@link #KEY_COLUMNS} 中的 18 张表
 * 按主键排序后的关键列 SHA-256。
 * 第二层：按主键配对，比较所有共有列；空字符串与 NULL 统一归一为同一哨兵，
 * 时间/日期列归一为非空哨兵（种子里的 {@code current_timestamp} 因执行时刻不同，
 * 不参与逐值比较，但 NULL/非 NULL 仍区分）。差异报告定位到“表/主键/列”。
 * Oracle 等大写折叠库通过 {@link DatabaseMetaData} 取实际标识符后再引用。
 * </p>
 */
class SeedComparisonTest {

    private static final int MAX_DIFFS = 200;

    private static final Map<String, List<String>> KEY_COLUMNS = new LinkedHashMap<>();

    static {
        KEY_COLUMNS.put("sys_tenant", List.of("id", "code", "name", "status"));
        KEY_COLUMNS.put("sys_dept", List.of("id", "tenant_id", "parent_id", "name", "code", "sort", "status", "leader_id"));
        KEY_COLUMNS.put("sys_user", List.of("id", "tenant_id", "username", "real_name", "dept_id", "status", "is_admin"));
        KEY_COLUMNS.put("sys_role", List.of("id", "tenant_id", "name", "code", "sort", "status", "data_scope", "is_builtin"));
        KEY_COLUMNS.put("sys_menu", List.of("id", "parent_id", "name", "title", "hidden", "menu_type", "path", "component", "permission", "sort"));
        KEY_COLUMNS.put("sys_role_menu", List.of("id", "tenant_id", "role_id", "menu_id"));
        KEY_COLUMNS.put("sys_user_role", List.of("id", "tenant_id", "user_id", "role_id"));
        KEY_COLUMNS.put("sys_dict_type", List.of("id", "tenant_id", "code", "name", "status"));
        KEY_COLUMNS.put("sys_dict_data", List.of("id", "tenant_id", "dict_code", "dict_value", "label", "sort", "status"));
        KEY_COLUMNS.put("sys_workspace_card_type", List.of("id", "tenant_id", "type_code", "display_name", "renderer_key", "default_span", "default_order", "status"));
        KEY_COLUMNS.put("sw_bpm_resource_usage", List.of("id", "scope", "scope_key", "segment", "outstanding"));
        KEY_COLUMNS.put("sw_bpm_node_function", List.of("id", "func_key", "func_version", "func_type", "impl_bean", "config", "allowed_nodes", "timeout_ms", "failure_strategy", "enabled"));
        KEY_COLUMNS.put("sw_form_app", List.of("id", "tenant_id", "app_key", "app_name", "status"));
        KEY_COLUMNS.put("sw_form_app_scope", List.of("id", "tenant_id", "app_id", "subject_type", "subject_id"));
        KEY_COLUMNS.put("sw_openapi_app", List.of("id", "tenant_id", "app_id", "app_name", "status"));
        KEY_COLUMNS.put("sw_agent_provider", List.of("id", "tenant_id", "provider_code", "provider_name", "enabled"));
        KEY_COLUMNS.put("sw_agent_model", List.of("id", "tenant_id", "provider_id", "model_code", "model_name", "enabled"));
        KEY_COLUMNS.put("sw_agent_graph_snapshot", List.of("id", "tenant_id", "graph_def_id", "version"));
    }

    @Test
    @DisplayName("种子两层比对：行数 + 关键列摘要 + 其余列逐值")
    void compareSeedData() throws Exception {
        String baseUrl = System.getProperty("seed.base.url");
        String targetUrl = System.getProperty("seed.target.url");
        Assumptions.assumeTrue(baseUrl != null && targetUrl != null,
                "未配置 seed.base.url / seed.target.url，跳过种子比对");

        try (Connection base = connect("seed.base.", baseUrl);
             Connection target = connect("seed.target.", targetUrl)) {
            List<String> tables = seedTables(base);
            assertThat(seedTables(target)).containsAll(tables);
            List<String> mismatches = new ArrayList<>();
            for (String table : tables) {
                long baseCount = count(base, table);
                long targetCount = count(target, table);
                if (baseCount != targetCount) {
                    addDiff(mismatches, table + " 行数 " + baseCount + " != " + targetCount);
                    continue;
                }
                List<String> keys = KEY_COLUMNS.get(table);
                if (keys != null && !keys.isEmpty()) {
                    String baseHash = hash(base, table, keys);
                    String targetHash = hash(target, table, keys);
                    if (!baseHash.equals(targetHash)) {
                        addDiff(mismatches, table + " 关键列摘要不一致 base=" + baseHash + " target=" + targetHash);
                    } else {
                        System.out.printf("COUNT+KEY OK %-36s %d %s%n", table, baseCount, baseHash.substring(0, 12));
                    }
                } else {
                    System.out.printf("COUNT OK     %-36s %d%n", table, baseCount);
                }
                compareAllColumns(base, target, table, mismatches);
            }
            assertThat(mismatches).as("种子两层比对差异（表/主键/列）").isEmpty();
        }
    }

    private static void addDiff(List<String> mismatches, String diff) {
        if (mismatches.size() < MAX_DIFFS) {
            mismatches.add(diff);
        }
    }

    private static Connection connect(String prefix, String url) throws SQLException {
        String user = System.getProperty(prefix + "user", "");
        String password = System.getProperty(prefix + "password", "");
        return DriverManager.getConnection(url, user, password);
    }

    private static List<String> seedTables(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        for (String name : tableNames(connection).keySet()) {
            if ((name.startsWith("sys_") || name.startsWith("sw_"))
                    && !name.equals("sw_schema_history")
                    && !name.equals("sw_schema_history_lock")) {
                tables.add(name);
            }
        }
        return new ArrayList<>(tables);
    }

    /** 逻辑小写表名 → 数据库实际表名（Oracle 为大写）。 */
    private static Map<String, String> tableNames(Connection connection) throws SQLException {
        Map<String, String> names = new LinkedHashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String actual = rs.getString("TABLE_NAME");
                names.putIfAbsent(actual.toLowerCase(Locale.ROOT), actual);
            }
        }
        return names;
    }

    private static String actualTable(Connection connection, String logical) throws SQLException {
        return tableNames(connection).get(logical.toLowerCase(Locale.ROOT));
    }

    /** 逻辑小写列名 → 数据库实际列名。 */
    private static Map<String, String> columnNames(Connection connection, String actualTable)
            throws SQLException {
        Map<String, String> names = new LinkedHashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, actualTable, "%")) {
            while (rs.next()) {
                String actual = rs.getString("COLUMN_NAME");
                names.putIfAbsent(actual.toLowerCase(Locale.ROOT), actual);
            }
        }
        return names;
    }

    private static List<String> primaryKey(Connection connection, String actualTable)
            throws SQLException {
        Map<Integer, String> ordered = new LinkedHashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getPrimaryKeys(null, null, actualTable)) {
            while (rs.next()) {
                ordered.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return ordered.values().stream().toList();
    }

    private static long count(Connection connection, String logicalTable) throws SQLException {
        DatabaseVendor vendor = DatabaseVendor.detect(connection);
        String actual = actualTable(connection, logicalTable);
        if (actual == null) {
            return -1;
        }
        String sql = "SELECT COUNT(*) FROM " + vendor.wrapIdentifier(actual);
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String hash(Connection connection, String logicalTable, List<String> keys)
            throws SQLException {
        DatabaseVendor vendor = DatabaseVendor.detect(connection);
        String actualTable = actualTable(connection, logicalTable);
        Map<String, String> columns = columnNames(connection, actualTable);
        List<String> actualKeys = keys.stream()
                .map(key -> columns.get(key.toLowerCase(Locale.ROOT)))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (actualKeys.size() != keys.size()) {
            return "MISSING-KEYS";
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        appendColumns(sql, vendor, actualKeys);
        sql.append(" FROM ").append(vendor.wrapIdentifier(actualTable)).append(" ORDER BY ");
        appendColumns(sql, vendor, actualKeys);
        MessageDigest digest = sha256();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    digest.update(normalize(rs.getObject(i)).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0x1f);
                }
                digest.update((byte) 0x1e);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void appendColumns(StringBuilder sql, DatabaseVendor vendor, List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(vendor.wrapIdentifier(columns.get(i)));
        }
    }

    /**
     * 第二层：按主键配对比较所有共有列；差异定位到“表/主键/列”。
     * 无主键的表跳过并在输出中标注。
     */
    private static void compareAllColumns(Connection base, Connection target, String logicalTable,
                                          List<String> mismatches) throws SQLException {
        DatabaseVendor baseVendor = DatabaseVendor.detect(base);
        DatabaseVendor targetVendor = DatabaseVendor.detect(target);
        String baseTable = actualTable(base, logicalTable);
        String targetTable = actualTable(target, logicalTable);
        if (baseTable == null || targetTable == null) {
            addDiff(mismatches, logicalTable + " 表缺失（base=" + baseTable + ", target=" + targetTable + "）");
            return;
        }
        List<String> basePk = primaryKey(base, baseTable);
        List<String> targetPk = primaryKey(target, targetTable);
        if (basePk.isEmpty() || targetPk.isEmpty()) {
            System.out.printf("LAYER2 SKIP  %-36s 无主键%n", logicalTable);
            return;
        }
        Map<String, String> baseColumns = columnNames(base, baseTable);
        Map<String, String> targetColumns = columnNames(target, targetTable);
        List<String> logicalColumns = baseColumns.keySet().stream()
                .filter(targetColumns::containsKey)
                .toList();
        List<String> baseActual = logicalColumns.stream().map(baseColumns::get).toList();
        List<String> targetActual = logicalColumns.stream().map(targetColumns::get).toList();

        Map<String, List<String>> baseRows =
                readRows(base, baseVendor, baseTable, basePk, baseActual);
        Map<String, List<String>> targetRows =
                readRows(target, targetVendor, targetTable, targetPk, targetActual);

        for (String key : baseRows.keySet()) {
            List<String> targetValues = targetRows.get(key);
            if (targetValues == null) {
                addDiff(mismatches, logicalTable + "[" + key + "] 目标库缺行");
                continue;
            }
            List<String> values = baseRows.get(key);
            for (int i = 0; i < logicalColumns.size(); i++) {
                if (!java.util.Objects.equals(values.get(i), targetValues.get(i))) {
                    addDiff(mismatches, logicalTable + "[" + key + "] 列 "
                            + logicalColumns.get(i) + " base=" + values.get(i)
                            + " target=" + targetValues.get(i));
                }
            }
        }
        for (String key : targetRows.keySet()) {
            if (!baseRows.containsKey(key)) {
                addDiff(mismatches, logicalTable + "[" + key + "] 基准库缺行");
            }
        }
    }

    private static Map<String, List<String>> readRows(Connection connection, DatabaseVendor vendor,
                                                      String actualTable, List<String> primaryKey,
                                                      List<String> valueColumns) throws SQLException {
        List<String> selectColumns = new ArrayList<>(valueColumns);
        for (String key : primaryKey) {
            if (selectColumns.stream().noneMatch(column -> column.equalsIgnoreCase(key))) {
                selectColumns.add(key);
            }
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        appendColumns(sql, vendor, selectColumns);
        sql.append(" FROM ").append(vendor.wrapIdentifier(actualTable)).append(" ORDER BY ");
        appendColumns(sql, vendor, primaryKey);

        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.toString());
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                indexes.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
            }
            List<Integer> keyIndexes = primaryKey.stream()
                    .map(key -> requireIndex(indexes, key))
                    .toList();
            List<Integer> valueIndexes = valueColumns.stream()
                    .map(column -> requireIndex(indexes, column))
                    .toList();
            while (rs.next()) {
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < keyIndexes.size(); i++) {
                    if (i > 0) {
                        key.append('\u001f');
                    }
                    key.append(normalize(rs.getObject(keyIndexes.get(i))));
                }
                List<String> values = new ArrayList<>(valueIndexes.size());
                for (int index : valueIndexes) {
                    values.add(normalize(rs.getObject(index)));
                }
                rows.put(key.toString(), values);
            }
        }
        return rows;
    }

    private static int requireIndex(Map<String, Integer> indexes, String column) {
        Integer index = indexes.get(column.toLowerCase(Locale.ROOT));
        if (index == null) {
            throw new IllegalStateException("结果集缺少列: " + column);
        }
        return index;
    }

    private static String normalize(Object value) throws SQLException {
        if (value == null) {
            return "\u0000";
        }
        if (value instanceof String text) {
            return text.isEmpty() ? "\u0000" : text;
        }
        if (value instanceof Clob clob) {
            long length = clob.length();
            if (length > Integer.MAX_VALUE) {
                throw new IllegalStateException("CLOB 过大，无法比对");
            }
            return normalize(clob.getSubString(1, (int) length));
        }
        if (value instanceof Blob blob) {
            return HexFormat.of().formatHex(blob.getBytes(1, (int) blob.length()));
        }
        if (value instanceof byte[] bytes) {
            return HexFormat.of().formatHex(bytes);
        }
        if (value instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Timestamp || value instanceof java.sql.Date
                || value instanceof java.sql.Time || value instanceof TemporalAccessor) {
            return "\u0001TS";
        }
        return String.valueOf(value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
