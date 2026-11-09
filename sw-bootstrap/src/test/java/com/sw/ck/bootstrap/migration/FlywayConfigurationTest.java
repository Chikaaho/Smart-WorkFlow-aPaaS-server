package com.sw.ck.bootstrap.migration;

import com.sw.ck.bootstrap.compat.DatabaseCompatibilityGuard;
import com.sw.ck.bootstrap.config.FlywayConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * R1：兼容性检查必须发生在迁移策略入口，检查失败时任何迁移都不得执行。
 */
class FlywayConfigurationTest {

    @Test
    @DisplayName("兼容性检查失败：Flyway 与 DataSource 均不被调用，迁移零执行")
    void compatibilityFailureBlocksAllMigrations() {
        DataSource dataSource = mock(DataSource.class);
        DatabaseCompatibilityGuard guard = mock(DatabaseCompatibilityGuard.class);
        doThrow(new IllegalStateException("字符集/排序规则不满足"))
                .when(guard).check();
        Flyway flyway = mock(Flyway.class);

        FlywayMigrationStrategy strategy =
                new FlywayConfiguration().flywayMigrationStrategy(dataSource, guard);

        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));
        verify(guard).check();
        verifyNoInteractions(flyway, dataSource);
    }
}
