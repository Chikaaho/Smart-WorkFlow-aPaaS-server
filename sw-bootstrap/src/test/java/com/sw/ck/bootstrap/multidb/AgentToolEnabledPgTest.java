package com.sw.ck.bootstrap.multidb;

import com.sw.ck.agent.dto.AgentToolExternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolExternalConfigQuery;
import com.sw.ck.agent.dto.AgentToolInternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolInternalConfigQuery;
import com.sw.ck.agent.integration.AgentExternalCapabilityFacade;
import com.sw.ck.common.integration.ExternalCapability;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.service.AgentGraphDebugService;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.agent.service.AgentToolConfigService;
import com.sw.ck.bootstrap.i5.ProdBootTestApplication;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X1：真实 PostgreSQL 上智能体工具 enabled 查询的 Boolean 绑定。
 *
 * <p>覆盖 P66 12 处查询：工具列表启用过滤、图调试/执行的工具白名单、LLM 工具回调、
 * 服务层 listEnabled 与表单外部能力门面。使用 zonky EmbeddedPostgres 启动真实
 * PostgreSQL 二进制，完整跑 Flyway 迁移链，验证 V0.2.3 后的 enabled 最终类型为
 * BOOLEAN，Boolean 参数可执行。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("X1 真实 PostgreSQL：智能体工具 enabled Boolean 绑定 12 处查询")
class AgentToolEnabledPgTest {

    private static final long TENANT = 0L;
    private static final long INTERNAL_ENABLED_ID = 990001L;
    private static final long INTERNAL_DISABLED_ID = 990002L;
    private static final long EXTERNAL_ENABLED_ID = 990003L;

    private EmbeddedPostgres pg;
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;

    @BeforeAll
    void boot() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        String pgUrl = "jdbc:postgresql://127.0.0.1:" + pg.getPort() + "/postgres?stringtype=unspecified";

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.profiles.active", "dev");
        props.put("ch.dev.test-mock", "true");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("spring.datasource.dynamic.datasource.master.driver-class-name", "org.postgresql.Driver");
        props.put("spring.datasource.dynamic.datasource.master.url", pgUrl);
        props.put("spring.datasource.dynamic.datasource.master.username", "postgres");
        props.put("spring.datasource.dynamic.datasource.master.password", "postgres");
        props.put("spring.datasource.dynamic.druid.initial-size", "2");
        props.put("spring.datasource.dynamic.druid.min-idle", "1");
        props.put("spring.datasource.dynamic.druid.max-active", "4");
        props.put("sw.security.jwt.secret", "p66-x1-jwt-secret-0123456789abcdef0123456789abcdef");
        props.put("sw.security.login.rsa-private-key", generatedRsaPkcs8Base64());
        props.put("sw.security.login.digest-secret", "p66-x1-digest-secret");
        props.put("sw.security.sso.cipher-key", Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.agent.cipher-key", Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.external-datasource.cipher-key", Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.iot.cipher.cipher-key", Base64.getEncoder().encodeToString(new byte[32]));
        props.put("sw.knowledge.vector-store", "database");

        app = new SpringApplicationBuilder(ProdBootTestApplication.class)
                .initializers(context -> {
                    context.getEnvironment().setActiveProfiles("dev");
                    context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("p66-x1", props));
                    context.getEnvironment().getSystemProperties()
                            .put("spring.main.allow-bean-definition-overriding", "true");
                    org.springframework.beans.factory.support.RootBeanDefinition provider =
                            new org.springframework.beans.factory.support.RootBeanDefinition(
                                    com.sw.ck.security.support.SecurityLoginContextProvider.class);
                    provider.setPrimary(true);
                    ((org.springframework.beans.factory.support.DefaultListableBeanFactory) context.getBeanFactory())
                            .registerBeanDefinition("p66X1LoginContextProvider", provider);
                })
                .run();
        jdbc = app.getBean(JdbcTemplate.class);

        assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'sw_agent_tool_internal' AND column_name = 'enabled'",
                String.class)).isEqualTo("boolean");
        assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'sw_agent_tool_external' AND column_name = 'enabled'",
                String.class)).isEqualTo("boolean");

        jdbc.update("INSERT INTO sw_agent_tool_internal "
                        + "(id, name, description, bean_name, method_name, enabled, deleted, tenant_id, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                INTERNAL_ENABLED_ID, "p66_enabled_probe", "probe", "agentProcessStartTool", "execute",
                true, 0, TENANT, 0L);
        jdbc.update("INSERT INTO sw_agent_tool_internal "
                        + "(id, name, description, bean_name, method_name, enabled, deleted, tenant_id, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                INTERNAL_DISABLED_ID, "p66_disabled_probe", "probe", "agentProcessStartTool", "execute",
                false, 0, TENANT, 0L);
        jdbc.update("INSERT INTO sw_agent_tool_external "
                        + "(id, name, description, url, http_method, timeout_seconds, enabled, effect_scope, "
                        + "deleted, tenant_id, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                EXTERNAL_ENABLED_ID, "p66_ext_probe", "probe", "http://127.0.0.1:1/probe", "GET", 1,
                true, "QUERY", 0, TENANT, 0L);
    }

    @AfterAll
    void close() {
        if (app != null) {
            app.close();
        }
        if (pg != null) {
            try {
                pg.close();
            } catch (Exception ignored) {
                // 测试资源清理失败不掩盖断言结果
            }
        }
    }

    @Test
    @DisplayName("12 处 enabled 查询在真实 PG 的 BOOLEAN 列上全部可执行")
    void allTwelveQueriesBindBooleanAgainstRealPg() {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setUsername("admin");
        loginUser.setTenantId(TENANT);
        loginUser.setSuperAdmin(true);
        LoginUserHolder.set(loginUser);
        try {
            AgentToolConfigService toolService = app.getBean(AgentToolConfigService.class);

            // 11/12：管理端列表启用过滤（动态 query.getEnabled()）
            AgentToolInternalConfigQuery internalQuery = new AgentToolInternalConfigQuery();
            internalQuery.setPageNum(1);
            internalQuery.setPageSize(10);
            internalQuery.setEnabled(true);
            PageResult<AgentToolInternalConfigDTO> internalPage = toolService.pageInternalTools(internalQuery);
            assertThat(internalPage.getRecords())
                    .extracting(AgentToolInternalConfigDTO::getName)
                    .contains("p66_enabled_probe")
                    .doesNotContain("p66_disabled_probe");

            AgentToolExternalConfigQuery externalQuery = new AgentToolExternalConfigQuery();
            externalQuery.setPageNum(1);
            externalQuery.setPageSize(10);
            externalQuery.setEnabled(true);
            PageResult<AgentToolExternalConfigDTO> externalPage = toolService.pageExternalTools(externalQuery);
            assertThat(externalPage.getRecords())
                    .extracting(AgentToolExternalConfigDTO::getName)
                    .contains("p66_ext_probe");

            // 9/10：服务层 listEnabled
            assertThat(toolService.listEnabledInternalTools(TENANT))
                    .extracting(com.sw.ck.agent.entity.tool.AgentToolInternalConfig::getName)
                    .contains("p66_enabled_probe")
                    .doesNotContain("p66_disabled_probe");
            assertThat(toolService.listEnabledExternalTools(TENANT))
                    .extracting(com.sw.ck.agent.entity.tool.AgentToolExternalConfig::getName)
                    .contains("p66_ext_probe");

            // 1/2：表单外部能力门面 listEnabled / findEnabled（requireConfig）
            AgentExternalCapabilityFacade facade = app.getBean(AgentExternalCapabilityFacade.class);
            List<ExternalCapability> capabilities = facade.listEnabled(TENANT);
            assertThat(capabilities).extracting(ExternalCapability::name).contains("p66_ext_probe");
            assertThat(facade.findEnabled(TENANT, "p66_ext_probe")).isNotNull();

            // 3/4：LLM 工具回调构建（internal + external 两表）
            AgentToolCallbackFactory callbackFactory = app.getBean(AgentToolCallbackFactory.class);
            assertThat(callbackFactory.buildToolCallbacks(TENANT))
                    .extracting(handle -> handle.specification().name())
                    .contains("p66_enabled_probe", "p66_ext_probe");

            // 5/6：图调试工具白名单（private toolExists）
            Object debugTarget = AopTestUtils.getUltimateTargetObject(app.getBean(AgentGraphDebugService.class));
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(debugTarget, "toolExists", "p66_enabled_probe"))
                    .isTrue();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(debugTarget, "toolExists", "p66_ext_probe"))
                    .isTrue();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(debugTarget, "toolExists", "p66_missing_probe"))
                    .isFalse();

            // 7/8：图执行工具白名单（private toolExists）
            Object executionTarget = AopTestUtils.getUltimateTargetObject(
                    app.getBean(AgentGraphExecutionService.class));
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(executionTarget, "toolExists", "p66_enabled_probe"))
                    .isTrue();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(executionTarget, "toolExists", "p66_ext_probe"))
                    .isTrue();
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(executionTarget, "toolExists", "p66_missing_probe"))
                    .isFalse();
        } finally {
            LoginUserHolder.clear();
        }
    }

    private static String generatedRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
