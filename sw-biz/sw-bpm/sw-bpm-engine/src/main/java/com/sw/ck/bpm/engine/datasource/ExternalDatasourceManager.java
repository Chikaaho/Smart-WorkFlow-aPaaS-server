package com.sw.ck.bpm.engine.datasource;

import com.sw.ck.bpm.engine.config.ExternalDatasourceProperties;
import com.sw.ck.bpm.engine.entity.ExternalDatasource;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.log4j.Log4j2;
/**
 * 外部数据源连接池管理器。
 * <p>
 * 每个外部数据源对应一个独立的 {@link HikariDataSource}，按 id 缓存，懒加载。
 * 连接池独立于主库 dynamic-datasource / SqlSessionFactory / MP 拦截器，
 * 形成完全隔离的 JDBC 通道。
 * </p>
 */
@Log4j2
public class ExternalDatasourceManager {

    private final Map<Long, HikariDataSource> poolCache = new ConcurrentHashMap<>();
    private final AesGcmCipher cipher;
    private final ExternalDatasourceProperties.Pool poolConfig;

    public ExternalDatasourceManager(AesGcmCipher cipher, ExternalDatasourceProperties properties) {
        this.cipher = cipher;
        this.poolConfig = properties.getPool();
    }

    /**
     * 获取或创建外部数据源的连接池。
     *
     * @param entity 外部数据源实体（须已携带 passwordCipher）
     * @return HikariDataSource
     */
    public HikariDataSource getOrCreatePool(ExternalDatasource entity) {
        return poolCache.computeIfAbsent(entity.getId(), id -> createPool(entity));
    }

    /**
     * 测试外部数据源连接是否可用。
     *
     * @param entity 外部数据源实体
     * @throws SQLException 连接失败时抛出
     */
    public void testConnection(ExternalDatasource entity) throws SQLException {
        HikariConfig config = buildConfig(entity);
        // 测试连接用独立的最小池
        config.setMinimumIdle(0);
        config.setMaximumPoolSize(1);
        try (HikariDataSource ds = new HikariDataSource(config)) {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.executeQuery("SELECT 1");
                log.info("External datasource connection test SUCCESS: name={}, type={}", entity.getName(), entity.getType());
            }
        }
    }

    /**
     * 驱逐并关闭指定数据源的连接池。
     */
    public void evictPool(Long datasourceId) {
        HikariDataSource removed = poolCache.remove(datasourceId);
        if (removed != null) {
            log.info("Evicting connection pool for external datasource id={}", datasourceId);
            removed.close();
        }
    }

    /**
     * 关闭所有连接池（应用关闭时调用）。
     */
    public void shutdown() {
        log.info("Shutting down all external datasource connection pools (count={})", poolCache.size());
        poolCache.values().forEach(HikariDataSource::close);
        poolCache.clear();
    }

    private HikariDataSource createPool(ExternalDatasource entity) {
        HikariConfig config = buildConfig(entity);
        log.info("Creating connection pool for external datasource: name={}, type={}, url={}",
                entity.getName(), entity.getType(), maskUrl(entity.getJdbcUrl()));
        return new HikariDataSource(config);
    }

    private HikariConfig buildConfig(ExternalDatasource entity) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(entity.getJdbcUrl());
        config.setDriverClassName(entity.getDriverClass());
        config.setUsername(entity.getUsername());
        config.setPassword(cipher.decrypt(entity.getPasswordCipher()));
        config.setMaximumPoolSize(poolConfig.getMaxPoolSize());
        config.setMinimumIdle(poolConfig.getMinIdle());
        config.setIdleTimeout(poolConfig.getIdleTimeout());
        config.setMaxLifetime(poolConfig.getMaxLifetime());
        config.setConnectionTimeout(poolConfig.getConnectionTimeout());
        config.setReadOnly(entity.getReadOnly() != null && entity.getReadOnly() == 1);
        // 连接池名称便于调试
        config.setPoolName("HikariPool-ext-" + entity.getId());
        return config;
    }

    /**
     * 脱敏 JDBC URL 中的敏感信息（如内网 IP 端口后的路径参数）。
     */
    static String maskUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return "null";
        }
        // 仅保留前缀 + host:port，隐藏参数和路径
        int paramIndex = jdbcUrl.indexOf('?');
        if (paramIndex > 0) {
            return jdbcUrl.substring(0, paramIndex);
        }
        return jdbcUrl;
    }
}
