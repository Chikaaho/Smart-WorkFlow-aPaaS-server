package com.sw.ck.bootstrap.multidb;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.bpm.process.entity.BpmTaskOpinionDraft;
import com.sw.ck.bpm.process.mapper.BpmTaskOpinionDraftMapper;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SysTenantMapper;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle 21c XE 启动冒烟（未配置 {@code oracle.url} 时跳过）。
 * <p>
 * 前置：先运行 {@link com.sw.ck.bootstrap.migration.FlywayFullChainOracleTest} 让目标 schema
 * 完成 30 条迁移。本测试启动完整应用，断言 Flyway 零新增、MyBatis、中文、CLOB、分页与
 * Flowable 建表可用。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Oracle 21c XE 启动冒烟（真实库；未配置 oracle.url 时跳过）")
class OracleBootSmokeTest {

    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;

    @BeforeAll
    void boot() {
        String url = System.getProperty("oracle.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 oracle.url，跳过 Oracle 启动冒烟");
        String user = System.getProperty("oracle.user", "swmdb");
        String password = System.getProperty("oracle.password", "");

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "oracle.jdbc.OracleDriver");
        props.put("spring.datasource.dynamic.datasource.master.url", url);
        props.put("spring.datasource.dynamic.datasource.master.username", user);
        props.put("spring.datasource.dynamic.datasource.master.password", password);
        props.put("sw.security.jwt.secret", "oracle-smoke-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "oracle-smoke-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.knowledge.vector-store", "database");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("oracle-smoke", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("oracleSmokeLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
    }

    @AfterAll
    void tearDown() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    @DisplayName("启动：Flyway 零新增到 0.4.0，MyBatis/中文/CLOB/分页/Flowable 可用")
    void smoke() throws Exception {
        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = 1", Integer.class);
        assertThat(migrations).isEqualTo(30);
        String version = jdbc.queryForObject(
                "SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" = 1 "
                        + "AND \"version\" IS NOT NULL ORDER BY \"installed_rank\" DESC "
                        + "FETCH FIRST 1 ROWS ONLY", String.class);
        assertThat(version).isEqualTo("0.4.0");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_tenant", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu", Integer.class)).isEqualTo(143);

        List<SysTenant> tenants;
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setTenantId(0L);
        LoginUserHolder.set(loginUser);
        try {
            tenants = app.getBean(SysTenantMapper.class).selectList(null);
        } finally {
            LoginUserHolder.clear();
        }
        assertThat(tenants).hasSize(4);

        LoginUserHolder.set(loginUser);
        try {
            NotifyMessageMapper notifyMapper = app.getBean(NotifyMessageMapper.class);
            NotifyMessage message = new NotifyMessage();
            message.setRecipientId(1L);
            message.setTitle("中文通知");
            message.setContent("通知内容");
            message.setBizType("SYSTEM");
            message.setBizId("1");
            message.setIsRead(false);
            message.setTenantId(0L);
            notifyMapper.insert(message);
            NotifyMessage loadedMessage = notifyMapper.selectById(message.getId());
            assertThat(loadedMessage.getIsRead()).isFalse();
            assertThat(app.getBean(ObjectMapper.class).writeValueAsString(loadedMessage))
                    .contains("\"read\"");

            BpmTaskOpinionDraftMapper draftMapper = app.getBean(BpmTaskOpinionDraftMapper.class);
            BpmTaskOpinionDraft draft = new BpmTaskOpinionDraft();
            draft.setTaskId("oracle-smoke-" + System.nanoTime());
            draft.setProcessInstanceId("oracle-smoke-pi");
            draft.setAssigneeId(1L);
            draft.setOpinionComment("中文意见");
            draft.setTenantId(0L);
            draftMapper.insert(draft);
            BpmTaskOpinionDraft loadedDraft = draftMapper.selectById(draft.getId());
            assertThat(loadedDraft.getOpinionComment()).isEqualTo("中文意见");
            assertThat(app.getBean(ObjectMapper.class).writeValueAsString(loadedDraft))
                    .contains("\"comment\"");
        } finally {
            LoginUserHolder.clear();
        }

        jdbc.update("DELETE FROM sys_dict_type WHERE id = 990001");
        jdbc.update("INSERT INTO sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, "
                + "name, code, status) VALUES (990001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, "
                + "'中文校验', 'oracle_smoke_zh', 0)");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM sys_dict_type WHERE id = 990001", String.class))
                .isEqualTo("中文校验");

        jdbc.update("DELETE FROM sw_form_config WHERE id = 'oracle-smoke-config'");
        jdbc.update("INSERT INTO sw_form_config (id, form_id, definition, tenant_id, deleted, "
                + "create_time, update_time, version) VALUES ('oracle-smoke-config', 'oracle-smoke-form', "
                + "'{\"title\":\"中文表单\"}', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
        assertThat(jdbc.queryForObject(
                "SELECT definition FROM sw_form_config WHERE id = 'oracle-smoke-config'", String.class))
                .contains("中文表单");

        List<Long> page2 = jdbc.queryForList(
                "SELECT id FROM sys_menu ORDER BY id OFFSET 2 ROWS FETCH NEXT 1 ROWS ONLY", Long.class);
        List<Long> all = jdbc.queryForList(
                "SELECT id FROM sys_menu ORDER BY id FETCH FIRST 3 ROWS ONLY", Long.class);
        assertThat(page2).containsExactly(all.get(2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACT_GE_PROPERTY", Integer.class))
                .isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT value FROM nls_database_parameters WHERE parameter = 'NLS_CHARACTERSET'",
                String.class)).isEqualTo("AL32UTF8");
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
