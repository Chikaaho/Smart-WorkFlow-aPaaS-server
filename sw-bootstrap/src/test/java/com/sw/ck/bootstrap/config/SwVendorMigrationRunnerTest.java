package com.sw.ck.bootstrap.config;

import com.sw.ck.bootstrap.migration.SwVendorMigrationRunner;

import com.sw.ck.common.jdbc.DatabaseVendor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自有迁移执行器的空库全链、重复启动与拒绝自动 baseline 测试（H2 代理）。
 */
class SwVendorMigrationRunnerTest {

    private static DataSource freshDataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:sw_migration_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test
    @DisplayName("空库执行全链；重复启动不重复执行")
    void shouldRunFullChainOnce() {
        DataSource dataSource = freshDataSource();
        SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.H2);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer history = jdbc.queryForObject("SELECT COUNT(*) FROM sw_schema_history", Integer.class);
        assertThat(history).isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'SW_KNOWLEDGE_VECTOR'",
                Integer.class)).isEqualTo(1);

        SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.H2);
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM sw_schema_history", Integer.class);
        assertThat(after).isEqualTo(history);
    }

    @Test
    @DisplayName("已有业务表但没有历史表时拒绝自动 baseline")
    void shouldRejectImplicitBaseline() {
        DataSource dataSource = freshDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sys_user (id BIGINT PRIMARY KEY)");
        assertThatThrownBy(() -> SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.H2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseline");
    }

    @Test
    @DisplayName("迁移锁被占用时拒绝并发执行")
    void shouldRejectConcurrentMigration() {
        DataSource dataSource = freshDataSource();
        SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.H2);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE sw_schema_history_lock SET locked_by = 'other-instance', "
                + "locked_at = CURRENT_TIMESTAMP WHERE id = 1");
        assertThatThrownBy(() -> SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.H2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("另一个实例");
    }
}
