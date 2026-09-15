package com.sw.ck.notify.i6;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantLineSuspension;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.notify.api.NotifyChannel;
import com.sw.ck.notify.api.NotifyChannelAdapter;
import com.sw.ck.notify.api.NotifyFacade;
import com.sw.ck.notify.api.NotifySendRequest;
import com.sw.ck.notify.entity.NotifyMessage;
import com.sw.ck.notify.mapper.NotifySendAttemptMapper;
import com.sw.ck.notify.mapper.NotifyMessageMapper;
import com.sw.ck.notify.service.NotifyMessageService;
import com.sw.ck.notify.service.NotifyDeliveryRecoveryService;
import com.sw.ck.notify.impl.NotifyFacadeImpl;
import com.sw.ck.notify.service.impl.NotifyMessageServiceImpl;
import com.sw.ck.notify.service.impl.NotifyDeliveryRecoveryServiceImpl;
import com.sw.ck.security.holder.LoginUser;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.sw.ck.notify.api.NotifySendRequest;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

/**
 * G1 持久投递恢复测试支撑：文件 H2（跨 JVM 持久）+ 真实通知域通道 + 固定失败适配器。
 * <p>两个上下文共用同一文件库，首个上下文停止后第二个上下文独立启动 —— 即重启的等价场景；
 * 第二个上下文由 {@link ConfigureScheduling} 触发自主调度（非人工方法调用）。</p>
 */
@Configuration
@EnableScheduling
@MapperScan(basePackageClasses = NotifyMessageMapper.class)
public class I6NotifyRestartSupport {

    /** 每个测试类固定独立文件库；跨 @SpringBootTest 类经由同一路径获得持久库。 */
    public static final String DB_URL = "jdbc:h2:file:./target/i6-restart-db;MODE=PostgreSQL;AUTO_SERVER=TRUE";

    @Bean(destroyMethod = "close")
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .url(DB_URL)
                .driverClassName("org.h2.Driver")
                .username("sa")
                .password("")
                .build();
    }

    @Bean
    public org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TenantProperties tenantProperties() {
        return new TenantProperties();
    }

    @Bean
    public com.sw.ck.notify.render.TemplateRenderService templateRenderService() {
        return new com.sw.ck.notify.render.TemplateRenderService();
    }

    @Bean
    public TestLoginContext testLoginContext() {
        return new TestLoginContext();
    }

    @Bean
    public CommonMetaObjectHandler metaObjectHandler(TestLoginContext ctx) {
        return new CommonMetaObjectHandler(ctx);
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(TenantProperties props, TestLoginContext ctx) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new CommonTenantLineHandler(props, ctx)));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               CommonMetaObjectHandler handler,
                                               MybatisPlusInterceptor interceptor) throws Exception {
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
    public NotifyMessageService notifyMessageService(
            com.sw.ck.notify.mapper.NotifyTemplateMapper templateMapper,
            com.sw.ck.notify.render.TemplateRenderService render,
            TestLoginContext ctx) {
        return new NotifyMessageServiceImpl(
                new com.sw.ck.notify.service.impl.NotifyTemplateServiceImpl(templateMapper, render, null),
                render, ctx);
    }

    /** 固定失败适配器：每次调用抛出可重试异常（模拟 Provider 瞬时故障）。 */
    @Bean
    public NotifyChannelAdapter failingAdapter() {
        return new NotifyChannelAdapter() {
            @Override
            public NotifyChannel channel() {
                return NotifyChannel.FEISHU;
            }

            @Override
            public com.sw.ck.notify.api.NotifySendResult send(NotifySendRequest request) {
                throw new RuntimeException(new java.net.SocketTimeoutException("飞书发送超时(测试注入)"));
            }
        };
    }

    @Bean
    public NotifyFacade notifyFacade(NotifyMessageService svc, List<NotifyChannelAdapter> adapters, com.sw.ck.notify.mapper.NotifySendAttemptMapper attemptMapper) {
        return new com.sw.ck.notify.impl.NotifyFacadeImpl(svc, adapters, attemptMapper);
    }

    @Bean
    public NotifyDeliveryRecoveryService notifyDeliveryRecoveryService(
            NotifyMessageMapper messageMapper,
            NotifySendAttemptMapper attemptMapper,
            NotifyFacade notifyFacade) {
        return new NotifyDeliveryRecoveryServiceImpl(messageMapper, attemptMapper, notifyFacade);
    }

    static class TestLoginContext implements LoginContextProvider {
        private volatile Long userId;
        private volatile Long tenantId;

        void set(Long tenantId, Long userId) {
            this.tenantId = tenantId;
            this.userId = userId;
        }

        @Override
        public Long getUserId() { return userId; }

        @Override
        public Long getTenantId() { return tenantId; }

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

    /** 启停租户过滤、按稳定身份读取与重建投递请求；重启前后共用。 */
    public static NotifyMessage findByIdentity(org.springframework.jdbc.core.JdbcTemplate jdbc,
                                               Long tenantId, Long recipientId, String channel) {
        try (TenantLineSuspension.Suspended ignored = TenantLineSuspension.suspended()) {
            return jdbc.query("""
                            SELECT * FROM sw_notify_message WHERE tenant_id = ? AND recipient_id = ?
                              AND event_type = 'TODO_CREATED' AND biz_id = 'task-g1' AND channel = ?
                              AND deleted = 0 ORDER BY id LIMIT 1
                            """,
                    (rs, i) -> {
                        NotifyMessage m = new NotifyMessage();
                        m.setId(rs.getLong("id"));
                        m.setTenantId(rs.getLong("tenant_id"));
                        m.setRecipientId(rs.getLong("recipient_id"));
                        m.setChannel(rs.getString("channel"));
                        m.setDeliveryStatus(rs.getString("delivery_status"));
                        m.setEventType(rs.getString("event_type"));
                        m.setRetryCount(rs.getInt("retry_count"));
                        m.setFailureClass(rs.getString("failure_class"));
                        m.setExternalMessageId(rs.getString("external_message_id"));
                        return m;
                    }, tenantId, recipientId, channel).stream().findFirst().orElse(null);
        }
    }
}
