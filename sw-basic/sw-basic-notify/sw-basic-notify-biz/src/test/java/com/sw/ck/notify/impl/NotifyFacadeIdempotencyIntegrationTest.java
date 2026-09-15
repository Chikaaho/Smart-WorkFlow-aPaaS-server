package com.sw.ck.notify.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifySendResult;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 集成证据（验收修正轮 03）：抄送事件投递幂等——同一幂等键重复投递只落一条消息。
 * <p>
 * CopyNodeDelegate 对同一抄送事件以 {@code processInstance:node:recipient} 作为幂等键经
 * NotifyFacade 投递；本测试在真实 H2（含 uk_sw_notify_msg_idempotency 唯一键）上验证：
 * 重复投递不新增 sw_notify_message 行，且回放既有投递结果。
 * </p>
 */
@SpringBootTest(
        classes = NotifyFacadeIdempotencyIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=false", "sw.tenant.enabled=true"}
)
@DisplayName("Notify 幂等键重复投递集成（R2）")
class NotifyFacadeIdempotencyIntegrationTest {

    private static final String KEY = "flow-1:c1:42";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotifyFacade notifyFacade;

    @Autowired
    private TestLoginContext testLoginContext;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_notify_message (
                    id                bigint          not null primary key,
                    create_time       timestamp       not null default current_timestamp,
                    create_by         bigint,
                    update_time       timestamp       not null default current_timestamp,
                    update_by         bigint,
                    deleted           smallint        not null default 0,
                    tenant_id         bigint          not null default 0,
                    version           bigint          not null default 0,
                    recipient_id      bigint          not null,
                    title             varchar(200)    not null,
                    content           text            not null,
                    biz_type          varchar(30)     not null,
                    biz_id            varchar(64),
                    is_read           boolean         not null default false,
                    channel           varchar(40)     not null default 'IN_APP',
                    delivery_status   varchar(20)     not null default 'SUCCESS',
                    external_message_id varchar(200),
                    failure_reason    varchar(500),
                    idempotency_key   varchar(200)
                )
                """);

        // I6 收口新增列（IF NOT EXISTS 双方言兼容；存量 schema 与生产迁移同语义）
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS event_type varchar(40) not null default 'SYSTEM'"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS occurrence_no bigint not null default 1"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS template_id bigint"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS template_version int"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS link_type varchar(32)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS link_id varchar(64)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS retry_count int not null default 0"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS next_retry_time timestamp"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS failure_class varchar(40)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_message ADD COLUMN IF NOT EXISTS receipt_digest varchar(200)"); } catch (Exception ignored) { /* schema already migrated */ }
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_notify_msg_idempotency
                ON sw_notify_message (tenant_id, idempotency_key)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_notify_send_attempt (
                    id                  bigint          not null primary key,
                    create_time         timestamp       not null default current_timestamp,
                    create_by           bigint,
                    update_time         timestamp       not null default current_timestamp,
                    update_by           bigint,
                    deleted             smallint        not null default 0,
                    tenant_id           bigint          not null default 0,
                    version             bigint          not null default 0,
                    message_id          bigint          not null,
                    attempt_no          int             not null default 1,
                    channel             varchar(32),
                    status              varchar(20)     not null,
                    failure_reason      varchar(500),
                    external_message_id varchar(200)
                )
                """);
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_send_attempt ADD COLUMN IF NOT EXISTS failure_class varchar(40)"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_send_attempt ADD COLUMN IF NOT EXISTS started_at timestamp"); } catch (Exception ignored) { /* schema already migrated */ }
        try { jdbcTemplate.execute("ALTER TABLE sw_notify_send_attempt ADD COLUMN IF NOT EXISTS finished_at timestamp"); } catch (Exception ignored) { /* schema already migrated */ }
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM sw_notify_message");
        jdbcTemplate.update("DELETE FROM sw_notify_send_attempt");
        testLoginContext.set(0L, 1L);
    }

    private NotifySendRequest request(String key, long recipient) {
        return NotifySendRequest.builder()
                .recipientId(recipient)
                .title("R2 抄送去重")
                .content("请假流程抄送")
                .bizType(NotifyBizType.WF_TODO)
                .bizId("flow-1")
                .tenantId(0L)
                .channel(NotifyChannel.IN_APP)
                .idempotencyKey(key)
                .build();
    }

    @Test
    @DisplayName("同一幂等键重复投递 → 仅一条消息，回放既有结果")
    void duplicateDeliveryPersistsSingleRow() {
        NotifySendResult first = notifyFacade.send(request(KEY, 42L));
        NotifySendResult second = notifyFacade.send(request(KEY, 42L));
        NotifySendResult third = notifyFacade.send(request(KEY, 42L));

        assertThat(first.getStatus()).isEqualTo("SUCCESS");
        assertThat(second.getStatus()).isEqualTo("SUCCESS");
        assertThat(third.getStatus()).isEqualTo("SUCCESS");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message WHERE idempotency_key = ?", Integer.class, KEY);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("不同幂等键（不同事件/接收人）→ 各自落一条")
    void differentKeysPersistSeparately() {
        notifyFacade.send(request(KEY, 42L));
        notifyFacade.send(request("flow-1:c1:43", 43L));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_notify_message WHERE idempotency_key LIKE 'flow-1:c1:%'",
                Integer.class);
        assertThat(rows).isEqualTo(2);
    }

    /** 可编程 LoginContextProvider（同 NotifyMessageIntegrationTest 口径）。 */
    static class TestLoginContext implements LoginContextProvider {

        private volatile Long currentUserId;
        private volatile Long currentTenantId;

        void set(Long tenantId, Long userId) {
            this.currentTenantId = tenantId;
            this.currentUserId = userId;
        }

        @Override
        public Long getUserId() { return currentUserId; }

        @Override
        public Long getTenantId() { return currentTenantId; }

        @Override
        public Long getDeptId() { return null; }

        @Override
        public DataScopeType getDataScopeType() { return DataScopeType.ALL; }

        @Override
        public Set<Long> getCustomDeptIds() { return Set.of(); }

        @Override
        public boolean isSuperAdmin() { return false; }
    }

    @Configuration
    @MapperScan("com.sw.ck.notify.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:notifyidem;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TestLoginContext testLoginContext() {
            return new TestLoginContext();
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public TenantProperties tenantProperties() {
            return new TenantProperties();
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                TenantProperties tenantProperties,
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(
                TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.notify.entity");
            MybatisConfiguration ibatisConfig = new MybatisConfiguration();
            ibatisConfig.setMapUnderscoreToCamelCase(true);
            ibatisConfig.setUseGeneratedKeys(true);
            factory.setConfiguration(ibatisConfig);
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            factory.setGlobalConfig(globalConfig);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public com.sw.ck.notify.render.TemplateRenderService templateRenderService() {
            return new com.sw.ck.notify.render.TemplateRenderService();
        }

        /** 模板服务仅作依赖占位：本测试走直连内容模式，不经模板渲染路径。 */
        @Bean
        public com.sw.ck.notify.service.NotifyTemplateService notifyTemplateService() {
            return org.mockito.Mockito.mock(com.sw.ck.notify.service.NotifyTemplateService.class);
        }

        @Bean
        public NotifyMessageService notifyMessageService(
                com.sw.ck.notify.service.NotifyTemplateService notifyTemplateService,
                com.sw.ck.notify.render.TemplateRenderService templateRenderService,
                LoginContextProvider loginContextProvider) {
            return new NotifyMessageServiceImpl(notifyTemplateService, templateRenderService, loginContextProvider);
        }

        @Bean
        public NotifyFacade notifyFacade(NotifyMessageService notifyMessageService) {
            return new NotifyFacadeImpl(notifyMessageService);
        }
    }
}
