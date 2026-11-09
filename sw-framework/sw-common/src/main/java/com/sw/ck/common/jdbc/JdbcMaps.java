package com.sw.ck.common.jdbc;

import org.springframework.util.LinkedCaseInsensitiveMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 查询结果 Map 的键大小写归一化。
 * <p>
 * PostgreSQL 返回小写列标签，Oracle/达梦/H2 可能返回大写；消费端统一按小写键读取。
 * 只改键大小写，不改变值。
 * </p>
 */
public final class JdbcMaps {

    private JdbcMaps() {
    }

    /**
     * 大小写不敏感的 Map：保留数据库返回的原始键（Oracle 可能是大写），
     * 消费端用任意大小写读取。适合内部取数，不适合直接序列化为对外 JSON。
     */
    public static Map<String, Object> caseInsensitiveKeys(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedCaseInsensitiveMap<>(row.size());
        normalized.putAll(row);
        return normalized;
    }

    public static List<Map<String, Object>> caseInsensitiveKeys(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            normalized.add(caseInsensitiveKeys(row));
        }
        return normalized;
    }

    public static Map<String, Object> lowerCaseKeys(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(row.size());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String key = entry.getKey();
            normalized.put(key == null ? null : key.toLowerCase(Locale.ROOT), entry.getValue());
        }
        return normalized;
    }

    public static List<Map<String, Object>> lowerCaseKeys(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            normalized.add(lowerCaseKeys(row));
        }
        return normalized;
    }
}
