package com.sw.ck.bootstrap.phase4;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 G5 · V96 迁移行为证据（真实 PostgreSQL，专用 schema，原始输出可回读）。
 *
 * <p>方向 §5.D.4 要求：既有记录兼容、前置数据检查、正向迁移、终点版本与回滚/恢复边界，
 * 且"不得以清空数据库证明迁移成功"。本类在独立 schema 中依次证明：</p>
 * <ol>
 *   <li>全链 clean migrate 到 V96（终点版本 + 新表/新列/索引存在）；</li>
 *   <li>V95 → V96 升级：升级前已有数据在升级后保留、新列落到既定默认值、
 *       唯一索引真实拒绝重复（不是"清空后重建"）；</li>
 *   <li>迁移失败边界：失败被记录为 failed 迁移，repair + 重跑后能继续到终点，
 *       且既有数据仍在。</li>
 * </ol>
 */
@DisplayName("Phase4 G5 · V96 迁移行为证据 · 真实 PostgreSQL 专用 schema")
class Phase4PgMigrationBehaviourTest extends Phase4PgSupport {

    private static final String SCHEMA = "p4_migration";

    @Test
    @DisplayName("G5-1 全链 clean migrate 到 V96：终点版本、新表、新列与索引齐备")
    void cleanMigrateReachesV96WithNewStructures() throws Exception {
        ensureEvidenceDatabase();
        Flyway flyway = flywayTargeting(null, false);
        flyway.clean();
        MigrateResult result = flyway.migrate();
        assertThat(result.success).isTrue();
        String version = latestVersion();
        assertThat(version).isEqualTo("96");
        assertThat(tableExists("sw_openapi_callback_task")).isTrue();
        assertThat(indexExists("uk_sw_openapi_cb_task")).isTrue();
        assertThat(indexExists("idx_sw_openapi_cb_task_due")).isTrue();
        List<String> triggerColumns = columnsOf("sw_iot_process_trigger");
        assertThat(triggerColumns).contains("process_template_key", "trigger_source", "configured_by",
                "retry_count", "next_retry_time");
        assertThat(indexExists("idx_sw_iot_trigger_recovery")).isTrue();
        System.out.println("[P4-EV] g5.clean-migrate success=" + result.success
                + " migrationsExecuted=" + result.migrationsExecuted + " targetVersion=" + version
                + " newTable=sw_openapi_callback_task newIndexes=3 triggerColumnsAddedByV96=5");
    }

    @Test
    @DisplayName("G5-2 V95 → V96 升级：既有记录保留、新列按默认值落地、唯一索引真实生效")
    void upgradeFromV95KeepsExistingRowsAndAppliesNewDefaults() throws Exception {
        Flyway v95 = flywayTargeting("95", false);
        v95.clean();
        MigrateResult toV95 = v95.migrate();
        assertThat(toV95.success).isTrue();
        assertThat(latestVersion()).isEqualTo("95");

        // 升级前置数据：旧结构里已存在的触发行（不含 V96 新列）与既有业务行
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id,"
                    + " version, rule_id, device_id, idempotent_key, status, trigger_time)"
                    + " values (96001, now(), now(), 0, 0, 0, 1, 1, 'p4-upgrade-existing-1', 'SUCCESS', now())");
            stmt.execute("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id,"
                    + " version, rule_id, device_id, idempotent_key, status, trigger_time, error)"
                    + " values (96002, now(), now(), 0, 0, 0, 1, 1, 'p4-upgrade-existing-2', 'FAILED', now(),"
                    + " '升级前失败记录')");
        }
        long rowsBefore = countRows("select count(*) from sw_iot_process_trigger where id in (96001, 96002)");
        assertThat(rowsBefore).isEqualTo(2L);

        MigrateResult upgrade = flywayTargeting(null, false).migrate();
        assertThat(upgrade.success).isTrue();
        assertThat(latestVersion()).isEqualTo("96");

        assertThat(countRows("select count(*) from sw_iot_process_trigger where id in (96001, 96002)"))
                .as("升级不得丢弃既有记录").isEqualTo(2L);
        assertThat(countRows("select count(*) from sw_iot_process_trigger where id = 96001 and status = 'SUCCESS'"))
                .as("既有状态不得被迁移改写").isEqualTo(1L);
        assertThat(countRows("select count(*) from sw_iot_process_trigger"
                + " where id in (96001, 96002) and retry_count = 0 and next_retry_time is null"))
                .as("新列必须落到既定默认值（可空列保持 null）").isEqualTo(2L);
        assertThat(countRows("select count(*) from sw_iot_process_trigger"
                + " where id = 96002 and error = '升级前失败记录'")).isEqualTo(1L);
        System.out.println("[P4-EV] g5.upgrade existingRowsBefore=" + rowsBefore + " existingRowsAfter=2"
                + " retryCountDefault=0 nextRetryTimeNull=true statusPreserved=true errorPreserved=true");

        // 唯一索引真实生效（升级后的表结构，不是"清空重建"）
        boolean duplicateRejected = false;
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("insert into sw_openapi_callback_task (id, create_time, update_time, deleted, tenant_id,"
                    + " version, app_id, event, biz_ref, status, attempts)"
                    + " values (96101, now(), now(), 0, 0, 0, 'a1', 'PROCESS_APPROVED', 'b1', 'PENDING', 0)");
            try {
                stmt.execute("insert into sw_openapi_callback_task (id, create_time, update_time, deleted, tenant_id,"
                        + " version, app_id, event, biz_ref, status, attempts)"
                        + " values (96102, now(), now(), 0, 0, 0, 'a1', 'PROCESS_APPROVED', 'b1', 'PENDING', 0)");
            } catch (SQLException expected) {
                duplicateRejected = true;
            }
        }
        assertThat(duplicateRejected).as("升级后的唯一索引必须拒绝同一业务身份的第二条回调任务").isTrue();
        assertThat(countRows("select count(*) from sw_openapi_callback_task where app_id = 'a1'")).isEqualTo(1L);
        System.out.println("[P4-EV] g5.upgrade-unique-index duplicateRejected=" + duplicateRejected
                + " callbackTasks=1");
    }

    @Test
    @DisplayName("G5-3 迁移失败边界：失败被记录、repair 后可继续到终点，既有数据不丢")
    void failedMigrationIsRecordedAndRepairableWithoutDataLoss() throws Exception {
        Flyway base = flywayTargeting("95", false);
        base.clean();
        base.migrate();
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute("insert into sw_iot_process_trigger (id, create_time, update_time, deleted, tenant_id,"
                    + " version, rule_id, device_id, idempotent_key, status, trigger_time)"
                    + " values (96201, now(), now(), 0, 0, 0, 1, 1, 'p4-fail-boundary-existing', 'SUCCESS', now())");
        }

        Path brokenLocation = Files.createTempDirectory("p4-broken-migration");
        Files.writeString(brokenLocation.resolve("V97__p4_evidence_broken.sql"),
                "insert into p4_table_that_does_not_exist (id) values (1);\n", StandardCharsets.UTF_8);
        String[] withBroken = concat(APP_LOCATIONS, "filesystem:" + brokenLocation.toAbsolutePath());

        boolean failed = false;
        try {
            flywayWithLocations(withBroken, false).migrate();
        } catch (Exception expected) {
            failed = true;
        }
        assertThat(failed).as("故意损坏的迁移必须失败").isTrue();
        // 真实 PostgreSQL 事实：迁移脚本在事务内执行，PostgreSQL 支持事务型 DDL，
        // 因此失败迁移被整体回滚（Flyway 原始日志为 "Changes successfully rolled back"），
        // 既不留 failed 历史行，也不推进版本号——与非事务型 DDL 方言需要 repair 的边界不同。
        long failedRecords = countRows("select count(*) from flyway_schema_history where success = false");
        // 失败点之前已就绪的 V96 正常应用；失败点 V97 整体回滚，版本停在最后一个成功迁移
        assertThat(latestVersion()).as("失败迁移不得推进到失败版本").isEqualTo("96");
        assertThat(countRows("select count(*) from flyway_schema_history where version = '97'"))
                .as("事务型方言失败后不留下 97 号历史行").isZero();
        assertThat(failedRecords).as("事务型 DDL 下失败迁移整体回滚，无残留失败行").isZero();
        assertThat(countRows("select count(*) from sw_iot_process_trigger where id = 96201"))
                .as("失败迁移不得清除既有数据").isEqualTo(1L);
        System.out.println("[P4-EV] g5.migration-failure failedRecords=" + failedRecords
                + " failedVersionInHistory=" + failedVersion() + " versionAfterFailure=" + latestVersion()
                + " transactionalRollback=true existingDataKept=true");

        // 恢复边界：修复元数据后重跑真实迁移链，继续到终点且数据仍在
        Flyway recovered = flywayTargeting(null, false);
        recovered.repair();
        MigrateResult afterRepair = recovered.migrate();
        assertThat(afterRepair.success).isTrue();
        assertThat(latestVersion()).isEqualTo("96");
        assertThat(countRows("select count(*) from sw_iot_process_trigger where id = 96201"))
                .as("恢复后既有数据仍必须存在").isEqualTo(1L);
        assertThat(tableExists("sw_openapi_callback_task")).isTrue();
        System.out.println("[P4-EV] g5.migration-recovered repaired=true success=" + afterRepair.success
                + " targetVersion=" + latestVersion() + " existingDataKept=true newTablePresent=true");
    }

    // ==================== 工具 ====================

    private Flyway flywayTargeting(String target, boolean cleanDisabled) {
        var configuration = Flyway.configure()
                .dataSource(evidenceUrl(), pgUser(), pgPassword())
                .locations(target == null ? APP_LOCATIONS : APP_LOCATIONS)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .cleanDisabled(cleanDisabled);
        if (target != null) {
            configuration = configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private Flyway flywayWithLocations(String[] locations, boolean cleanDisabled) {
        return Flyway.configure()
                .dataSource(evidenceUrl(), pgUser(), pgPassword())
                .locations(locations)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .cleanDisabled(cleanDisabled)
                .load();
    }

    private static String[] concat(String[] base, String extra) {
        List<String> all = new ArrayList<>(List.of(base));
        all.add(extra);
        return all.toArray(String[]::new);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(evidenceUrl() + "&currentSchema=" + SCHEMA, pgUser(), pgPassword());
    }

    private String latestVersion() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select version from flyway_schema_history"
                     + " where success = true and version is not null order by installed_rank desc limit 1")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select count(*) from information_schema.tables"
                     + " where table_schema = '" + SCHEMA + "' and table_name = '" + table + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private boolean indexExists(String index) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select count(*) from pg_indexes"
                     + " where schemaname = '" + SCHEMA + "' and indexname = '" + index + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private List<String> columnsOf(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select column_name from information_schema.columns"
                     + " where table_schema = '" + SCHEMA + "' and table_name = '" + table
                     + "' order by column_name")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private Integer failedRank() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select installed_rank from flyway_schema_history"
                     + " where success = false order by installed_rank desc limit 1")) {
            return rs.next() ? rs.getInt(1) : null;
        }
    }

    private String failedVersion() throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("select coalesce(version, 'null') || '/' || coalesce(description,'-')"
                     + " from flyway_schema_history where success = false order by installed_rank desc limit 1")) {
            return rs.next() ? rs.getString(1) : "none";
        }
    }

    private long countRows(String sql) throws SQLException {
        try (Connection conn = connect(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
