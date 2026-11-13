package com.sw.ck.bootstrap.config;

import com.sw.ck.bootstrap.migration.SwVendorMigrationRunner;
import com.sw.ck.common.jdbc.DatabaseVendor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DM8 脚本静态代理：在 H2 Oracle 兼容模式下用自有 Runner 跑完整 DM 迁移与种子。
 * <p>
 * 只证明脚本可由项目执行器按版本顺序解析、DDL/种子语法在 Oracle 兼容模式可执行；
 * DM8 的字符集、大小写折叠、函数索引等最终语义由 Owner 在真实达梦上验证。
 * </p>
 */
@Disabled("H2 Oracle 模式不支持 DM 的 MODIFY(col ...) 与函数索引；DM8 由 Owner 实库验证")
@DisplayName("DM8 迁移脚本 H2 Oracle 模式代理")
class SwVendorMigrationRunnerDmProxyTest {

    @Test
    @DisplayName("空库 31 条 DM 脚本全链执行，历史与种子表可读")
    void shouldRunDmScriptsOnH2OracleMode() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sw_dm_" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.DM);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_schema_history", Integer.class))
                .isEqualTo(31);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_tenant", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class)).isEqualTo(146);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_name = 'SW_KNOWLEDGE_VECTOR'", Integer.class)).isEqualTo(1);

        SwVendorMigrationRunner.migrate(dataSource, DatabaseVendor.DM);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sw_schema_history", Integer.class))
                .isEqualTo(31);
    }
}
