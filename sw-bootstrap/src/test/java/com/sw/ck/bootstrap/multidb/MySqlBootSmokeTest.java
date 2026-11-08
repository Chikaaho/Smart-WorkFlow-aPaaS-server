package com.sw.ck.bootstrap.multidb;

import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.entity.SysTenant;
import com.sw.ck.system.mapper.SysTenantMapper;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 5.7 空库启动冒烟（未配置 {@code mysql.url} 时跳过）。
 * <p>
 * 真实 MySQL 容器 + 空库启动完整应用：Flyway 迁移 30 条到 V0.4.0、兼容性检查通过、
 * MyBatis 查询、中文写入回读、JSON 列、分页第二页、Flowable 引擎建表。
 * 测试使用独立临时库 {@code swmdb_smoke}，结束后删除。
 * </p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("MySQL 5.7 空库启动冒烟（真实库；未配置 mysql.url 时跳过）")
class MySqlBootSmokeTest {

    private static final String DATABASE = "swmdb_smoke";
    private static final Pattern JDBC_URL = Pattern.compile("^(jdbc:mysql://[^/]+)/([^?]*)(\\?.*)?$");

    private String serverUrl;
    private String targetUrl;
    private String user;
    private String password;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;

    @BeforeAll
    void boot() throws Exception {
        String url = System.getProperty("mysql.url");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "未配置 mysql.url，跳过 MySQL 启动冒烟");
        user = System.getProperty("mysql.user", "root");
        password = System.getProperty("mysql.password", "");
        Matcher matcher = JDBC_URL.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("mysql.url 应形如 jdbc:mysql://host:port/db: " + url);
        }
        String params = matcher.group(3) == null ? "" : matcher.group(3);
        String separator = params.isEmpty() ? "?" : params.endsWith("&") ? "" : "&";
        String sessionParam = params.contains("sessionVariables=") ? ""
                : separator + "sessionVariables=explicit_defaults_for_timestamp=ON";
        serverUrl = matcher.group(1) + "/mysql" + params + sessionParam;
        targetUrl = matcher.group(1) + "/" + DATABASE + params + sessionParam;

        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
            statement.executeUpdate("CREATE DATABASE " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "com.mysql.cj.jdbc.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", targetUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", user);
        props.put("spring.datasource.dynamic.datasource.master.password", password);
        props.put("sw.security.jwt.secret", "mysql-smoke-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "mysql-smoke-digest-secret");
        props.put("sw.security.sso.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.knowledge.vector-store", "database");
        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("mysql-smoke", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("mysqlSmokeLoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (app != null) {
            app.close();
        }
        if (serverUrl == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + DATABASE);
        }
    }

    @Test
    @DisplayName("空库启动：Flyway 30 条到 V0.4.0，MyBatis/中文/JSON/分页/Flowable 可用")
    void smoke() {
        Integer migrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(migrations).isEqualTo(30);
        String version = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class);
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

        jdbc.update("INSERT INTO sys_dict_type (id, create_time, update_time, deleted, tenant_id, version, "
                + "name, code, status) VALUES (990001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, "
                + "'中文校验', 'mysql_smoke_zh', 0)");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM sys_dict_type WHERE id = 990001", String.class))
                .isEqualTo("中文校验");

        jdbc.update("INSERT INTO sw_form_config (id, form_id, definition, tenant_id, deleted, "
                + "create_time, update_time, version) VALUES ('smoke-config', 'smoke-form', "
                + "CAST('{\"title\":\"中文表单\"}' AS JSON), 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
        assertThat(jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(definition, '$.title')) FROM sw_form_config "
                        + "WHERE id = 'smoke-config'", String.class)).isEqualTo("中文表单");

        List<Long> page2 = jdbc.queryForList(
                "SELECT id FROM sys_menu ORDER BY id LIMIT 1 OFFSET 2", Long.class);
        List<Long> all = jdbc.queryForList("SELECT id FROM sys_menu ORDER BY id LIMIT 3", Long.class);
        assertThat(page2).containsExactly(all.get(2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ACT_GE_PROPERTY", Integer.class))
                .isGreaterThan(0);
        assertThat(jdbc.queryForObject(
                "SELECT @@character_set_database", String.class)).isEqualTo("utf8mb4");
        assertThat(jdbc.queryForObject(
                "SELECT @@collation_database", String.class)).isEqualTo("utf8mb4_bin");
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
