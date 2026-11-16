package com.sw.ck.bootstrap.p4overlap;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.service.impl.BpmCommandServiceImpl;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.service.impl.BpmCommandServiceImpl;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.bpm.process.entity.BpmCommand;
import com.sw.ck.bpm.process.mapper.BpmCommandEffectMapper;
import com.sw.ck.bpm.process.mapper.BpmResourcePolicyMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceRejectLogMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.bpm.process.service.ResourceAdmissionService;
import com.sw.ck.bpm.process.service.ResourceReleaseService;
import com.sw.ck.bpm.process.service.TenantRateBuckets;
import com.sw.ck.common.datascope.DataScopeType;

import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Set;
import javax.sql.DataSource;

/**
 * G4/G5/G7 隔离集成验证配置：真实 H2（PostgreSQL 模式）+ 真实 bpm 迁移 +
 * 真实 MyBatis-Plus（租户/乐观锁拦截器、审计填充）+ 真实持久化队列。
 */

@Configuration
@MapperScan(basePackageClasses = BpmCommandMapper.class)
public class OverlapH2TestConfig {

    @Bean(destroyMethod = "")
    public DataSource dataSource() {
        DataSource dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:p4commandqueue;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                .driverClassName("org.h2.Driver")
                .username("sa")
                .password("")
                .build();
        // V51 引用主库 sys_menu（生产全链含主迁移目录）；本隔离库仅装 bpm 目录，
        // 预置最小 sys_menu 结构使权限 seed 可执行。
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS sys_menu (
                        id bigint not null primary key, create_time timestamp not null default current_timestamp,
                        create_by bigint, update_time timestamp not null default current_timestamp, update_by bigint,
                        deleted smallint not null default 0, tenant_id bigint not null default 0, version bigint not null default 0,
                        parent_id bigint, name varchar(64), title varchar(64), hidden boolean not null default false,
                        menu_type int not null default 2, path varchar(200), component varchar(200),
                        permission varchar(128), icon varchar(64), sort int not null default 0)
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("预置 sys_menu 失败", e);
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/bpm-fixture/h2") // 0.1.3 种子合并后 BPM H2 迁移冻结为测试夹具（与原主链逐字节一致，非冲突路径避免污染全链锚）
                .baselineOnMigrate(true)
                .load()
                .migrate();
        return dataSource;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }

    @Bean
    public LoginContextProvider loginContextProvider() {
        return new LoginContextProvider() {
            @Override
            public Long getUserId() {
                var user = LoginUserHolder.get();
                return user == null ? null : user.getUserId();
            }

            @Override
            public Long getTenantId() {
                var user = LoginUserHolder.get();
                return user == null ? null : user.getTenantId();
            }

            @Override
            public Long getDeptId() {
                return null;
            }

            @Override
            public DataScopeType getDataScopeType() {
                return DataScopeType.SELF;
            }

            @Override
            public Set<Long> getCustomDeptIds() {
                return Set.of();
            }

            @Override
            public boolean isSuperAdmin() {
                // 与生产 SecurityLoginContextProvider 同语义：从登录态读取超管标识
                var user = LoginUserHolder.get();
                return user != null && user.isSuperAdmin();
            }
        };
    }

    @Bean
    public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider provider) {
        return new CommonMetaObjectHandler(provider);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                               LoginContextProvider loginContextProvider) {
        TenantProperties tenantProperties = new TenantProperties();
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                new CommonTenantLineHandler(tenantProperties, loginContextProvider)));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(StdOutImpl.class);

        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setLogicDeleteField("deleted");
        dbConfig.setLogicDeleteValue("1");
        dbConfig.setLogicNotDeleteValue("0");
        globalConfig.setDbConfig(dbConfig);
        globalConfig.setMetaObjectHandler(commonMetaObjectHandler(loginContextProvider));

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(globalConfig);
        factory.setPlugins(interceptor);
        try {
            return factory.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 SqlSessionFactory 失败", e);
        }
    }

    @Bean
    public BpmCommandServiceImpl bpmCommandService(SqlSessionFactory ignored) {
        return new BpmCommandServiceImpl();
    }

    @Bean
    public ResourceAdmissionService resourceAdmissionService(
            BpmResourcePolicyMapper policyMapper,
            BpmResourceUsageMapper usageMapper,
            BpmResourceRejectLogMapper rejectLogMapper,
            PlatformTransactionManager txManager) {
        return new ResourceAdmissionService(policyMapper, usageMapper,
                rejectLogMapper, new TenantRateBuckets(), txManager);
    }

    @Bean
    public ResourceReleaseService resourceReleaseService(
            ResourceAdmissionService admissionService,
            BpmCommandServiceImpl commandService,
            JdbcTemplate jdbcTemplate) {
        return new ResourceReleaseService(admissionService,
                commandService, jdbcTemplate);
    }

    @Bean
    public PersistentBpmCommandQueue bpmCommandQueue(BpmCommandServiceImpl commandService,
                                                     BpmCommandEffectMapper effectMapper,
                                                     ResourceReleaseService releaseService,
                                                     ResourceAdmissionService admissionService) {
        return new StaleReadWindowQueue(commandService, effectMapper, releaseService, admissionService);
    }

    /**
     * B4 窗口测试用队列：默认行为与真实队列完全一致；仅当测试显式放置
     * staleSnapshot 时，failAndScheduleRetry 的读取返回该快照，构造
     * "读取校验通过后、实际写回前发生租约交接"的确定性竞争窗口。
     */
    public static class StaleReadWindowQueue extends PersistentBpmCommandQueue {

        public volatile BpmCommand staleSnapshot;

        public StaleReadWindowQueue(BpmCommandServiceImpl commandService,
                                    BpmCommandEffectMapper effectMapper,
                                    ResourceReleaseService releaseService,
                                    ResourceAdmissionService admissionService) {
            super(commandService, effectMapper, releaseService, admissionService,
                    (tenantId, claim) -> claim.getAsInt());
        }

        @Override
        protected BpmCommand readCommandForFailure(Long commandId) {
            BpmCommand snapshot = staleSnapshot;
            return snapshot != null ? snapshot : super.readCommandForFailure(commandId);
        }
    }
}
