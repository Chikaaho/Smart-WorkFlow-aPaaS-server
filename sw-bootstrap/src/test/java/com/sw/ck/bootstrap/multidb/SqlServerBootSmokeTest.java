package com.sw.ck.bootstrap.multidb;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.entity.BpmTaskOpinionDraft;
import com.sw.ck.bpm.process.mapper.BpmTaskOpinionDraftMapper;
import com.sw.ck.knowledge.vector.KnowledgeVectorStore;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysWorkspaceCardType;
import com.sw.ck.system.mapper.SysWorkspaceCardTypeMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL Server 2019/2022 空库启动冒烟（未配置 {@code sqlserver.url} 时跳过）。
 * <p>
 * 数据库排序规则必须为 Chinese_PRC_CS_AS；迁移由 Flyway 的 sqlserver 目录执行，
 * 覆盖 30 条迁移、种子、MyBatis、中文、JSON、动态宽表、向量与主流程业务。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SQL Server 空库启动冒烟（真实库；未配置 sqlserver.url 时跳过）")
class SqlServerBootSmokeTest {

    private static final String DATABASE = "swmdb_sqlserver_smoke";

    private String masterUrl;
    private String user;
    private String password;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private String base;

    @BeforeAll
    void boot() throws Exception {
        String url = System.getProperty("sqlserver.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 sqlserver.url，跳过 SQL Server 启动冒烟");
        user = System.getProperty("sqlserver.user", "sa");
        password = System.getProperty("sqlserver.password", "");
        masterUrl = url.replaceAll("(?i);databaseName=[^;]*", "") + ";databaseName=master";
        String targetUrl = url.replaceAll("(?i);databaseName=[^;]*", "")
                + ";databaseName=" + DATABASE;

        try (Connection connection = DriverManager.getConnection(masterUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("IF DB_ID('" + DATABASE + "') IS NOT NULL BEGIN "
                    + "ALTER DATABASE [" + DATABASE + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE; "
                    + "DROP DATABASE [" + DATABASE + "]; END");
            statement.executeUpdate("CREATE DATABASE [" + DATABASE + "] COLLATE Chinese_PRC_CS_AS");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.profiles.active", "dev");
        props.put("ch.dev.test-mock", "true");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        props.put("spring.datasource.dynamic.datasource.master.url", targetUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", user);
        props.put("spring.datasource.dynamic.datasource.master.password", password);
        props.put("sw.security.jwt.secret", "sqlserver-smoke-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "sqlserver-smoke-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.knowledge.vector-store", "database");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class,
                com.sw.ck.bootstrap.config.FlywayConfiguration.class,
                com.sw.ck.bootstrap.compat.DatabaseCompatibilityGuard.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("sqlserver-smoke", props));
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("sqlserverSmokeLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api";
    }

    @AfterAll
    void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (masterUrl == null || Boolean.getBoolean("smoke.keep-db")) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(masterUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER DATABASE [" + DATABASE + "] SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
            statement.executeUpdate("DROP DATABASE [" + DATABASE + "]");
        }
    }

    @Test
    @DisplayName("空库启动：Flyway 30 条到 0.4.0，种子/中文/JSON/动态宽表/向量/主流程可用")
    void smoke() throws Exception {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isEqualTo(30);
        assertThat(jdbc.queryForObject(
                "SELECT TOP 1 version FROM flyway_schema_history WHERE success = 1 "
                        + "AND version IS NOT NULL ORDER BY installed_rank DESC", String.class))
                .isEqualTo("0.4.0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_tenant", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class)).isEqualTo(143);

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setTenantId(0L);
        LoginUserHolder.set(loginUser);
        try {
            NotifyMessageMapper notifyMapper = app.getBean(NotifyMessageMapper.class);
            NotifyMessage message = new NotifyMessage();
            message.setRecipientId(1L);
            message.setTitle("中文通知");
            message.setContent("通知内容");
            message.setBizType("SYSTEM");
            message.setBizId("sqlserver-smoke-" + System.nanoTime());
            message.setIsRead(false);
            message.setTenantId(0L);
            notifyMapper.insert(message);
            assertThat(notifyMapper.selectById(message.getId()).getIsRead()).isFalse();
            assertThat(app.getBean(ObjectMapper.class).writeValueAsString(
                    notifyMapper.selectById(message.getId()))).contains("\"read\"");

            BpmTaskOpinionDraftMapper draftMapper = app.getBean(BpmTaskOpinionDraftMapper.class);
            BpmTaskOpinionDraft draft = new BpmTaskOpinionDraft();
            draft.setTaskId("sqlserver-smoke-" + System.nanoTime());
            draft.setProcessInstanceId("sqlserver-smoke-pi");
            draft.setAssigneeId(1L);
            draft.setOpinionComment("中文意见");
            draft.setTenantId(0L);
            draftMapper.insert(draft);
            assertThat(draftMapper.selectById(draft.getId()).getOpinionComment()).isEqualTo("中文意见");
            assertThat(app.getBean(ObjectMapper.class).writeValueAsString(
                    draftMapper.selectById(draft.getId()))).contains("\"comment\"");

            SysWorkspaceCardTypeMapper cardTypeMapper = app.getBean(SysWorkspaceCardTypeMapper.class);
            SysWorkspaceCardType cardType = new SysWorkspaceCardType();
            cardType.setTypeCode("smoke_" + System.nanoTime());
            cardType.setDisplayName("冒烟卡片");
            cardType.setRendererKey("todo");
            cardType.setDefaultSpan(1);
            cardType.setDefaultOrder(1);
            cardType.setStatus(0);
            cardType.setTenantId(0L);
            cardTypeMapper.insert(cardType);
            assertThat(jdbc.queryForObject(
                    "SELECT metadata_json FROM sys_workspace_card_type WHERE id = ?",
                    String.class, cardType.getId())).isEqualTo("{}");

            KnowledgeVectorStore vectorStore = app.getBean(KnowledgeVectorStore.class);
            long kbId = 880001L;
            long documentId = 880002L;
            long chunkId = 880003L;
            jdbc.update("DELETE FROM sw_knowledge_vector WHERE chunk_id = ?", chunkId);
            jdbc.update("DELETE FROM sw_knowledge_chunk WHERE id = ?", chunkId);
            jdbc.update("DELETE FROM sw_knowledge_document WHERE id = ?", documentId);
            jdbc.update("INSERT INTO sw_knowledge_document (id, kb_id, name, status, chunk_count, "
                    + "tenant_id, deleted, version) VALUES (?, ?, '冒烟文档', 'READY', 1, 0, 0, 0)",
                    documentId, kbId);
            jdbc.update("INSERT INTO sw_knowledge_chunk (id, kb_id, document_id, chunk_index, content, "
                    + "tenant_id, deleted, version) VALUES (?, ?, ?, 0, '中文分片', 0, 0, 0)",
                    chunkId, kbId, documentId);
            vectorStore.store(chunkId, new float[]{1f, 0f, 0f});
            assertThat(vectorStore.search(kbId, new float[]{1f, 0f, 0f}, 5, 0.9)).hasSize(1);
            vectorStore.deleteByDocument(kbId, documentId);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sw_knowledge_vector WHERE chunk_id = ?", Integer.class, chunkId))
                    .isZero();
        } finally {
            LoginUserHolder.clear();
        }

        jdbc.update("INSERT INTO sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, "
                + "name, code, status) VALUES (990001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, "
                + "'中文校验', 'sqlserver_smoke_zh', 0)");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM sys_dict_type WHERE id = 990001", String.class)).isEqualTo("中文校验");

        jdbc.update("INSERT INTO sw_form_config (id, form_id, definition, tenant_id, deleted, "
                        + "create_time, update_time, version) VALUES (?, ?, ?, 0, 0, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                "smoke-config", "smoke-form", "{\"title\":\"中文表单\"}");

        String token = BusinessSmokeSupport.login(base, "admin", "AdminP@ssw0rd");
        BusinessSmokeSupport.assertMenus(base, token);
        String formId = BusinessSmokeSupport.publishForm(
                app, loginUser, "sqlserver_smoke_form_" + System.nanoTime());
        BusinessSmokeSupport.assertDynamicRecordRoundTrip(app, formId, 0L);
        BusinessSmokeSupport.runApproval(app);
        BusinessSmokeSupport.triggerQuartz(app);

        assertThat(jdbc.queryForObject(
                "SELECT CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation'))", String.class))
                .contains("_CS_");
    }

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
