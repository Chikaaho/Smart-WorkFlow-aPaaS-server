package com.sw.ck.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Arrays;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G7 真实旧库升级演练（H2）：从受支持的旧基线（V58 = v0.0.2 OA 通知语义）保留既有数据，
 * 升级到 I6 终点 V90；升级前后按同一 ID 回读，语义可解释。
 * 不通过重建/删数据/替换对象证明。
 */
class I6G7UpgradeDrillH2Test {

    private static final String DB = "jdbc:h2:file:./target/i6-g7-upgrade-db;MODE=PostgreSQL;AUTO_SERVER=TRUE";
    private static final String USER = "sa";
    private static final String PASSWORD = "";
    private static final String HEADER =
            "id BIGINT PRIMARY KEY, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
            + "deleted SMALLINT NOT NULL DEFAULT 0, tenant_id BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0";

    @Test
    @DisplayName("G7 从 V58 基线的既有消息/模板/尝试升级到 V92：同一 ID 回读一致")
    void upgradeFromV58PreservesLegacyObjects() throws Exception {
        Files.deleteIfExists(new java.io.File("./target/i6-g7-upgrade-db.mv.db").toPath());

        // ---------- 1. 升级到 V58（旧基线） ----------
        String[] allLocations = Arrays.stream("classpath:db/migration/h2,classpath:db/migration/bpm/h2,classpath:db/migration/notify/h2,classpath:db/migration/form/h2,classpath:db/migration/storage/h2,classpath:db/migration/job/h2,classpath:db/migration/agent/h2,classpath:db/migration/iot/h2,classpath:db/migration/openapi/h2,classpath:db/migration/system/h2".split(","))
                .toArray(String[]::new);
        Flyway base = Flyway.configure().dataSource(DB, USER, PASSWORD)
                .locations(allLocations)
                .baselineOnMigrate(true)
                .target(MigrationVersion.fromVersion("58"))
                .load();
        var r58 = base.migrate();
        assertTrue(r58.success, "到 V58 的迁移应成功");

        // ---------- 2. 写入代表性旧数据（真实 INSERT，非 schema 构造） ----------
        try (Connection conn = DriverManager.getConnection(DB, USER, PASSWORD); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO sw_notify_message (id, recipient_id, title, content, biz_type, biz_id, is_read, deleted, tenant_id, version, create_time, update_time) "
                    + "SELECT 1, 7, '旧标题V1', '旧正文V1', 'SYSTEM', 'legacy-biz-1', FALSE, 0, 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP");
            st.execute("INSERT INTO sw_notify_template (id, template_code, name, title_template, content_template, enabled, deleted, tenant_id, version) "
                    + "VALUES (500, 'legacy_tpl', '旧模板', '旧主题', '旧正文', 1, 0, 100, 0)");
            st.execute("INSERT INTO sw_notify_send_attempt (id, message_id, attempt_no, channel, status, deleted, tenant_id, version) "
                    + "VALUES (1, 1, 1, 'IN_APP', 'SUCCESS', 0, 100, 0)");
        }

        // ---------- 3. 继续升级到 V92（I6 终点） ----------
        Flyway chain = Flyway.configure().dataSource(DB, USER, PASSWORD)
                .locations(allLocations)
                .baselineOnMigrate(true)
                .target(org.flywaydb.core.api.MigrationVersion.LATEST)
                .load();
        var r90 = chain.migrate();
        assertTrue(r90.success, "基线 → V92 升级应成功");
        assertEquals("92", chain.info().current().getVersion().getVersion(),
                "升级终点须为 V90");

        // ---------- 4. 同一 ID 回读：行数与语义一致，增量列可解释 ----------
        try (Connection conn = DriverManager.getConnection(DB, USER, PASSWORD)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT title, content, biz_id, event_type, occurrence_no, retry_count, channel FROM sw_notify_message WHERE id = 1 AND deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "历史消息按原 ID 保留");
                assertEquals("旧标题V1", rs.getString(1));
                assertEquals("旧正文V1", rs.getString(2));
                assertEquals("legacy-biz-1", rs.getString(3));
                assertEquals("SYSTEM", rs.getString(4));
                assertEquals(1L, rs.getLong("occurrence_no"));
                assertEquals(0, rs.getInt("retry_count"));
                assertEquals("IN_APP", rs.getString("channel"));
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT template_code, title_template, event_type, channel FROM sw_notify_template WHERE id = 500 AND deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "旧模板按原 ID 保留");
                assertEquals("legacy_tpl", rs.getString(1));
                assertEquals("旧主题", rs.getString(2));
                assertEquals("SYSTEM", rs.getString(3));
                assertEquals("IN_APP", rs.getString(4));
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT attempt_no, status, failure_class, started_at FROM sw_notify_send_attempt WHERE id = 1 AND deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "历史尝试流水按原 ID 保留");
                assertEquals(1, rs.getInt(1));
                assertEquals("SUCCESS", rs.getString(2));
                assertEquals(null, rs.getString(3));
                // started_at 增量列可为空（历史行不回填时间），语义可解释
                assertTrue(rs.getDate(4) == null);
            }
        }
    }
}
