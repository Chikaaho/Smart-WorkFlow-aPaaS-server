package com.sw.ck.bootstrap.config;

import com.sw.ck.bootstrap.migration.SwVendorMigrationRunner;
import com.sw.ck.common.jdbc.DatabaseVendor;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Flyway 配置：通过 {@link FlywayMigrationStrategy} 串联主迁移与 prod-update 补丁迁移。
 * <p>
 * 主迁移（db/migration/{vendor}）先执行，prod-update（db/prod-update/{vendor}）后执行。
 * prod-update 使用独立历史表 flyway_schema_history_prod，仅允许 DML 补丁脚本。
 * 任一迁移失败均阻断启动。
 * </p>
 */
@Configuration
public class FlywayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfiguration.class);

    /**
     * 自定义 Flyway 迁移策略，保证主迁移先跑、prod-update 补丁后跑。
     *
     * @param dataSource 主数据源
     * @return FlywayMigrationStrategy
     */
    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return flyway -> {
            DatabaseVendor vendor = detectVendor(dataSource);
            // Flyway 没有达梦/Kingbase/openGauss 模块，改由项目内 Runner 执行。
            if (vendor == DatabaseVendor.DM
                    || vendor == DatabaseVendor.KINGBASE
                    || vendor == DatabaseVendor.OPENGAUSS) {
                log.info("Executing SwVendorMigrationRunner (vendor = {}, locations = db/migration/{})",
                        vendor.displayName(), vendor.name().toLowerCase(java.util.Locale.ROOT));
                SwVendorMigrationRunner.migrate(dataSource, vendor);
                log.info("SwVendorMigrationRunner completed (vendor = {})", vendor.displayName());
                return;
            }

            // 1. 执行主迁移
            log.info("Executing primary Flyway migration (locations = db/migration/{vendor})");
            flyway.migrate();
            log.info("Primary Flyway migration completed");

            // 2. 执行 prod-update 迁移
            String dir = vendor.prodUpdateDir();
            log.info("Executing prod-update Flyway migration (vendor = {}, locations = db/prod-update/{})", dir, dir);

            Flyway prodFlyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/prod-update/" + dir)
                    .table("flyway_schema_history_prod")
                    .baselineOnMigrate(true)
                    .validateOnMigrate(true)
                    .outOfOrder(false)
                    .load();

            prodFlyway.migrate();
            log.info("Prod-update Flyway migration completed (vendor = {})", dir);
        };
    }

    private DatabaseVendor detectVendor(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseVendor vendor = DatabaseVendor.detect(conn);
            log.info("Detected database vendor: {}", vendor.displayName());
            return vendor;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to detect database vendor from DataSource", e);
        }
    }
}
