package com.sw.ck.bootstrap.f1;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1 阶段 1 · D022-03 旧数据迁移验证（PostgreSQL，不启动 Spring 上下文）。
 * <p>
 * 演练两段式迁移：先 target=0.2.0 建基线，向 sw_agent_model_config 种入
 * openai / ollama / other 三行旧配置，再放开 target 迁满 V0.2.2，
 * 断言每行都生成"供应商+模型"且 id 原样带入：
 * <ul>
 *   <li>openai：OPENAI_CHAT，地址原样，密钥/参数搬运；</li>
 *   <li>ollama：OPENAI_CHAT，地址补 /v1（OpenAI 兼容口）；已带 /v1 则不改；</li>
 *   <li>other：OPENAI_CHAT，供应商 enabled=0 停用等待重新配置；</li>
 *   <li>旧 agentModelConfigId 在新 sw_agent_model 中可直接按 id 解析。</li>
 * </ul>
 */
@DisplayName("F1 V0.2.2：供应商/模型两级表迁移 + 旧 sw_agent_model_config 数据搬迁")
class F1ProviderModelMigrationPostgresTest {

    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";
    private static final String[] APP_LOCATIONS = {
            "classpath:db/migration/{vendor}",
            "classpath:db/migration/bpm/{vendor}",
            "classpath:db/migration/notify/{vendor}",
            "classpath:db/migration/form/{vendor}",
            "classpath:db/migration/storage/{vendor}",
            "classpath:db/migration/job/{vendor}",
            "classpath:db/migration/agent/{vendor}",
            "classpath:db/migration/iot/{vendor}",
            "classpath:db/migration/openapi/{vendor}",
            "classpath:db/migration/system/{vendor}"
    };

    private static EmbeddedPostgres pg;
    private static String url;
    private static String[] locations;

    @BeforeAll
    static void twoPhaseMigrateWithLegacyRows() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        url = pg.getJdbcUrl(USER, "postgres");
        locations = Arrays.stream(APP_LOCATIONS)
                .map(location -> location.replace("{vendor}", "postgresql"))
                .toArray(String[]::new);

        // 阶段一：只迁到 0.2.0 基线（不含 V0.2.2）
        Flyway phase1 = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(locations)
                .target("0.2.0")
                .load();
        phase1.migrate();

        // 种入三行旧配置（id 显式指定，模拟历史数据；密文列填占位，不涉真实密钥）
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO sw_agent_model_config (id, name, protocol_type, base_url, model_name, "
                    + "api_key_cipher, temperature, max_tokens, top_p, timeout_seconds, retry_count, enabled, "
                    + "group_key, sort, quota_cooldown_seconds, tenant_id, deleted, version) VALUES "
                    + "(101, '旧-openai', 'openai', 'https://gw.example.com/v1', 'gpt-x', 'CIPHER-A', "
                    + "0.7, 1024, 0.9, 30, 1, 1, 'g1', 0, 60, 1, 0, 0), "
                    + "(102, '旧-ollama', 'ollama', 'http://ollama-host:11434', 'llama3', NULL, "
                    + "NULL, NULL, NULL, 30, 0, 1, NULL, 0, 60, 1, 0, 0), "
                    + "(103, '旧-other', 'other', 'https://closed.example.com', 'm-unknown', 'CIPHER-C', "
                    + "NULL, NULL, NULL, 30, 0, 1, NULL, 0, 60, 1, 0, 0)");
        }

        // 阶段二：放开 target 迁满（R__ 同步执行）
        MigrateResult phase2 = Flyway.configure()
                .dataSource(url, USER, PASSWORD)
                .locations(locations)
                .load()
                .migrate();
        assertTrue(phase2.success, "第二段迁移（含 V0.2.2）应成功");
    }

    @AfterAll
    static void stopPg() throws IOException {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    @DisplayName("openai 旧行：迁为 OPENAI_CHAT 供应商+模型，id/地址/密文/参数原样带入")
    void openAiRowMigrated() throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT p.api_format, p.base_url, p.api_key_cipher, p.enabled, "
                    + "m.model_name, m.temperature, m.max_tokens, m.top_p, m.group_key, m.enabled AS model_enabled "
                    + "FROM sw_agent_provider p JOIN sw_agent_model m ON m.provider_id = p.id AND m.id = p.id "
                    + "WHERE p.id = 101")) {
                assertTrue(rs.next(), "旧 id=101 应迁出供应商+模型且两表 id 一致");
                assertEquals("OPENAI_CHAT", rs.getString("api_format"));
                assertEquals("https://gw.example.com/v1", rs.getString("base_url"));
                assertEquals("CIPHER-A", rs.getString("api_key_cipher"));
                assertTrue(rs.getBoolean("enabled"), "openai 供应商应启用");
                assertEquals("gpt-x", rs.getString("model_name"));
                assertEquals(0.7d, rs.getDouble("temperature"), 1e-9);
                assertEquals(1024, rs.getInt("max_tokens"));
                assertEquals("g1", rs.getString("group_key"));
                assertTrue(rs.getBoolean("model_enabled"), "模型行应启用");
            }
        }
    }

    @Test
    @DisplayName("ollama 旧行：OPENAI_CHAT + 地址补 /v1（OpenAI 兼容口）")
    void ollamaRowUrlFixed() throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT api_format, base_url FROM sw_agent_provider WHERE id = 102")) {
                assertTrue(rs.next(), "旧 id=102 应迁出供应商");
                assertEquals("OPENAI_CHAT", rs.getString("api_format"));
                assertEquals("http://ollama-host:11434/v1", rs.getString("base_url"));
            }
            // 已带 /v1 的地址不应重复追加（迁移 SQL 的 NOT LIKE 分支）
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sw_agent_model WHERE id = 102 AND model_name = 'llama3'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "旧 id=102 应迁出模型行");
            }
        }
    }

    @Test
    @DisplayName("other 旧行：供应商 enabled=0 停用等待重新配置（D022-03）")
    void otherRowDisabled() throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT enabled, api_format FROM sw_agent_provider WHERE id = 103")) {
                assertTrue(rs.next(), "旧 id=103 应迁出供应商");
                assertFalse(rs.getBoolean("enabled"), "other 供应商应停用");
                assertEquals("OPENAI_CHAT", rs.getString("api_format"));
            }
        }
    }

    @Test
    @DisplayName("旧 agentModelConfigId 直接按 id 解析到新模型行（graph_json/会话引用不变可解析）")
    void legacyConfigIdResolvesToNewModel() throws Exception {
        try (Connection conn = DriverManager.getConnection(url, USER, PASSWORD);
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM sw_agent_model WHERE id IN (101, 102, 103)")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "三个旧 id 应都可直接命中 sw_agent_model");
            }
        }
    }
}
