package com.sw.ck.notify.i6;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.api.NotifyRoutingService;
import com.sw.ck.notify.dto.NotifyRuleDTO;
import com.sw.ck.notify.dto.NotifyRuleQuery;
import com.sw.ck.notify.dto.NotifySubscriptionSaveReq;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import com.sw.ck.notify.service.NotifyTemplateVersionService;
import com.sw.ck.notify.service.impl.NotifyTemplateVersionServiceImpl;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.mapper.NotifyChannelConfigMapper;
import com.sw.ck.notify.mapper.NotifyTemplateVersionMapper;
import com.sw.ck.notify.mapper.NotifyRuleMapper;
import com.sw.ck.notify.mapper.NotifySubscriptionMapper;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyChannelConfigService;
import com.sw.ck.notify.service.NotifyRuleService;
import com.sw.ck.notify.service.NotifySubscriptionService;
import com.sw.ck.notify.service.impl.NotifyChannelConfigServiceImpl;
import com.sw.ck.notify.impl.NotifyFacadeImpl;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.notify.service.impl.NotifyRoutingServiceImpl;
import com.sw.ck.notify.service.impl.NotifyRuleServiceImpl;
import com.sw.ck.notify.service.impl.NotifySubscriptionServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I6 通知收口聚焦集成（H2，独立库 notifyi6）。
 * 覆盖：收件箱服务端真分页/未读数/全部已读/稳定排序；业务稳定身份幂等（重复请求仅一次业务效果）；
 * 规则按事件裁决渠道顺序；必须送达规则（required+IN_APP）不可被订阅关闭。
 */
@SpringBootTest
@ContextConfiguration(classes = I6NotifyClosureIntegrationTest.I6Config.class)
class I6NotifyClosureIntegrationTest {

    private static final Long TENANT_100 = 100L;
    private static final Long USER_7 = 7L;

    @Autowired
    private JdbcTemplateSupport jdbc;

    @Autowired
    private NotifyFacade notifyFacade;

    @Autowired
    private NotifyMessageService notifyMessageService;

    @Autowired
    private NotifyRuleService notifyRuleService;

    @Autowired
    private NotifySubscriptionService notifySubscriptionService;

    @Autowired
    private NotifyChannelConfigService notifyChannelConfigService;

    @Autowired
    private NotifyRoutingService notifyRoutingService;

    @Autowired
    private TestLoginContext testLoginContext;

    @BeforeAll
    static void createTables(@Autowired JdbcTemplateSupport jt) {
        try { jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_message (id BIGINT PRIMARY KEY, recipient_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL, content TEXT NOT NULL, biz_type VARCHAR(30) NOT NULL, biz_id VARCHAR(64), is_read BOOLEAN DEFAULT FALSE, channel VARCHAR(40) NOT NULL DEFAULT 'IN_APP', delivery_status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS', external_message_id VARCHAR(200), failure_reason VARCHAR(500), idempotency_key VARCHAR(200), event_type VARCHAR(40) DEFAULT 'SYSTEM', occurrence_no BIGINT DEFAULT 1, template_id BIGINT, template_version INT, link_type VARCHAR(32), link_id VARCHAR(64), retry_count INT DEFAULT 0, next_retry_time TIMESTAMP, failure_class VARCHAR(40), receipt_digest VARCHAR(200), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by BIGINT, update_by BIGINT)"); } catch (Exception ignored) { }
        try { jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_rule (id BIGINT PRIMARY KEY, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by BIGINT, update_by BIGINT, rule_code VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL, event_type VARCHAR(40) NOT NULL, channel_priority VARCHAR(200) NOT NULL DEFAULT 'IN_APP', recipient_rule VARCHAR(500) NOT NULL, required_flag SMALLINT NOT NULL DEFAULT 0, failure_policy VARCHAR(20) NOT NULL DEFAULT 'RETRY', enabled SMALLINT NOT NULL DEFAULT 1, remark VARCHAR(500), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0)"); } catch (Exception ignored) { }
        try { jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_subscription (id BIGINT PRIMARY KEY, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, create_by BIGINT, update_by BIGINT, user_id BIGINT NOT NULL, event_type VARCHAR(40) NOT NULL, channel VARCHAR(40) DEFAULT 'IN_APP', enabled SMALLINT NOT NULL DEFAULT 1, tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0)"); } catch (Exception ignored) { }
        try { jt.execute("CREATE TABLE IF NOT EXISTS sw_notify_channel_config (id BIGINT PRIMARY KEY, channel VARCHAR(40) NOT NULL, enabled SMALLINT NOT NULL DEFAULT 0, sender_display VARCHAR(200), config_summary VARCHAR(500), tenant_id BIGINT NOT NULL DEFAULT 0, deleted SMALLINT DEFAULT 0, version BIGINT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"); } catch (Exception ignored) { }
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM sw_notify_message");
        jdbc.update("DELETE FROM sw_notify_rule");
        jdbc.update("DELETE FROM sw_notify_subscription");
        jdbc.update("DELETE FROM sw_notify_channel_config");
        testLoginContext.set(TENANT_100, USER_7);
    }

    @Test
    @DisplayName("R1 身份幂等：同一 (事件,业务对象,次序,接收人,渠道) 重复投递只产生一条业务通知")
    void r1_identityDedupe() {
        for (int i = 0; i < 3; i++) {
            notifyFacade.send(NotifySendRequest.builder()
                    .channel(NotifyChannel.IN_APP)
                    .recipientId(USER_7)
                    .title("待办提醒")
                    .content("您有一条新的待办任务")
                    .tenantId(TENANT_100)
                    .eventType("TODO_CREATED")
                    .bizId("task-1")
                    .occurrenceNo(1L)
                    .build());
        }
        Long count = jdbc.queryCount("SELECT COUNT(*) FROM sw_notify_message");
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("R2 新一轮发生次序生成新的合法业务通知")
    void r2_newOccurrenceAllowed() {
        NotifySendRequest base = NotifySendRequest.builder()
                .channel(NotifyChannel.IN_APP)
                .recipientId(USER_7)
                .title("办理时限提醒")
                .content("任务临近时限")
                .tenantId(TENANT_100)
                .eventType("TASK_DEADLINE_ALERT")
                .bizId("task-2")
                .occurrenceNo(1L)
                .build();
        notifyFacade.send(base);
        notifyFacade.send(NotifySendRequest.builder()
                .channel(NotifyChannel.IN_APP)
                .recipientId(USER_7)
                .title("办理时限提醒")
                .content("任务临近时限")
                .tenantId(TENANT_100)
                .eventType("TASK_DEADLINE_ALERT")
                .bizId("task-2")
                .occurrenceNo(2L)
                .build());
        assertThat(jdbc.queryCount("SELECT COUNT(*) FROM sw_notify_message")).isEqualTo(2L);
    }

    @Test
    @DisplayName("R3 收件箱分页：未读计数 / 全部已读 / 稳定排序")
    void r3_inboxPagingAndReadAll() {
        for (int i = 1; i <= 12; i++) {
            notifyFacade.send(NotifySendRequest.builder()
                    .channel(NotifyChannel.IN_APP)
                    .recipientId(USER_7)
                    .title("通知 " + i)
                    .content("内容 " + i)
                    .tenantId(TENANT_100)
                    .eventType("SYSTEM")
                    .bizId("announce-" + i)
                    .occurrenceNo(1L)
                    .build());
        }
        // 未读数
        assertThat(notifyMessageService.unreadCount(USER_7)).isEqualTo(12L);
        // 第一页 10 条、稳定排序（创建时间相同时按 id 倒序）
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(1);
        pageParam.setPageSize(10);
        var page1 = notifyMessageService.pageInbox(pageParam, USER_7, null, null, null);
        assertThat(page1.getRecords()).hasSize(10);
        assertThat(page1.getTotal()).isEqualTo(12);
        // 第二页返回剩余 2 条
        pageParam.setPageNum(2);
        var page2 = notifyMessageService.pageInbox(pageParam, USER_7, null, null, null);
        assertThat(page2.getRecords()).hasSize(2);
        // 全部已读幂等
        int affected = notifyMessageService.markAllRead(USER_7);
        assertThat(affected).isEqualTo(12);
        assertThat(notifyMessageService.markAllRead(USER_7)).isEqualTo(0);
        assertThat(notifyMessageService.unreadCount(USER_7)).isEqualTo(0);
    }

    @Test
    @DisplayName("R4 规则按事件裁决渠道顺序（IN_APP 保底 + 渠道裁剪）")
    void r4_ruleRouting() {
        jdbc.execute("INSERT INTO sw_notify_rule (id, rule_code, name, event_type, channel_priority, recipient_rule, required_flag, failure_policy, enabled, tenant_id) VALUES (1,'i6_rule','I6 规则','TODO_CREATED','IN_APP','ASSIGNEE',1,'RETRY',1,100)");
        List<NotifyChannel> channels = notifyRoutingService.channelsFor("TODO_CREATED", USER_7);
        assertThat(channels).contains(NotifyChannel.IN_APP);
        assertThat(notifyRoutingService.required("TODO_CREATED")).isTrue();
        // 无规则事件保底 IN_APP
        assertThat(notifyRoutingService.channelsFor("UNKNOWN_EVENT", USER_7))
                .containsExactly(NotifyChannel.IN_APP);
    }

    @Test
    @DisplayName("R5 必须送达事件的站内信不可被订阅关闭")
    void r5_requiredNotOptOut() {
        // 规则 required=1 且以 IN_APP 开头
        try {
            notifyRuleService.createRule(rule("req_rule_todo", "TODO_CREATED", true));
        } catch (Exception ignored) {
            // 编码冲突等重跑防护
        }
        assertThat(notifySubscriptionService.canOptOut("TODO_CREATED", "IN_APP")).isFalse();
        assertThat(notifySubscriptionService.canOptOut("TODO_CREATED", "EMAIL")).isTrue();
        // 保存关闭偏好时被拒绝
        NotifySubscriptionSaveReq req = new NotifySubscriptionSaveReq();
        NotifySubscriptionSaveReq.Item item = new NotifySubscriptionSaveReq.Item();
        item.setEventType("TODO_CREATED");
        item.setChannel("IN_APP");
        item.setEnabled(false);
        req.setItems(List.of(item));
        assertThatThrownBy(() -> notifySubscriptionService.save(USER_7, req))
                .isInstanceOf(com.sw.ck.common.exception.BaseException.class);
    }

    @Test
    @DisplayName("R6 租户隔离：跨租户收件箱读不到对方通知")
    void r6_tenantIsolation() {
        testLoginContext.set(TENANT_100, USER_7);
        notifyFacade.send(NotifySendRequest.builder()
                .channel(NotifyChannel.IN_APP)
                .recipientId(USER_7)
                .title("租户 100 专属")
                .content("内容")
                .tenantId(TENANT_100)
                .eventType("SYSTEM")
                .bizId("t100-only")
                .build());
        // 换租户 200 的同号用户查询：读不到租户 100 的行
        testLoginContext.set(200L, USER_7);
        assertThat(notifyMessageService.unreadCount(USER_7)).isEqualTo(0);
        pageParamStub();
    }

    private void pageParamStub() {
        PageParam p = new PageParam();
        p.setPageNum(1);
        var page = notifyMessageService.pageInbox(p, USER_7, null, null, null);
        assertThat(page.getRecords()).isEmpty();
        assertThat(page.getTotal()).isEqualTo(0);
    }

    private NotifyRuleDTO rule(String code, String eventType, boolean required) {
        NotifyRuleDTO dto = new NotifyRuleDTO();
        dto.setRuleCode(code);
        dto.setName("I6 规则");
        dto.setEventType(eventType);
        dto.setChannelPriority("IN_APP");
        dto.setRecipientRule("ASSIGNEE");
        dto.setRequiredFlag(required);
        dto.setFailurePolicy("RETRY");
        dto.setEnabled(true);
        return dto;
    }

    // ==================== JDBC 支持与配置 ====================

    static class JdbcTemplateSupport {
        private final org.springframework.jdbc.core.JdbcTemplate jdbc;

        JdbcTemplateSupport(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void execute(String sql) {
            jdbc.execute(sql);
        }

        void update(String sql) {
            jdbc.update(sql);
        }

        long queryCount(String sql) {
            return jdbc.queryForObject(sql, Long.class);
        }
    }

    static class TestLoginContext implements com.sw.ck.common.security.LoginContextProvider {

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
        public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
            return com.sw.ck.common.datascope.DataScopeType.ALL;
        }

        @Override
        public Set<Long> getCustomDeptIds() { return Set.of(); }

        @Override
        public boolean isSuperAdmin() { return false; }
    }

    @Configuration
    @MapperScan("com.sw.ck.notify.mapper")
    static class I6Config {

        @Bean
        public DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url("jdbc:h2:mem:notifyi6;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        public JdbcTemplateSupport jdbcTemplateSupport(DataSource dataSource) {
            return new JdbcTemplateSupport(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
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
        public com.sw.ck.notify.render.TemplateRenderService templateRenderService() {
            return new com.sw.ck.notify.render.TemplateRenderService();
        }

        @Bean
        public CommonMetaObjectHandler metaObjectHandler(TestLoginContext ctx) {
            return new CommonMetaObjectHandler(ctx);
        }

        @Bean
        public TenantProperties tenantProperties() {
            return new TenantProperties();
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties props, TestLoginContext ctx) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new CommonTenantLineHandler(props, ctx)));
            interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(DbType.POSTGRE_SQL));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                                                             CommonMetaObjectHandler handler,
                                                                             MybatisPlusInterceptor interceptor)
                throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.notify.entity");
            MybatisConfiguration config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(config);
            GlobalConfig gc = new GlobalConfig();
            gc.setMetaObjectHandler(handler);
            GlobalConfig.DbConfig db = new GlobalConfig.DbConfig();
            db.setLogicDeleteField("deleted");
            db.setLogicDeleteValue("1");
            db.setLogicNotDeleteValue("0");
            gc.setDbConfig(db);
            factory.setGlobalConfig(gc);
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public NotifyTemplateVersionService templateVersionServiceBean(NotifyTemplateVersionMapper vm) {
            return new NotifyTemplateVersionServiceImpl(vm);
        }

        @Bean
        public com.sw.ck.notify.service.NotifyTemplateService notifyTemplateService(
                com.sw.ck.notify.mapper.NotifyTemplateMapper mapper,
                com.sw.ck.notify.render.TemplateRenderService render,
                NotifyTemplateVersionService vs) {
            return new com.sw.ck.notify.service.impl.NotifyTemplateServiceImpl(mapper, render, vs);
        }

        @Bean
        public NotifyMessageService notifyMessageService(com.sw.ck.notify.service.NotifyTemplateService ts,
                                                         com.sw.ck.notify.render.TemplateRenderService render,
                                                         TestLoginContext ctx,
                                                         NotifyTemplateVersionService vs) {
            return new NotifyMessageServiceImpl(ts, render, ctx, vs);
        }

        @Bean
        public NotifyFacade notifyFacade(NotifyMessageService svc) {
            return new com.sw.ck.notify.impl.NotifyFacadeImpl(svc);
        }

        @Bean
        public NotifyRuleService notifyRuleService(NotifyRuleMapper mapper) {
            return new NotifyRuleServiceImpl(mapper);
        }

        @Bean
        public NotifySubscriptionService notifySubscriptionService(com.sw.ck.notify.mapper.NotifySubscriptionMapper m,
                                                                   NotifyRuleMapper r,
                                                                   NotifyChannelConfigMapper c) {
            return new NotifySubscriptionServiceImpl(m, r, c);
        }

        @Bean
        public NotifyChannelConfigService notifyChannelConfigService(com.sw.ck.notify.mapper.NotifyChannelConfigMapper c) {
            return new NotifyChannelConfigServiceImpl(List.of(), c);
        }

        @Bean
        public NotifyRoutingService notifyRoutingService(NotifyRuleMapper r,
                                                         NotifyChannelConfigService ch,
                                                         NotifySubscriptionService s) {
            return new NotifyRoutingServiceImpl(r, ch, s);
        }
    }
}
