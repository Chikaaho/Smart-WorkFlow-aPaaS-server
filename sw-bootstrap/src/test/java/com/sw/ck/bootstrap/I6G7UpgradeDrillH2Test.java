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
 * <p>
 * 另含 D022-03 升级演练：迁到 0.2.0 基线后写入旧 sw_agent_model_config 三行，
 * 再迁满 V0.2.2，验证 ollama→OPENAI_CHAT 且地址补 /v1、other 供应商停用、旧 id 原样。
 * </p>
 */
class I6G7UpgradeDrillH2Test {

    private static final String DB = "jdbc:h2:file:./target/i6-g7-baseline-db;MODE=PostgreSQL;AUTO_SERVER=TRUE";
    private static final String UPGRADE_DB = "jdbc:h2:file:./target/i6-g7-f1-upgrade-db;MODE=PostgreSQL;AUTO_SERVER=TRUE";
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
        assertEquals(24, result.migrationsExecuted,
                "全新库应执行 24 条（V0.2.0 基线种子 + V0.2.2/V0.2.3/V0.2.4/V0.2.5 + 19 个 R__）");
        assertEquals("0.2.5", flyway.info().current().getVersion().getVersion(),
                "终点当前版本应为最后一个 versioned 迁移（V0.2.5）");

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

    @Test
    @DisplayName("D022-03 升级演练：旧三行搬迁为供应商+模型，ollama 补 /v1、other 停用、旧 id 原样")
    void f1LegacyProviderConfigMigrated() throws Exception {
        Files.deleteIfExists(new java.io.File("./target/i6-g7-f1-upgrade-db.mv.db").toPath());

        String[] allLocations = "classpath:db/migration/h2,classpath:db/migration/bpm/h2,classpath:db/migration/notify/h2,classpath:db/migration/form/h2,classpath:db/migration/storage/h2,classpath:db/migration/job/h2,classpath:db/migration/agent/h2,classpath:db/migration/iot/h2,classpath:db/migration/openapi/h2,classpath:db/migration/system/h2".split(",");

        // ---------- 1. 只迁到 0.2.0 基线，模拟升级前存量库 ----------
        var phase1 = Flyway.configure().dataSource(UPGRADE_DB, USER, PASSWORD)
                .locations(allLocations)
                .target("0.2.0")
                .load();
        phase1.migrate();

        // ---------- 2. 写入旧 sw_agent_model_config 三行（openai / ollama / other） ----------
        try (Connection conn = DriverManager.getConnection(UPGRADE_DB, USER, PASSWORD);
             Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO sw_agent_model_config (id, name, protocol_type, base_url, model_name, "
                    + "api_key_cipher, temperature, max_tokens, top_p, timeout_seconds, retry_count, enabled, "
                    + "group_key, sort, quota_cooldown_seconds, tenant_id, deleted, version) VALUES "
                    + "(101, '旧-openai', 'openai', 'https://gw.example.com/v1', 'gpt-x', 'CIPHER-A', "
                    + "0.7, 1024, 0.9, 30, 1, 1, 'g1', 0, 60, 1, 0, 0), "
                    + "(102, '旧-ollama', 'ollama', 'http://ollama-host:11434', 'llama3', NULL, "
                    + "NULL, NULL, NULL, 30, 0, 1, NULL, 0, 60, 1, 0, 0), "
                    + "(103, '旧-other', 'other', 'https://closed.example.com', 'm-unknown', 'CIPHER-C', "
                    + "NULL, NULL, NULL, 30, 0, 1, NULL, 0, 60, 1, 0, 0)");
            // 已发布的旧图：V0.2.3 迁移应按当前版本回填一条快照
            st.executeUpdate("INSERT INTO sw_agent_graph_def (id, graph_key, name, def_version, status, graph_json, "
                    + "tenant_id, deleted, version, create_time, update_time) VALUES "
                    + "(901, 'agent_legacy', '旧图', 4, 'PUBLISHED', "
                    + "'{\"graphKey\":\"agent_legacy\",\"name\":\"旧图\",\"version\":4,\"elements\":[]}', "
                    + "1, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        // ---------- 3. 放开 target 迁满（V0.2.2/V0.2.3/V0.2.4/V0.2.5） ----------
        var phase2 = Flyway.configure().dataSource(UPGRADE_DB, USER, PASSWORD)
                .locations(allLocations)
                .load();
        var result = phase2.migrate();
        assertTrue(result.success, "第二阶段迁移（含 V0.2.2/V0.2.3/V0.2.4/V0.2.5）应成功");
        assertEquals("0.2.5", phase2.info().current().getVersion().getVersion(),
                "升级后终点应为 V0.2.5");

        // ---------- 4. 断言 D022-03 搬运结果 ----------
        try (Connection conn = DriverManager.getConnection(UPGRADE_DB, USER, PASSWORD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT p.api_format, p.base_url, p.enabled AS provider_enabled, m.model_name "
                     + "FROM sw_agent_provider p JOIN sw_agent_model m ON m.id = p.id AND m.provider_id = p.id "
                     + "WHERE p.id = ?")) {
            // openai：格式统一为 OPENAI_CHAT，地址/旧 id 原样
            assertLegacyRow(ps, 101, "OPENAI_CHAT", "https://gw.example.com/v1", true, "gpt-x");
            // ollama：OPENAI_CHAT + 地址补 /v1（OpenAI 兼容口），旧 id 原样
            assertLegacyRow(ps, 102, "OPENAI_CHAT", "http://ollama-host:11434/v1", true, "llama3");
            // other：OPENAI_CHAT 且供应商停用，旧 id 原样
            assertLegacyRow(ps, 103, "OPENAI_CHAT", "https://closed.example.com", false, "m-unknown");
        }

        // ---------- 5. D022-06：已发布旧图在 V0.2.3 回填快照 ----------
        try (Connection conn = DriverManager.getConnection(UPGRADE_DB, USER, PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT graph_def_id, def_version, graph_key, name, graph_json "
                             + "FROM sw_agent_graph_snapshot WHERE graph_def_id = 901")) {
            assertTrue(rs.next(), "已发布旧图应回填一条版本快照");
            assertEquals(901L, rs.getLong("graph_def_id"));
            assertEquals(4, rs.getInt("def_version"), "快照版本应等于图当前 defVersion");
            assertEquals("agent_legacy", rs.getString("graph_key"));
            assertEquals("旧图", rs.getString("name"));
            assertTrue(rs.getString("graph_json").contains("agent_legacy"));
        }
    }

    private static void assertLegacyRow(PreparedStatement ps, long id, String apiFormat, String baseUrl,
                                        boolean providerEnabled, String modelName) throws Exception {
        ps.setLong(1, id);
        try (ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "旧 id=" + id + " 应迁出供应商+模型且两表 id 一致");
            assertEquals(apiFormat, rs.getString("api_format"));
            assertEquals(baseUrl, rs.getString("base_url"));
            assertEquals(providerEnabled, rs.getBoolean("provider_enabled"));
            assertEquals(modelName, rs.getString("model_name"));
        }
    }
}
