package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.bpm.process.mapper.BpmConsensusVoteMapper;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUserHolder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 会签计数并发测试支撑：隔离 H2（PostgreSQL 模式）+ 真实 bpm 迁移
 * （V71 建 sw_bpm_consensus_vote 及唯一键）+ 真实 MyBatis-Plus 通道。
 */
@Configuration
@MapperScan(basePackageClasses = BpmConsensusVoteMapper.class)
public class ConsensusVoteTestSupport {

    @Bean(destroyMethod = "")
    public DataSource dataSource() {
        DataSource dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:i3consensusvote;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                .driverClassName("org.h2.Driver")
                .username("sa")
                .password("")
                .build();
        // V51 引用主库 sys_menu（生产全链含主迁移目录）；本隔离库预置最小 sys_menu 结构
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
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("预置 sys_menu 失败", e);
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
            public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                return com.sw.ck.common.datascope.DataScopeType.SELF;
            }

            @Override
            public java.util.Set<Long> getCustomDeptIds() {
                return java.util.Set.of();
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
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                new CommonTenantLineHandler(tenantProperties, loginContextProvider)));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);

        GlobalConfig globalConfig = new GlobalConfig();
        GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
        dbConfig.setIdType(com.baomidou.mybatisplus.annotation.IdType.ASSIGN_ID);
        globalConfig.setDbConfig(dbConfig);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        factoryBean.setPlugins(interceptor);
        try {
            return factoryBean.getObject();
        } catch (Exception e) {
            throw new IllegalStateException("构建 SqlSessionFactory 失败", e);
        }
    }
}
