package com.sw.ck.bootstrap.p62;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 0.1.5 发布收敛升级通道常驻验证（隔离内嵌真实 PostgreSQL）。
 * <p>
 * 本类原为 LT05a「非空旧数据先于 P62 增量」时间链；0.1.5/0.1.6/0.1.7 三轮发版收敛
 * （versioned 链 → 每方言单一 {@code V0.1.7__baseline_seed.sql}，旧链与 0.1.5/0.1.6 期迁移
 * 移入独立升级通道 {@code db/upgrade/0.1.5/}、{@code db/upgrade/0.1.6/}、{@code db/upgrade/0.1.7/}）后，
 * 时间链重写为升级通道的受控路径，非空数据无损语义延续：</p>
 * <ol>
 *   <li>通道A（历史衔接）：在役旧链全量库（0.1.10 终态 + 业务数据）→
 *       {@code transition-history-to-0.1.5.postgresql.sql} →
 *       {@code upgrade-to-0.1.6-from-0.1.5.postgresql.sql} →
 *       {@code upgrade-to-0.1.7-from-0.1.6.postgresql.sql} → validate 通过、
 *       重复 migrate 零执行、数据无损、目录与全新种子库一致；</li>
 *   <li>通道B（存量升级）：legacy 0.1.1 库（业务数据）→
 *       {@code upgrade-to-0.1.5-from-0.1.1.postgresql.sql} →
 *       {@code upgrade-to-0.1.6-from-0.1.5.postgresql.sql} →
 *       {@code upgrade-to-0.1.7-from-0.1.6.postgresql.sql} → 同上。</li>
 * </ol>
 * <p>
 * 隔离性：zonky 内嵌 PostgreSQL（临时集群，随 JVM 退出销毁），不触碰共享库。
 * 升级脚本按发布资产原文从 classpath 读取，测试即验证交付物本身。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("0.1.6→0.1.7 升级通道常驻验证：历史衔接与存量升级（内嵌真实 PostgreSQL）")
class P62NonEmptyUpgradePgTest {

    private static final String PG_USER = "postgres";
    private static final String PG_PASSWORD = "postgres";

    /** 升级通道脚本（发布资产原文）。 */
    private static final String TRANSITION_SCRIPT =
            "db/upgrade/0.1.5/transition-history-to-0.1.5.postgresql.sql";
    private static final String UPGRADE_FROM_0_1_1_SCRIPT =
            "db/upgrade/0.1.5/upgrade-to-0.1.5-from-0.1.1.postgresql.sql";
    private static final String UPGRADE_FROM_0_1_5_SCRIPT =
            "db/upgrade/0.1.6/upgrade-to-0.1.6-from-0.1.5.postgresql.sql";
    private static final String TRANSITION_016_SCRIPT =
            "db/upgrade/0.1.6/transition-history-to-0.1.6.postgresql.sql";
    private static final String UPGRADE_FROM_0_1_6_SCRIPT =
            "db/upgrade/0.1.7/upgrade-to-0.1.7-from-0.1.6.postgresql.sql";
    private static final String LEGACY_DIR_RESOURCE = "db/upgrade/0.1.5/legacy-0.1.x/postgresql";

    private static final String LEGACY_TABLE = "lt05a_legacy_tbl";

    private static EmbeddedPostgres pg;
    private static String url;

    @BeforeAll
    static void startEmbedded() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        // stringtype=unspecified：与真实启动数据源同口径，jsonb 列可直接以文本参数写入
        url = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";
    }

    @AfterAll
    static void stopEmbedded() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @Test
    @DisplayName("通道A：在役旧链全量库（0.1.10 终态+业务数据）→ 0.1.5 衔接 → 0.1.6 存量升级脚本 → validate 通过/零重复执行/数据无损/目录=全新种子")
    void legacyFullChainSurvivesHistoryTransition() throws Exception {
        String db = createDatabase("lt05a_tr_full");

        // 1) 旧链全量迁移（legacy V0.1.0—V0.1.10 + 9 个 R__ 副本）
        var result = flywayLegacy(db).load().migrate();
        assertThat(result.success).isTrue();
        assertThat(currentVersion(db)).isEqualTo("0.1.10");

        // 2) 非空代表数据（迁移/衔接前已存在）
        seedBusinessData(db);
        String preDigest = legacyDigest(db);
        int preCount = legacyCount(db);

        // 3) 历史衔接脚本（0.1.5，发布资产原文）→ 0.1.6 存量升级脚本（应用中心增量 + 历史衔接）
        //    → 0.1.7 存量升级脚本（0.1.7 期增量 + 历史衔接）
        execScript(db, classpathResource(TRANSITION_SCRIPT));
        execScript(db, classpathResource(UPGRADE_FROM_0_1_5_SCRIPT));
        execScript(db, classpathResource(UPGRADE_FROM_0_1_6_SCRIPT));

        // 4) 衔接后：主链继续到最新 + validate 通过 + 重复 migrate 零执行 + 终点=0.1.7
        Flyway after = Flyway.configure().dataSource(dbUrl(db), PG_USER, PG_PASSWORD)
                .locations(appLocations()).load();
        MigrateResult upgrade = after.migrate();
        assertThat(upgrade.success).isTrue();
        after.validate();
        MigrateResult again = after.migrate();
        assertEquals(0, again.migrationsExecuted, "衔接后重复 migrate 不应执行任何迁移");
        assertThat(currentVersion(db)).as("衔接并升级后终点应为 0.1.10").isEqualTo("0.1.10");

        // 5) 数据无损 + 目录与全新种子库一致
        assertThat(legacyCount(db)).as("衔接后行数不减").isEqualTo(preCount);
        assertThat(legacyDigest(db)).as("衔接后行内容摘要一致").isEqualTo(preDigest);
        assertHistorySeedAndTenantMigrations(db);
        String fresh = createDatabase("lt05a_tr_full_fresh");
        Flyway.configure().dataSource(dbUrl(fresh), PG_USER, PG_PASSWORD)
                .locations(appLocations()).load().migrate();
        assertThat(catalogOf(db)).as("衔接后目录应与全新种子库一致")
                .isEqualTo(catalogOf(fresh));
        System.out.println("[P62-EV] r017.transition fullchain rows=" + legacyCount(db)
                + " digest_sha256=" + sha256(preDigest) + " history=0.1.7 rerun=0 validate=pass");
    }

    @Test
    @DisplayName("通道B：legacy 0.1.1 库（业务数据）→ 0.1.5 增量升级 → 0.1.6 存量升级脚本 → validate 通过/终点 0.1.6/数据无损/目录=全新种子")
    void legacy011UpgradesToSeedWithoutDataLoss() throws Exception {
        String db = createDatabase("lt05a_up_011");

        // 1) 旧链迁移到 0.1.1（R__ 全量照常应用；衔接脚本只处理 versioned 行）
        Flyway.configure().dataSource(dbUrl(db), PG_USER, PG_PASSWORD)
                .locations(legacyDir()).target("0.1.1").load().migrate();
        assertThat(currentVersion(db)).isEqualTo("0.1.1");

        // 2) 非空代表数据（升级前已存在）
        seedBusinessData(db);
        String preDigest = legacyDigest(db);
        int preCount = legacyCount(db);

        // 3) 增量升级脚本（0.1.5）→ 0.1.6 存量升级脚本（应用中心增量 + 历史衔接，发布资产原文）
        //    → 0.1.7 存量升级脚本（0.1.7 期增量 + 历史衔接）
        execScript(db, classpathResource(UPGRADE_FROM_0_1_1_SCRIPT));
        execScript(db, classpathResource(UPGRADE_FROM_0_1_5_SCRIPT));
        execScript(db, classpathResource(UPGRADE_FROM_0_1_6_SCRIPT));

        // 4) 升级后：主链继续到最新 + validate 通过 + 重复 migrate 零执行 + 终点=0.1.7
        Flyway after = Flyway.configure().dataSource(dbUrl(db), PG_USER, PG_PASSWORD)
                .locations(appLocations()).load();
        MigrateResult upgrade = after.migrate();
        assertThat(upgrade.success).isTrue();
        after.validate();
        MigrateResult again = after.migrate();
        assertEquals(0, again.migrationsExecuted, "升级后重复 migrate 不应执行任何迁移");
        assertThat(currentVersion(db)).as("升级后终点应为 0.1.10").isEqualTo("0.1.10");

        // 5) 数据无损 + 目录与全新种子库一致
        assertThat(legacyCount(db)).as("升级后行数不减").isEqualTo(preCount);
        assertThat(legacyDigest(db)).as("升级后行内容摘要一致").isEqualTo(preDigest);
        assertHistorySeedAndTenantMigrations(db);
        String fresh = createDatabase("lt05a_up_011_fresh");
        Flyway.configure().dataSource(dbUrl(fresh), PG_USER, PG_PASSWORD)
                .locations(appLocations()).load().migrate();
        assertThat(catalogOf(db)).as("升级后目录应与全新种子库一致")
                .isEqualTo(catalogOf(fresh));
        System.out.println("[P62-EV] r017.upgrade-from-0.1.1 rows=" + legacyCount(db)
                + " digest_sha256=" + sha256(preDigest) + " history=0.1.7 rerun=0 validate=pass");
    }

    @Test
    @DisplayName("通道C：已应用应用中心增量的库（0.1.5 + V20261015190000 + 业务数据）→ 0.1.6 历史衔接脚本 → validate 通过/零重复执行/数据无损")
    void appliedIncrementSurvivesHistoryTransitionTo016() throws Exception {
        String db = createDatabase("lt05a_tr_incr016");

        // 1) 0.1.5 + 应用中心增量形态（模拟已执行时间戳 20261015190000 的在役库：
        //    升级通道 legacy 资源 + 现行 R__ 副本临时目录，不含主链 V0.1.7 种子）
        Flyway.configure().dataSource(dbUrl(db), PG_USER, PG_PASSWORD)
                .locations(legacy016Dir()).load().migrate();
        assertThat(currentVersion(db)).isEqualTo("20261015190000");

        // 2) 非空代表数据（衔接前已存在）
        seedBusinessData(db);
        String preDigest = legacyDigest(db);
        int preCount = legacyCount(db);

        // 3) 0.1.6 历史衔接脚本 + 0.1.7 存量升级脚本（发布资产原文）
        execScript(db, classpathResource(TRANSITION_016_SCRIPT));
        execScript(db, classpathResource(UPGRADE_FROM_0_1_6_SCRIPT));

        // 4) 衔接后：主链零重放 + validate 通过 + 重复 migrate 零执行 + 终点=0.1.7
        Flyway after = Flyway.configure().dataSource(dbUrl(db), PG_USER, PG_PASSWORD)
                .locations(appLocations()).load();
        assertThat(after.migrate().success).isTrue();
        after.validate();
        MigrateResult again = after.migrate();
        assertEquals(0, again.migrationsExecuted, "衔接后重复 migrate 不应执行任何迁移");
        assertThat(currentVersion(db)).as("衔接后终点应为 0.1.10").isEqualTo("0.1.10");

        // 5) 数据无损 + 目录与全新种子库一致
        assertThat(legacyCount(db)).as("衔接后行数不减").isEqualTo(preCount);
        assertThat(legacyDigest(db)).as("衔接后行内容摘要一致").isEqualTo(preDigest);
        assertHistorySeedAndTenantMigrations(db);
        String fresh = createDatabase("lt05a_tr_incr016_fresh");
        Flyway.configure().dataSource(dbUrl(fresh), PG_USER, PG_PASSWORD)
                .locations(appLocations()).load().migrate();
        assertThat(catalogOf(db)).as("衔接后目录应与全新种子库一致")
                .isEqualTo(catalogOf(fresh));
        System.out.println("[P62-EV] r017.transition applied-increment rows=" + legacyCount(db)
                + " digest_sha256=" + sha256(preDigest) + " history=0.1.7 rerun=0 validate=pass");
    }

    /** 0.1.5+增量形态位置 = 临时目录（0.1.6 通道 legacy 版本化文件 + 现行 12 个 R__ 副本）。 */
    private static String[] legacy016Dir() throws Exception {
        Path dir = Files.createTempDirectory("r016-legacy-pg-");
        for (String v : new String[]{"V0.1.5__baseline_seed.sql", "V20261015190000__application_center.sql"}) {
            Files.writeString(dir.resolve(v),
                    classpathResource("db/upgrade/0.1.6/legacy-0.1.5/postgresql/" + v), StandardCharsets.UTF_8);
        }
        for (String r : new String[]{
                "R__0.1.7_catalog_menu_first.sql", "R__0.1.7_org_demo_seed.sql",
                "R__bf20261013_catalog_admin_menu.sql", "R__i6_notify_menu_reconciliation.sql",
                "R__p62_form_txn_action_menu.sql", "R__p62_iot_command_verify_menu.sql",
                "R__p62_resource_ops_menu.sql", "R__p62_txn_batch_menu.sql",
                "R__p63_iot_reservation_menu.sql", "R__p64_approval_form_menu.sql",
                "R__p64_position_delegate_menu.sql", "R__application_center_menu.sql"}) {
            Files.writeString(dir.resolve(r), classpathResource("db/migration/postgresql/" + r),
                    StandardCharsets.UTF_8);
        }
        return new String[]{"filesystem:" + dir.toAbsolutePath()};
    }

    // ==================== 数据与历史工具 ====================

    private static String[] appLocations() {
        return new String[]{
                "classpath:db/migration/postgresql",
                "classpath:db/migration/bpm/postgresql",
                "classpath:db/migration/notify/postgresql",
                "classpath:db/migration/form/postgresql",
                "classpath:db/migration/storage/postgresql",
                "classpath:db/migration/job/postgresql",
                "classpath:db/migration/agent/postgresql",
                "classpath:db/migration/iot/postgresql",
                "classpath:db/migration/openapi/postgresql",
                "classpath:db/migration/system/postgresql"};
    }

    /** 旧链位置 = 临时目录（legacy V 文件 + 主链 9 个 R__ 副本），避免与新种子同号冲突。 */
    private static String[] legacyDir() throws Exception {
        Path dir = Files.createTempDirectory("r015-legacy-pg-");
        for (int i = 0; i <= 10; i++) {
            String name = i == 0 ? "V0.1.0__baseline_seed.sql" : "V0.1." + i + "__" + LEGACY_TAILS.get(i) + ".sql";
            Files.writeString(dir.resolve(name), classpathResource(LEGACY_DIR_RESOURCE + "/" + name),
                    StandardCharsets.UTF_8);
        }
        for (String r : new String[]{
                "R__bf20261013_catalog_admin_menu.sql", "R__i6_notify_menu_reconciliation.sql",
                "R__p62_form_txn_action_menu.sql", "R__p62_iot_command_verify_menu.sql",
                "R__p62_resource_ops_menu.sql", "R__p62_txn_batch_menu.sql",
                "R__p63_iot_reservation_menu.sql", "R__p64_approval_form_menu.sql",
                "R__p64_position_delegate_menu.sql"}) {
            Files.writeString(dir.resolve(r), classpathResource("db/migration/postgresql/" + r),
                    StandardCharsets.UTF_8);
        }
        return new String[]{"filesystem:" + dir.toAbsolutePath()};
    }

    private static final java.util.Map<Integer, String> LEGACY_TAILS = java.util.Map.ofEntries(
            java.util.Map.entry(1, "form_local_transaction_actions"),
            java.util.Map.entry(2, "tiered_command_semantics"),
            java.util.Map.entry(3, "batch_command"),
            java.util.Map.entry(4, "resource_assurance"),
            java.util.Map.entry(5, "p63_dynamic_branch_semantics"),
            java.util.Map.entry(6, "p63_iot_command_reservation"),
            java.util.Map.entry(7, "p64_orchestration_data_action"),
            java.util.Map.entry(8, "p64_phase2_parent_child"),
            java.util.Map.entry(9, "p64_position_delegate"),
            java.util.Map.entry(10, "p64_approval_form_type"));

    /** 代表业务数据：0.1.1 时点已可存在的形态（表单元数据 + 动态宽表行）。 */
    private static void seedBusinessData(String db) throws Exception {
        try (Connection conn = conn(db); Statement st = conn.createStatement()) {
            st.executeUpdate("INSERT INTO sw_form_def (id, form_key, name, status, physical_table_name,"
                    + " form_version, tenant_id, deleted, version, create_time, update_time)"
                    + " VALUES ('r015-form-0001', 'r015_legacy_form', '升级通道旧数据表单', 'PUBLISHED',"
                    + " '" + LEGACY_TABLE + "', 1, 0, 0, 0, now(), now())");
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
            st.executeUpdate("INSERT INTO \"" + LEGACY_TABLE + "\" (\"id\", \"tenant_id\", \"deleted\", \"version\","
                    + " \"material\", \"qty_available\", \"qty_reserved\", \"create_time\", \"update_time\")"
                    + " VALUES ('r015-rec-0001', 0, 0, 0, 'R015-M1', 100.000000, 30.000000, now(), now())");
            st.executeUpdate("INSERT INTO \"" + LEGACY_TABLE + "\" (\"id\", \"tenant_id\", \"deleted\", \"version\","
                    + " \"material\", \"qty_available\", \"qty_reserved\", \"create_time\", \"update_time\")"
                    + " VALUES ('r015-rec-0002', 0, 0, 0, 'R015-M2', 7.500000, 0.000000, now(), now())");
        }
    }

    private static String legacyDigest(String db) throws Exception {
        return queryString(db, "SELECT md5(string_agg(\"id\" || '|' || \"material\" || '|'"
                + " || \"qty_available\"::text || '|' || \"qty_reserved\"::text, ',' ORDER BY \"id\"))"
                + " FROM \"" + LEGACY_TABLE + "\" WHERE \"tenant_id\" = 0 AND \"deleted\" = 0");
    }

    private static int legacyCount(String db) throws Exception {
        return Integer.parseInt(queryString(db, "SELECT count(*)::text FROM \"" + LEGACY_TABLE + "\""));
    }

    /** 保留历史种子及其校验和，并验证主链追加的两条租户迁移。 */
    private static void assertHistorySeedAndTenantMigrations(String db) throws Exception {
        int versioned = Integer.parseInt(queryString(db,
                "SELECT count(*)::text FROM \"flyway_schema_history\" WHERE \"version\" IS NOT NULL"));
        assertEquals(4, versioned, "versioned 历史应为 0.1.7 种子及 0.1.8/0.1.9/0.1.10 增量");
        assertEquals("0.1.7,0.1.8,0.1.9,0.1.10", queryString(db,
                "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history"
                        + " WHERE version IS NOT NULL AND success = true"));
        String seed = queryString(db, "SELECT \"version\" || '|' || \"success\"::text || '|' || \"checksum\""
                + " FROM \"flyway_schema_history\" WHERE \"version\" = '0.1.7'");
        assertThat(seed).as("0.1.7 成功记录及校验和").isNotNull().endsWith("|true|2016046975");
    }

    /** 目录口径：表集合 + 列定义 + 索引名（数据不计；时间戳/行序不构成目录差异）。 */
    private static String catalogOf(String db) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Connection conn = conn(db); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                            + "AND table_name <> 'flyway_schema_history' AND table_name <> '"
                            + LEGACY_TABLE + "' ORDER BY table_name")) {
                List<String> tables = new ArrayList<>();
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                out.append("tables=").append(tables).append('\n');
                for (String table : tables) {
                    try (ResultSet rs2 = st.executeQuery(
                            "SELECT column_name || ':' || data_type || ':' || is_nullable"
                                    + " FROM information_schema.columns WHERE table_schema = 'public'"
                                    + " AND table_name = '" + table + "' ORDER BY ordinal_position")) {
                        List<String> cols = new ArrayList<>();
                        while (rs2.next()) {
                            cols.add(rs2.getString(1));
                        }
                        out.append(table).append('=').append(cols).append('\n');
                    }
                    try (ResultSet rs3 = st.executeQuery(
                            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'"
                                    + " AND tablename = '" + table + "' ORDER BY indexname")) {
                        List<String> idx = new ArrayList<>();
                        while (rs3.next()) {
                            idx.add(rs3.getString(1));
                        }
                        out.append(table).append("#idx=").append(idx).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static String createDatabase(String name) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, PG_USER, PG_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static String dbUrl(String db) {
        return url.replace("/postgres", "/" + db);
    }

    private static Connection conn(String db) throws Exception {
        return DriverManager.getConnection(dbUrl(db), PG_USER, PG_PASSWORD);
    }

    private static FluentConfiguration flywayLegacy(String db) throws Exception {
        return Flyway.configure().dataSource(dbUrl(db), PG_USER, PG_PASSWORD).locations(legacyDir());
    }

    private static String currentVersion(String db) throws Exception {
        String v = queryString(db, "SELECT \"version\" FROM \"flyway_schema_history\" "
                + "WHERE \"success\" = TRUE AND \"version\" IS NOT NULL ORDER BY \"installed_rank\" DESC LIMIT 1");
        assertThat(v).as("迁移历史不应为空").isNotNull();
        return v;
    }

    private static void execScript(String db, String script) throws Exception {
        try (Connection conn = conn(db); Statement st = conn.createStatement()) {
            for (String raw : script.split(";")) {
                String sql = stripComments(raw);
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
    }

    private static String stripComments(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (!line.strip().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String classpathResource(String resource) throws Exception {
        try (var in = P62NonEmptyUpgradePgTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("发布资产缺失: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String queryString(String db, String sql) throws Exception {
        try (Connection conn = conn(db); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
