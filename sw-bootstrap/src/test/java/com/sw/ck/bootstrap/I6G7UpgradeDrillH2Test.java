package com.sw.ck.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I6 G7 通知语义演练（H2，基线形态）。
 * <p>
 * 0.1.3 种子合并后历史升级路径退役（0.1.3 起仅支持全新建库），本测试由
 * 「从 V58 旧基线升级保留既有数据」改为「V0.1.0 基线全新建库后通知语义可用」：
 * 全新库执行基线（V0.1.0 + R__ 可重复对账）后，通知消息/模板/发送尝试三表的
 * 关键列语义按真实 INSERT/SELECT 验证（同一 ID 回读、增量列可解释）。
 * 不通过重建/删数据/替换对象证明。
 * </p>
 */
class I6G7UpgradeDrillH2Test {

    private static final String DB = "jdbc:h2:file:./target/i6-g7-baseline-db;MODE=PostgreSQL;AUTO_SERVER=TRUE";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    @Test
    @DisplayName("G7 基线全新建库：通知消息/模板/尝试关键列语义按同一 ID 回读一致")
    void baselineDatabaseKeepsNotificationSemantics() throws Exception {
        Files.deleteIfExists(new java.io.File("./target/i6-g7-baseline-db.mv.db").toPath());

        // ---------- 1. 基线全新建库 ----------
        String[] allLocations = "classpath:db/migration/h2,classpath:db/migration/bpm/h2,classpath:db/migration/notify/h2,classpath:db/migration/form/h2,classpath:db/migration/storage/h2,classpath:db/migration/job/h2,classpath:db/migration/agent/h2,classpath:db/migration/iot/h2,classpath:db/migration/openapi/h2,classpath:db/migration/system/h2".split(",");
        var flyway = Flyway.configure().dataSource(DB, USER, PASSWORD)
                .locations(allLocations)
                .load();
        var result = flyway.migrate();
        assertTrue(result.success, "基线迁移应成功");
        assertEquals(10, result.migrationsExecuted, "全新库应执行 10 条（V0.1.0—V0.1.4 五个版本 + 5 个 R__，P62 链尾机械修正）");
        assertEquals("0.1.4", flyway.info().current().getVersion().getVersion(),
                "终点当前版本应为 0.1.4 链尾");

        // ---------- 2. 写入代表性数据（真实 INSERT，非 schema 构造） ----------
        try (Connection conn = DriverManager.getConnection(DB, USER, PASSWORD); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO sw_notify_message (id, recipient_id, title, content, biz_type, biz_id, is_read, deleted, tenant_id, version, create_time, update_time) "
                    + "SELECT 1, 7, '基线标题V1', '基线正文V1', 'SYSTEM', 'biz-1', FALSE, 0, 100, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP");
            st.execute("INSERT INTO sw_notify_template (id, template_code, name, title_template, content_template, enabled, deleted, tenant_id, version) "
                    + "VALUES (500, 'baseline_tpl', '基线模板', '基线主题', '基线正文', 1, 0, 100, 0)");
            st.execute("INSERT INTO sw_notify_send_attempt (id, message_id, attempt_no, channel, status, deleted, tenant_id, version) "
                    + "VALUES (1, 1, 1, 'IN_APP', 'SUCCESS', 0, 100, 0)");
        }

        // ---------- 3. 同一 ID 回读：行数与语义一致，增量列可解释 ----------
        try (Connection conn = DriverManager.getConnection(DB, USER, PASSWORD)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT title, content, biz_id, event_type, occurrence_no, retry_count, channel FROM sw_notify_message WHERE id = 1 AND deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "消息按原 ID 保留");
                assertEquals("基线标题V1", rs.getString(1));
                assertEquals("基线正文V1", rs.getString(2));
                assertEquals("biz-1", rs.getString(3));
                assertEquals("SYSTEM", rs.getString(4));
                assertEquals(1L, rs.getLong("occurrence_no"));
                assertEquals(0, rs.getInt("retry_count"));
                assertEquals("IN_APP", rs.getString("channel"));
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT template_code, title_template, event_type, channel FROM sw_notify_template WHERE id = 500 AND deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "模板按原 ID 保留");
                assertEquals("baseline_tpl", rs.getString(1));
                assertEquals("基线主题", rs.getString(2));
                assertEquals("SYSTEM", rs.getString(3));
                assertEquals("IN_APP", rs.getString(4));
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT attempt_no, status, failure_class, started_at FROM sw_notify_send_attempt WHERE id = 1 AND deleted = 0");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "尝试流水按原 ID 保留");
                assertEquals(1, rs.getInt(1));
                assertEquals("SUCCESS", rs.getString(2));
                assertEquals(null, rs.getString(3));
                // started_at 增量列可为空（不强制回填时间），语义可解释
                assertTrue(rs.getDate(4) == null);
            }
        }
    }
}
