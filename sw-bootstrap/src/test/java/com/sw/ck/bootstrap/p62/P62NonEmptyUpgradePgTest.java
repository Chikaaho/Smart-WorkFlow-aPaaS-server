package com.sw.ck.bootstrap.p62;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LT05a：非空旧数据先于 P62 增量迁移的时间链（隔离内嵌真实 PostgreSQL）。
 * <p>
 * 审查02 LT05a 的不可接受证据是「全新建库后写数据」——那只能证明新库可用，
 * 不能证明「迁移前已存在的非空数据」在应用本次增量后身份与值不变。
 * 本用例构建完整时间链：0.1.3 基线（V0.1.0）→ 写入代表数据（表单元数据 + 动态宽表行）
 * → 应用本次增量（V0.1.1 + 可重复迁移）→ 回读身份/值/既有查询，全程无数据改写。
 * </p><p>
 * 隔离性：使用 zonky 内嵌 PostgreSQL（临时集群，随 JVM 退出销毁），不触碰任何
 * 共享库；不做 DROP DATABASE，不写发布基线。校验和取自 flyway_schema_history
 * 机器记录，并附 V0.1.1 迁移文件 SHA-256 供对象身份绑定。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LT05a 非空旧数据先于 P62 增量的隔离升级链（内嵌真实 PostgreSQL）")
class P62NonEmptyUpgradePgTest {

    private static final String[] LOCATIONS = {"classpath:db/migration/postgresql"};
    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "postgres";

    private static final String LEGACY_FORM_ID = "lt05a-form-0001";
    private static final String LEGACY_FORM_KEY = "lt05a_legacy_form";
    private static final String LEGACY_TABLE = "lt05a_legacy_tbl";
    private static final String LEGACY_CONFIG_ID = "lt05a-conf-0001";

    /** P62 增量新建的 6 张表（V0.1.1）。 */
    private static final List<String> P62_TABLES = List.of(
            "sw_form_txn_action", "sw_form_txn_action_version", "sw_form_txn_invocation",
            "sw_form_txn_reservation", "sw_form_txn_ledger", "sw_form_c1_policy");

    private EmbeddedPostgres pg;
    private String url;

    @BeforeAll
    void startEmbedded() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        // stringtype=unspecified：与真实启动数据源同口径，jsonb 列可直接以文本参数写入
        url = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
    }

    @AfterAll
    void stopEmbedded() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    @DisplayName("基线 0.1.0 → 非空代表数据 → 本次增量 → 身份/值/既有查询回读无损且迁移历史可复算")
    void nonEmptyLegacyRowsSurviveP62Increment() throws Exception {
        // ============ 1. 隔离基线：0.1.3 发布时的迁移全集（V0.1.0 + R__i6） ============
        // 0.1.3 发版批次（a9483be）的 postgresql 目录仅含这两支；基线阶段用字节级复制隔离
        // 出同源文件，避免把本次增量才引入的 R__p62 提前执行，破坏时间链。
        Path baselineDir = Files.createTempDirectory("lt05a-baseline-0.1.3");
        for (String file : new String[]{
                "V0.1.0__baseline_seed.sql", "R__i6_notify_menu_reconciliation.sql"}) {
            String resource = "db/migration/postgresql/" + file;
            copyResource(resource, baselineDir.resolve(file));
            assertThat(sha256File(baselineDir.resolve(file))).as("基线复制字节同源：" + file)
                    .isEqualTo(resourceSha256(resource));
        }
        Flyway baseline = Flyway.configure().dataSource(url, PG_USER, PG_PASSWORD)
                .locations("filesystem:" + baselineDir.toAbsolutePath())
                .target(MigrationVersion.fromVersion("0.1.0"))
                .load();
        var baselineResult = baseline.migrate();
        assertThat(baselineResult.success).isTrue();
        String baselineHistory = historyRows();
        System.out.println("[P62-EV] lt05a.baseline migrated=" + baselineResult.migrationsExecuted
                + " target=0.1.0 set=V0.1.0+R__i6 history=" + baselineHistory);
        assertThat(baselineHistory).as("基线含 0.1.0").contains("0.1.0");

        // 基线阶段必须尚无 P62 结构与 P62 菜单（否则时间链不成立）
        assertThat(existingTables(P62_TABLES)).as("增量前不存在 P62 表").isEmpty();
        assertThat(countRowsWhere("sys_menu", "id IN (9100,9101,9102,9103)")).as("增量前无 P62 菜单")
                .isZero();

        // ============ 2. 非空代表数据：迁移前已存在 ============
        String insertedAt;
        try (Connection conn = conn()) {
            insertedAt = now(conn);
            // 表单元数据（已发布表单，业务查询入口）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sw_form_def (id, form_key, name, status, physical_table_name,"
                            + " form_version, tenant_id, deleted, version, create_time, update_time)"
                            + " VALUES (?, ?, ?, 'PUBLISHED', ?, 1, 0, 0, 0, now(), now())")) {
                ps.setString(1, LEGACY_FORM_ID);
                ps.setString(2, LEGACY_FORM_KEY);
                ps.setString(3, "LT05a 旧数据表单");
                ps.setString(4, LEGACY_TABLE);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO sw_form_config (id, form_id, definition, table_name, parent_table,"
                            + " tenant_id, deleted, version, create_time, update_time)"
                            + " VALUES (?, ?, ?, ?, NULL, 0, 0, 0, now(), now())")) {
                ps.setString(1, LEGACY_CONFIG_ID);
                ps.setString(2, LEGACY_FORM_ID);
                ps.setString(3, "{\"schemaVersion\":1,\"title\":\"LT05a 旧数据表单\",\"fields\":["
                        + "{\"name\":\"material\",\"type\":\"TEXT\",\"label\":\"物料\"},"
                        + "{\"name\":\"qty_available\",\"type\":\"NUMBER\",\"label\":\"可用量\"},"
                        + "{\"name\":\"qty_reserved\",\"type\":\"NUMBER\",\"label\":\"预占量\"}]}");
                ps.setString(4, LEGACY_TABLE);
                ps.executeUpdate();
            }
            // 动态宽表（应用生成的真实形态：系统列 + 用户列，NUMBER=NUMERIC(20,6)）
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE \"" + LEGACY_TABLE + "\" ("
                        + "  \"id\" VARCHAR(36) NOT NULL,"
                        + "  \"tenant_id\" BIGINT NOT NULL DEFAULT 0,"
                        + "  \"deleted\" SMALLINT NOT NULL DEFAULT 0,"
                        + "  \"create_time\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "  \"create_by\" BIGINT,"
                        + "  \"update_time\" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "  \"update_by\" BIGINT,"
                        + "  \"version\" BIGINT NOT NULL DEFAULT 0,"
                        + "  \"material\" VARCHAR(1000),"
                        + "  \"qty_available\" NUMERIC(20,6),"
                        + "  \"qty_reserved\" NUMERIC(20,6),"
                        + "  PRIMARY KEY (\"id\")"
                        + ")");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO \"" + LEGACY_TABLE + "\" (\"id\", \"tenant_id\", \"deleted\", \"version\","
                            + " \"material\", \"qty_available\", \"qty_reserved\", \"create_time\", \"update_time\")"
                            + " VALUES (?, 0, 0, 0, ?, ?, ?, now(), now())")) {
                ps.setString(1, "lt05a-rec-0001");
                ps.setString(2, "LT05A-M1");
                ps.setBigDecimal(3, new java.math.BigDecimal("100.000000"));
                ps.setBigDecimal(4, new java.math.BigDecimal("30.000000"));
                ps.executeUpdate();
                ps.setString(1, "lt05a-rec-0002");
                ps.setString(2, "LT05A-M2");
                ps.setBigDecimal(3, new java.math.BigDecimal("7.500000"));
                ps.setBigDecimal(4, new java.math.BigDecimal("0.000000"));
                ps.executeUpdate();
            }
        }
        String preDigest = legacyDigest();
        int preCount = legacyCount();
        List<String> preRows = legacyRows();
        assertThat(preCount).as("代表数据非空").isEqualTo(2);
        assertThat(insertedAt).isNotBlank();
        System.out.println("[P62-EV] lt05a.legacy-seeded rows=" + preCount + " digest=" + preDigest
                + " digest_sha256=" + sha256(preDigest) + " inserted_before_increment=true");

        // ============ 3. 本次增量（无 target 的最新链） ============
        Flyway latest = Flyway.configure().dataSource(url, PG_USER, PG_PASSWORD)
                .locations(LOCATIONS)
                .load();
        var incrementResult = latest.migrate();
        assertThat(incrementResult.success).isTrue();
        String incrementHistory = historyRows();
        String versionRow = historyWhere("version = '0.1.1'");
        System.out.println("[P62-EV] lt05a.increment migrated=" + incrementResult.migrationsExecuted
                + " applied=" + appliedList() + " history_0_1_1=" + versionRow);
        assertThat(versionRow).as("V0.1.1 进入迁移历史").contains("0.1.1").contains("true");

        // ============ 4. 迁移后回读：身份/值/既有查询 ============
        List<String> postRows = legacyRows();
        assertThat(legacyCount()).as("迁移后行数不减").isEqualTo(preCount);
        assertThat(legacyDigest()).as("迁移后行内容摘要一致（无数据改写）").isEqualTo(preDigest);
        assertThat(postRows).as("迁移后逐行身份/值一致").isEqualTo(preRows);
        assertThat(legacyFormMeta()).as("表单元数据回读一致").contains(LEGACY_FORM_KEY).contains(LEGACY_TABLE);

        // 既有查询（应用读路径形态：租户 + 未删条件）仍可执行且结果正确
        try (Connection conn = conn();
             PreparedStatement ps = conn.prepareStatement("SELECT \"material\", \"qty_available\" - \"qty_reserved\""
                     + " AS \"available\" FROM \"" + LEGACY_TABLE + "\""
                     + " WHERE \"tenant_id\" = 0 AND \"deleted\" = 0 ORDER BY \"id\"")) {
            try (ResultSet rs = ps.executeQuery()) {
                List<String> available = new ArrayList<>();
                while (rs.next()) {
                    available.add(rs.getString(1) + "=" + rs.getBigDecimal(2).toPlainString());
                }
                assertThat(available).as("既有可用量查询迁移后结果").containsExactly("LT05A-M1=70.000000", "LT05A-M2=7.500000");
            }
        }

        // 增量结构就位：P62 六表存在且可查询（零行）；P62 菜单与授权随增量落库
        assertThat(existingTables(P62_TABLES)).as("增量后 P62 六表齐备").containsExactlyInAnyOrderElementsOf(P62_TABLES);
        assertThat(countRows("sw_form_txn_action")).isZero();
        assertThat(countRows("sw_form_c1_policy")).isZero();
        assertThat(countRowsWhere("sys_menu", "id IN (9100,9101,9102,9103)")).as("增量后 P62 菜单落库").isEqualTo(4L);
        assertThat(countRowsWhere("sys_role_menu", "menu_id IN (9100,9101,9102,9103) AND role_id = 2"))
                .as("增量后 P62 角色授权落库").isEqualTo(4L);

        // 迁移历史复算：版本链、校验和、安装时间
        assertThat(historyRows()).contains("0.1.0").contains("0.1.1");
        System.out.println("[P62-EV] lt05a.readback rows=" + legacyCount() + " digest=" + legacyDigest()
                + " digest_sha256=" + sha256(legacyDigest()) + " digest_equal=" + preDigest.equals(legacyDigest())
                + " p62_tables=6 history=" + incrementHistory);
        System.out.println("[P62-EV] lt05a.file-identity migration_file=V0.1.1__form_local_transaction_actions.sql"
                + " sha256=" + resourceSha256("db/migration/postgresql/V0.1.1__form_local_transaction_actions.sql"));
    }

    // ==================== 数据与历史工具 ====================

    private Connection conn() throws Exception {
        return DriverManager.getConnection(url, PG_USER, PG_PASSWORD);
    }

    private static String now(Connection conn) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT now()::timestamp::text")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private String legacyDigest() throws Exception {
        return queryString("SELECT md5(string_agg(\"id\" || '|' || \"material\" || '|' ||"
                + " \"qty_available\"::text || '|' || \"qty_reserved\"::text || '|' || \"create_time\"::text, ','"
                + " ORDER BY \"id\")) FROM \"" + LEGACY_TABLE + "\" WHERE \"tenant_id\" = 0 AND \"deleted\" = 0");
    }

    private int legacyCount() throws Exception {
        return Integer.parseInt(queryString("SELECT count(*)::text FROM \"" + LEGACY_TABLE + "\""));
    }

    private List<String> legacyRows() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection conn = conn();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT \"id\", \"material\", \"qty_available\"::text,"
                     + " \"qty_reserved\"::text, \"create_time\"::text FROM \"" + LEGACY_TABLE + "\" ORDER BY \"id\"")) {
            while (rs.next()) {
                rows.add(String.join("|", rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)));
            }
        }
        return rows;
    }

    private String legacyFormMeta() throws Exception {
        return queryString("SELECT \"form_key\" || '|' || \"physical_table_name\"::text || '|' || \"status\""
                + " FROM sw_form_def WHERE \"id\" = '" + LEGACY_FORM_ID + "'");
    }

    private List<String> existingTables(List<String> names) throws Exception {
        List<String> found = new ArrayList<>();
        for (String name : names) {
            String exists = queryString("SELECT count(*)::text FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_name = '" + name + "'");
            if ("1".equals(exists)) {
                found.add(name);
            }
        }
        return found;
    }

    private long countRows(String table) throws Exception {
        return Long.parseLong(queryString("SELECT count(*)::text FROM " + table));
    }

    private long countRowsWhere(String table, String where) throws Exception {
        return Long.parseLong(queryString("SELECT count(*)::text FROM " + table + " WHERE " + where));
    }

    private static void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = P62NonEmptyUpgradePgTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("迁移资源缺失: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256File(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private String historyRows() throws Exception {
        return queryString("SELECT string_agg(coalesce(version, description) || ':' || checksum || ':'"
                + " || installed_on::timestamp(0)::text || ':' || success::text || ':' || execution_time || 'ms',"
                + " ' , ' ORDER BY installed_rank) FROM flyway_schema_history");
    }

    private String historyWhere(String where) throws Exception {
        return queryString("SELECT \"version\" || '|' || \"description\" || '|' || checksum::text || '|'"
                + " || installed_on::timestamp(0)::text || '|' || success::text || '|' || execution_time || 'ms'"
                + " FROM flyway_schema_history WHERE " + where);
    }

    private String appliedList() throws Exception {
        return queryString("SELECT string_agg(coalesce(\"version\", \"description\"), '>' ORDER BY installed_rank)"
                + " FROM flyway_schema_history WHERE success = true");
    }

    private String queryString(String sql) throws Exception {
        try (Connection conn = conn();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String resourceSha256(String resource) throws Exception {
        try (InputStream in = P62NonEmptyUpgradePgTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return "RESOURCE_NOT_FOUND";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
