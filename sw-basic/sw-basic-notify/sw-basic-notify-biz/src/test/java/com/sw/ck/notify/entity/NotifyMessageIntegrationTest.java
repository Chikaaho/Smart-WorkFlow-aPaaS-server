package com.sw.ck.notify.entity;

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
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.api.NotifyBizType;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.SendNotifyCommand;
import com.sw.ck.notify.dto.NotifyTemplateDTO;
import com.sw.ck.notify.dto.NotifyTemplateQuery;
import com.sw.ck.notify.dto.TemplatePreviewRequest;
import com.sw.ck.notify.dto.TemplatePreviewResult;
import com.sw.ck.notify.entity.NotifyTemplate;
import com.sw.ck.notify.impl.NotifyFacadeImpl;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.render.TemplateRenderService;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyTemplateService;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M05 Step 1：验证 sw_notify_message 表 + 实体/Mapper/Service/Facade 的基础功能。
 * <p>
 * 验证范围：
 * <ul>
 *   <li>BaseEntity 继承 + 拦截器自动注入 tenant_id/审计列/deleted/version</li>
 *   <li>is_read 默认 false、biz_type/biz_id/recipient 正确落库</li>
 *   <li>按 recipient 查询只返回对应行</li>
 * </ul>
 * </p>
 */
@SpringBootTest(
        classes = NotifyMessageIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=false",
                "sw.tenant.enabled=true"
        }
)
@DisplayName("Notify 通知表 + 数据访问层验证")
class NotifyMessageIntegrationTest {

    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;
    private static final Long RECIPIENT_10 = 10L;
    private static final Long RECIPIENT_20 = 20L;
    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final String TITLE = "审批待办";
    private static final String CONTENT = "您有一个审批任务待处理";
    private static final String BIZ_ID = "task_abc123";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NotifyFacade notifyFacade;

    @Autowired
    private NotifyMessageService notifyMessageService;

    @Autowired
    private TestLoginContext testLoginContext;

    // ==================== 表创建 ====================

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
        // 索引无需手动创建（Flyway 脚本中已有），测试验证不依赖索引
    }

    @BeforeEach
    void setUp() {
        cleanUp();
        testLoginContext.set(TENANT_100, USER_A);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM sw_notify_message");
    }

    // ==================== 测试 1：自动注入地基契约 + is_read 默认值 ====================

    @Test
    @DisplayName("send(只填业务列) → 查回确认基列被拦截器自动注入，is_read==false")
    void send_shouldAutoInjectBaseColumns() {
        // —— Arrange ——
        SendNotifyCommand cmd = new SendNotifyCommand(
                RECIPIENT_10, TITLE, CONTENT, NotifyBizType.WF_TODO, BIZ_ID, TENANT_100);

        // —— Act ——
        notifyFacade.send(cmd);

        // —— Assert：查最近一条 ——
        List<NotifyMessage> all = jdbcTemplate.query(
                "SELECT * FROM sw_notify_message ORDER BY id DESC",
                (rs, rowNum) -> {
                    NotifyMessage m = new NotifyMessage();
                    m.setId(rs.getLong("id"));
                    m.setTenantId(rs.getLong("tenant_id"));
                    m.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
                    m.setCreateBy(rs.getLong("create_by"));
                    m.setDeleted(rs.getInt("deleted"));
                    m.setVersion(rs.getLong("version"));
                    m.setRecipientId(rs.getLong("recipient_id"));
                    m.setTitle(rs.getString("title"));
                    m.setContent(rs.getString("content"));
                    m.setBizType(rs.getString("biz_type"));
                    m.setBizId(rs.getString("biz_id"));
                    m.setRead(rs.getBoolean("is_read"));
                    return m;
                });
        assertThat(all).as("应至少有一条记录").isNotEmpty();
        NotifyMessage found = all.get(0);

        // 基列自动注入验证
        assertThat(found.getTenantId()).as("tenant_id 应被自动注入").isEqualTo(TENANT_100);
        assertThat(found.getCreateTime()).as("createTime 应被自动注入").isNotNull();
        assertThat(found.getCreateBy()).as("createBy 应被自动注入").isEqualTo(USER_A);
        assertThat(found.getDeleted()).as("deleted 应被注入为 0").isZero();
        assertThat(found.getVersion()).as("version 应被注入为 0").isZero();

        // 业务列正确
        assertThat(found.getRecipientId()).isEqualTo(RECIPIENT_10);
        assertThat(found.getTitle()).isEqualTo(TITLE);
        assertThat(found.getContent()).isEqualTo(CONTENT);
        assertThat(found.getBizType()).isEqualTo(NotifyBizType.WF_TODO.name());
        assertThat(found.getBizId()).isEqualTo(BIZ_ID);

        // is_read 默认 false
        assertThat(found.getRead()).as("is_read 应默认为 false").isFalse();

        System.out.println("=== send 自动注入 + is_read 验证 ===");
        System.out.println("  id=" + found.getId() + ", tenantId=" + found.getTenantId()
                + ", createBy=" + found.getCreateBy() + ", isRead=" + found.getRead()
                + ", deleted=" + found.getDeleted() + ", version=" + found.getVersion()
                + ", bizType=" + found.getBizType() + ", bizId=" + found.getBizId() + " ✓");
    }

    // ==================== 测试 2：按 recipient 查询 ====================

    @Test
    @DisplayName("插两个不同 recipient → findByRecipient 只得对应行")
    void findByRecipient_shouldFilterCorrectly() {
        // —— Arrange ——
        testLoginContext.set(TENANT_100, USER_A);
        notifyFacade.send(new SendNotifyCommand(
                RECIPIENT_10, TITLE, CONTENT, NotifyBizType.WF_TODO, BIZ_ID, TENANT_100));
        notifyFacade.send(new SendNotifyCommand(
                RECIPIENT_20, TITLE, CONTENT, NotifyBizType.WF_APPROVED, "pi_002", TENANT_100));

        // —— Act ——
        List<NotifyMessage> results = notifyMessageService.findByRecipient(RECIPIENT_10);

        // —— Assert ——
        assertThat(results)
                .as("recipient_10 应查到一条通知")
                .hasSize(1);
        assertThat(results.get(0).getRecipientId()).isEqualTo(RECIPIENT_10);
        assertThat(results.get(0).getBizType()).isEqualTo(NotifyBizType.WF_TODO.name());

        System.out.println("=== findByRecipient 验证 ===");
        System.out.println("  recipientId=" + RECIPIENT_10 + " → count=" + results.size() + " ✓");
    }

    // ==================== 测试 3：租户隔离 ====================

    @Test
    @DisplayName("不同租户插同 recipient 通知 → 查询只返回当前租户那条")
    void tenantIsolation_shouldSeparateMessages() {
        // —— Arrange：TENANT_100 发一条 ——
        testLoginContext.set(TENANT_100, USER_A);
        notifyFacade.send(new SendNotifyCommand(
                RECIPIENT_10, TITLE, "TENANT_100 通知", NotifyBizType.WF_TODO, BIZ_ID, TENANT_100));

        // TENANT_200 发一条（同 recipient）
        testLoginContext.set(TENANT_200, USER_B);
        notifyFacade.send(new SendNotifyCommand(
                RECIPIENT_10, TITLE, "TENANT_200 通知", NotifyBizType.WF_APPROVED, "pi_200", TENANT_200));

        // —— Act & Assert：TENANT_100 上下文查询 ——
        testLoginContext.set(TENANT_100, USER_A);
        List<NotifyMessage> results100 = notifyMessageService.findByRecipient(RECIPIENT_10);
        assertThat(results100)
                .as("TENANT_100 应查到自己的 1 条通知")
                .hasSize(1);
        assertThat(results100.get(0).getContent())
                .as("应返回 TENANT_100 的内容")
                .contains("TENANT_100");
        assertThat(results100.get(0).getTenantId())
                .as("tenant_id 应为 100")
                .isEqualTo(TENANT_100);

        // —— Act & Assert：TENANT_200 上下文查询 ——
        testLoginContext.set(TENANT_200, USER_B);
        List<NotifyMessage> results200 = notifyMessageService.findByRecipient(RECIPIENT_10);
        assertThat(results200)
                .as("TENANT_200 应查到自己的 1 条通知")
                .hasSize(1);
        assertThat(results200.get(0).getContent())
                .as("应返回 TENANT_200 的内容")
                .contains("TENANT_200");
        assertThat(results200.get(0).getTenantId())
                .as("tenant_id 应为 200")
                .isEqualTo(TENANT_200);

        System.out.println("=== 租户隔离验证 ===");
        System.out.println("  TENANT_100: recipient=" + RECIPIENT_10 + " → count=" + results100.size() + " ✓");
        System.out.println("  TENANT_200: recipient=" + RECIPIENT_10 + " → count=" + results200.size() + " ✓");
    }

    // ==================== 测试上下文配置 ====================

    /**
     * 可编程的 LoginContextProvider，通过 {@link #set(Long, Long)} 切换当前用户/租户，
     * 让 MyBatis-Plus 拦截器（自动填充 + 租户行级隔离）在测试中真实生效。
     */
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
        public DataScopeType getDataScopeType() {
            return DataScopeType.ALL;
        }

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
                    .url("jdbc:h2:mem:notifymsg;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public TemplateRenderService templateRenderService() {
            return new TemplateRenderService();
        }

        @Bean
        public NotifyTemplateService notifyTemplateService() {
            return new NotifyTemplateService() {
                @Override
                public PageResult<NotifyTemplateDTO> pageTemplates(NotifyTemplateQuery query) { return null; }
                @Override
                public NotifyTemplateDTO getTemplate(Long id) { return null; }
                @Override
                public Long createTemplate(NotifyTemplateDTO dto) { return null; }
                @Override
                public void updateTemplate(Long id, NotifyTemplateDTO dto) {}
                @Override
                public void deleteTemplate(Long id) {}
                @Override
                public void toggleTemplate(Long id, boolean enabled) {}
                @Override
                public TemplatePreviewResult renderPreview(TemplatePreviewRequest request) { return null; }
                @Override
                public TemplatePreviewResult previewByCode(String templateCode, java.util.Map<String, String> variables) { return null; }
                @Override
                public java.util.Set<String> extractVariables(String titleTemplate, String contentTemplate) { return java.util.Set.of(); }
                @Override
                public NotifyTemplate getEnabledByCode(String code) { return null; }
            };
        }

        @Bean
        public NotifyMessageService notifyMessageService(NotifyTemplateService notifyTemplateService,
                                                         TemplateRenderService templateRenderService,
                                                         LoginContextProvider loginContextProvider) {
            return new NotifyMessageServiceImpl(notifyTemplateService, templateRenderService, loginContextProvider);
        }

        @Bean
        public NotifyFacade notifyFacade(NotifyMessageService notifyMessageService) {
            return new NotifyFacadeImpl(notifyMessageService);
        }
    }
}
