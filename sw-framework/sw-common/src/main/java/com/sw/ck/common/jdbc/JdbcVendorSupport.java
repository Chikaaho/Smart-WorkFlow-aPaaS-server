package com.sw.ck.common.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JdbcTemplate 侧的方言辅助：裸 SQL 不经过 MyBatis 拦截器，调用方用它取方言、
 * 生成尾部 LIMIT/OFFSET。按 JDBC URL 缓存识别结果，避免每次查询读元数据。
 */
public final class JdbcVendorSupport {

    private static final Map<String, DatabaseVendor> CACHE = new ConcurrentHashMap<>();

    private JdbcVendorSupport() {
    }

    public static DatabaseVendor vendor(JdbcTemplate jdbcTemplate) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("JdbcTemplate 未绑定 DataSource，无法确定数据库方言");
        }
        try (Connection connection = dataSource.getConnection()) {
            return vendor(connection);
        } catch (Exception e) {
            throw new IllegalStateException("无法确定数据库方言", e);
        }
    }

    public static DatabaseVendor vendor(Connection connection) {
        try {
            String url = connection.getMetaData().getURL();
            if (url != null) {
                DatabaseVendor cached = CACHE.get(url);
                if (cached != null) {
                    return cached;
                }
                DatabaseVendor detected = DatabaseVendor.detect(connection);
                CACHE.put(url, detected);
                return detected;
            }
            return DatabaseVendor.detect(connection);
        } catch (Exception e) {
            throw new IllegalStateException("无法确定数据库方言", e);
        }
    }

    public static String applyLimit(JdbcTemplate jdbcTemplate, String sql, long limit) {
        return vendor(jdbcTemplate).applyLimit(sql, limit);
    }

    public static String applyLimit(JdbcTemplate jdbcTemplate, String sql, long limit, long offset) {
        return vendor(jdbcTemplate).applyLimit(sql, limit, offset);
    }
}
