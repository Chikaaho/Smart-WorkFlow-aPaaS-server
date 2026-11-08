package com.sw.ck.bootstrap.multidb;

import com.sw.ck.common.jdbc.DatabaseVendor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 种子两层比对：所有表行数 + 关键表关键列逐值摘要。
 * <p>
 * 通过系统属性指定基准库和目标库：
 * {@code -Dseed.base.url=... -Dseed.base.user=... -Dseed.base.password=...}
 * 与 {@code seed.target.*}。未配置时跳过，供阶段 B/C 每库执行。
 * 基准库由 PostgreSQL 空库执行完整迁移后得到；目标库执行对应方言迁移。
 * 空字符串与 NULL 在摘要中统一归一到同一个哨兵，避免 Oracle 把 '' 当 NULL 造成假差异。
 * </p>
 */
class SeedComparisonTest {

    private static final Map<String, List<String>> KEY_COLUMNS = new HashMap<>();

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
    @DisplayName("种子两层比对：行数 + 关键列摘要")
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
                    mismatches.add(table + " 行数 " + baseCount + " != " + targetCount);
                    continue;
                }
                List<String> keys = KEY_COLUMNS.get(table);
                if (keys == null || keys.isEmpty()) {
                    System.out.printf("COUNT OK  %-40s %d%n", table, baseCount);
                    continue;
                }
                String baseHash = hash(base, table, keys);
                String targetHash = hash(target, table, keys);
                if (!baseHash.equals(targetHash)) {
                    mismatches.add(table + " 关键列摘要不一致 base=" + baseHash + " target=" + targetHash);
                } else {
                    System.out.printf("COUNT+KEY OK %-36s %d %s%n", table, baseCount, baseHash.substring(0, 12));
                }
            }
            assertThat(mismatches).as("种子比对差异").isEmpty();
        }
    }

    private static Connection connect(String prefix, String url) throws SQLException {
        String user = System.getProperty(prefix + "user", "");
        String password = System.getProperty(prefix + "password", "");
        return DriverManager.getConnection(url, user, password);
    }

    private static List<String> seedTables(Connection connection) throws SQLException {
        Set<String> tables = new TreeSet<>();
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if ((name.startsWith("sys_") || name.startsWith("sw_"))
                        && !name.equals("sw_schema_history")
                        && !name.equals("sw_schema_history_lock")) {
                    tables.add(name);
                }
            }
        }
        return new ArrayList<>(tables);
    }

    private static long count(Connection connection, String table) throws SQLException {
        DatabaseVendor vendor = DatabaseVendor.detect(connection);
        String sql = "SELECT COUNT(*) FROM " + vendor.wrapIdentifier(table);
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String hash(Connection connection, String table, List<String> keys) throws SQLException {
        DatabaseVendor vendor = DatabaseVendor.detect(connection);
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(vendor.wrapIdentifier(keys.get(i)));
        }
        sql.append(" FROM ").append(vendor.wrapIdentifier(table)).append(" ORDER BY ");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(vendor.wrapIdentifier(keys.get(i)));
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private static String normalize(Object value) {
        if (value == null) {
            return "\u0000";
        }
        if (value instanceof String text) {
            return text.isEmpty() ? "\u0000" : text;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return HexFormat.of().formatHex(bytes);
        }
        return String.valueOf(value);
    }

}
