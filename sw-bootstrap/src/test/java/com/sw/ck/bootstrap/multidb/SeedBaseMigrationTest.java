package com.sw.ck.bootstrap.multidb;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 为 {@link SeedComparisonTest} 准备 PostgreSQL 基准库：对 {@code seed.base.url} 执行完整 PG 迁移。
 * 未配置系统属性时跳过；仅用于 B2 各库种子比对，不参与常规门禁数据准备。
 */
@DisplayName("种子比对基准库（PostgreSQL）迁移")
class SeedBaseMigrationTest {

    @Test
    @DisplayName("对 seed.base.url 执行 30 条 PG 迁移与种子")
    void migrateBaseDatabase() {
        String url = System.getProperty("seed.base.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置 seed.base.url，跳过基准库迁移");
        String user = System.getProperty("seed.base.user", "");
        String password = System.getProperty("seed.base.password", "");
        var result = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration/postgresql")
                .load()
                .migrate();
        assertEquals(31, result.migrationsExecuted, "PG 基准库应执行 31 条迁移");
    }
}
