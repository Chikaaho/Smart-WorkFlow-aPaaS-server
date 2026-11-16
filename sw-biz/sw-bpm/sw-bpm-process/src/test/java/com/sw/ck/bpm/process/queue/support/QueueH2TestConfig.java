package com.sw.ck.bpm.process.queue.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import com.sw.ck.bpm.process.mapper.BpmCommandEffectMapper;
import com.sw.ck.bpm.process.mapper.BpmCommandMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.mapper.BpmResourcePolicyMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceRejectLogMapper;
import com.sw.ck.bpm.process.mapper.BpmResourceUsageMapper;
import com.sw.ck.bpm.process.queue.CommandEffectRecorder;
import com.sw.ck.bpm.process.queue.PersistentBpmCommandQueue;
import com.sw.ck.bpm.process.queue.ResourceAssuranceReconcileJob;
import com.sw.ck.bpm.process.queue.TieredCommandReconcileJob;
import com.sw.ck.bpm.process.service.LightProcessClassifier;
import com.sw.ck.bpm.process.service.ResourceAdmissionService;
import com.sw.ck.bpm.process.service.ResourceFactView;
import com.sw.ck.bpm.process.service.ResourcePolicyService;
import com.sw.ck.bpm.process.service.ResourceReleaseService;
import com.sw.ck.bpm.process.service.TenantRateBuckets;
import com.sw.ck.bpm.process.service.impl.BpmCommandServiceImpl;
import com.sw.ck.bpm.process.service.impl.ResourcePolicyServiceImpl;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUserHolder;

import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * G4/G5/G7 隔离集成验证配置：真实 H2（PostgreSQL 模式）+ 真实 bpm 迁移 +
 * 真实 MyBatis-Plus（租户/乐观锁拦截器、审计填充）+ 真实持久化队列。
 */
@Configuration
@MapperScan(basePackageClasses = BpmCommandMapper.class)
public class QueueH2TestConfig {

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
        // 资源保障目标链事实桩表（模块库无 Flowable；行存在即占用成立，空表=链已终结）
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS act_ru_job (id_ varchar(64) primary key, "
                    + "process_instance_id_ varchar(64))");
            stmt.execute("CREATE TABLE IF NOT EXISTS act_ru_deadletter_job (id_ varchar(64) primary key, "
                    + "process_instance_id_ varchar(64))");
            stmt.execute("CREATE TABLE IF NOT EXISTS act_ru_execution (id_ varchar(64) primary key, "
                    + "proc_inst_id_ varchar(64), business_key_ varchar(64))");
        } catch (SQLException e) {
            throw new IllegalStateException("预置 act_ru 桩表失败", e);
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/bpm/h2")
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
                return false;
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
        // 资源治理表（策略/占用计数/拒绝审计）的租户语义由代码显式承载，排除拦截器注入
        tenantProperties.setIgnoreTables(List.of("sys_menu",
                "sw_bpm_resource_policy", "sw_bpm_resource_usage", "sw_bpm_resource_reject_log"));
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
    public TenantRateBuckets tenantRateBuckets() {
        return new TenantRateBuckets();
    }

    @Bean
    public ResourceAdmissionService resourceAdmissionService(
            BpmResourcePolicyMapper policyMapper,
            BpmResourceUsageMapper usageMapper,
            BpmResourceRejectLogMapper rejectLogMapper,
            TenantRateBuckets rateBuckets,
            PlatformTransactionManager txManager) {
        return new ResourceAdmissionService(
                policyMapper, usageMapper, rejectLogMapper, rateBuckets, txManager);
    }

    @Bean
    public ResourceFactView resourceFactView(
            JdbcTemplate jdbcTemplate) {
        return new ResourceFactView(jdbcTemplate);
    }

    @Bean
    public ResourceReleaseService resourceReleaseService(
            ResourceAdmissionService admissionService,
            BpmCommandServiceImpl commandService,
            JdbcTemplate jdbcTemplate) {
        return new ResourceReleaseService(
                admissionService, commandService, jdbcTemplate);
    }

    @Bean
    public ResourceAssuranceReconcileJob resourceAssuranceReconcileJob(
            BpmCommandServiceImpl commandService,
            ResourceReleaseService releaseService,
            BpmResourceUsageMapper usageMapper,
            ResourceFactView factView) {
        return new ResourceAssuranceReconcileJob(
                commandService, releaseService, usageMapper, factView);
    }

    @Bean
    public LightProcessClassifier lightProcessClassifier(
            BpmProcessDefMapper processDefMapper) {
        return new LightProcessClassifier(processDefMapper);
    }

    @Bean
    public ResourcePolicyService resourcePolicyService(
            BpmResourcePolicyMapper policyMapper,
            BpmResourceRejectLogMapper rejectLogMapper,
            ApplicationContext applicationContext,
            Environment environment,
            PlatformTransactionManager transactionManager) {
        return new ResourcePolicyServiceImpl(
                policyMapper, rejectLogMapper, applicationContext, environment, transactionManager);
    }

    @Bean
    public PersistentBpmCommandQueue bpmCommandQueue(BpmCommandServiceImpl commandService,
                                                     BpmCommandEffectMapper effectMapper,
                                                     ResourceReleaseService releaseService,
                                                     ResourceAdmissionService admissionService) {
        // 隔离队列状态机夹具；租户权威与行锁在 bootstrap H2/PG 入口专项验证。
        return new PersistentBpmCommandQueue(commandService, effectMapper, releaseService, admissionService,
                (tenantId, claim) -> claim.getAsInt());
    }

    @Bean
    public CommandEffectRecorder commandEffectRecorder(BpmCommandMapper commandMapper,
                                                       BpmCommandEffectMapper effectMapper) {
        return new CommandEffectRecorder(commandMapper, effectMapper);
    }

    @Bean
    public TieredCommandReconcileJob tieredCommandReconcileJob(PersistentBpmCommandQueue queue,
                                                               CommandEffectRecorder recorder) {
        return new TieredCommandReconcileJob(queue, recorder);
    }
}
