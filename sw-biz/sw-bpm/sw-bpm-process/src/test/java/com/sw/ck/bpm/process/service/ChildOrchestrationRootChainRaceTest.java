package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.bpm.api.exception.BpmErrorCode;
import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
import com.sw.ck.form.api.facade.FormDataWritebackFacade;
import com.sw.ck.bpm.process.entity.BpmChildBatch;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmActionRefMapper;
import com.sw.ck.bpm.process.mapper.BpmChildBatchMapper;
import com.sw.ck.bpm.process.mapper.BpmChildItemMapper;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.common.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P64 根链护栏同根并发占用验证（复审05 P2-06a-C，真实 H2 PostgreSQL 模式 + 真实 bpm 迁移）。
 * <p>
 * 边界：同根（root_instance_id 相同）两个不同 batch_key 的并发冻结，既有占用 999、
 * 各自 +1——合同要求合计至多允许 1（最终总额 ≤1000），被拒方零新增批次/意图。
 * 单批固定 999 计数 fixture，无 1000 对象、无压测。
 * </p>
 */
@SpringBootTest(classes = ChildOrchestrationRootChainRaceTest.RaceConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("P64 根链护栏同根并发占用（真实 H2）")
class ChildOrchestrationRootChainRaceTest {

    private static final Long TENANT = 9L;
    private static final String ROOT = "root-race-1";

    @Autowired
    private ChildOrchestrationService service;

    @Autowired
    private BpmChildBatchMapper batchMapper;

    @Autowired
    private BpmInstanceMapper bpmInstanceMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private com.sw.ck.bpm.process.service.NodeFormDataService nodeFormDataService;

    @MockBean
    private BpmRuntimeFacade bpmRuntimeFacade;

    @MockBean
    private FormDataWritebackFacade writebackFacade;

    @MockBean
    private com.sw.ck.bpm.process.service.BpmInstanceService bpmInstanceService;

    @Configuration
    @MapperScan(basePackageClasses = BpmChildBatchMapper.class)
    static class RaceConfig {

        @Bean(destroyMethod = "")
        public DataSource dataSource() {
            DataSource dataSource = DataSourceBuilder.create()
                    .url("jdbc:h2:mem:p64rootchainrace;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
            // V51 引用主库 sys_menu：隔离库预置最小结构（与 QueueH2TestConfig 同口径）
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
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        }

        @Bean
        public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
            return new TransactionTemplate(txManager);
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public com.sw.ck.common.security.LoginContextProvider loginContextProvider() {
            return new com.sw.ck.common.security.LoginContextProvider() {
                @Override
                public Long getUserId() {
                    var user = com.sw.ck.security.holder.LoginUserHolder.get();
                    return user == null ? null : user.getUserId();
                }

                @Override
                public Long getTenantId() {
                    var user = com.sw.ck.security.holder.LoginUserHolder.get();
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
        public com.sw.ck.common.config.mybatis.CommonMetaObjectHandler commonMetaObjectHandler(
                com.sw.ck.common.security.LoginContextProvider provider) {
            return new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(provider);
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                                   com.sw.ck.common.config.mybatis.CommonMetaObjectHandler metaObjectHandler,
                                                   com.sw.ck.common.security.LoginContextProvider loginContextProvider) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            GlobalConfig globalConfig = new GlobalConfig();
            GlobalConfig.DbConfig dbConfig = new GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            dbConfig.setIdType(com.baomidou.mybatisplus.annotation.IdType.ASSIGN_ID);
            globalConfig.setDbConfig(dbConfig);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setConfiguration(configuration);
            globalConfig.setMetaObjectHandler(metaObjectHandler);
            // 生产同口径：租户行拦截器负责 INSERT 审计列 tenant_id（freezeBatch 实体不手动设租户）
            com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor =
                    new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            com.sw.ck.common.config.mybatis.tenant.TenantProperties tenantProperties =
                    new com.sw.ck.common.config.mybatis.tenant.TenantProperties();
            interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor(
                    new com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler(
                            tenantProperties, loginContextProvider)));
            interceptor.addInnerInterceptor(
                    new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());
            factory.setPlugins(interceptor);
            factory.setGlobalConfig(globalConfig);
            try {
                return factory.getObject();
            } catch (Exception e) {
                throw new IllegalStateException("初始化 SqlSessionFactory 失败", e);
            }
        }

        @Bean
        public ChildOrchestrationService childOrchestrationService(
                com.sw.ck.bpm.process.mapper.BpmActionRefMapper actionRefMapper,
                BpmChildBatchMapper batchMapper,
                com.sw.ck.bpm.process.mapper.BpmChildItemMapper itemMapper,
                com.sw.ck.bpm.process.service.NodeFormDataService nodeFormDataService,
                com.sw.ck.bpm.process.service.BpmInstanceService bpmInstanceService,
                BpmRuntimeFacade bpmRuntimeFacade,
                FormDataWritebackFacade writebackFacade,
                BpmInstanceMapper bpmInstanceMapper) {
            return new ChildOrchestrationService(actionRefMapper, batchMapper, itemMapper,
                    nodeFormDataService, bpmInstanceService, bpmRuntimeFacade, writebackFacade,
                    bpmInstanceMapper, new ObjectMapper());
        }
    }

    @AfterEach
    void cleanRootBatches() {
        jdbcTemplate.update("DELETE FROM sw_bpm_child_batch WHERE root_instance_id = ?", ROOT);
        jdbcTemplate.update("DELETE FROM sw_bpm_instance WHERE process_instance_id = ?", ROOT);
        com.sw.ck.security.holder.LoginUserHolder.clear();
    }

    @BeforeEach
    void seedRootChain() {
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(7L);
        user.setTenantId(TENANT);
        com.sw.ck.security.holder.LoginUserHolder.set(user);
        // 根实例行（根链锁目标；生产不变量：root_instance_id 必为真实实例 ID）
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_instance WHERE process_instance_id = ?", Long.class, ROOT);
        if (existing == 0) {
            BpmInstance root = new BpmInstance();
            root.setProcessInstanceId(ROOT);
            root.setProcessDefKey("def_race");
            root.setBusinessKey("rec-race");
            root.setFormKey("form_race");
            root.setInitiatorId(7L);
            root.setTenantId(TENANT);
            root.setStatus("RUNNING");
            bpmInstanceMapper.insert(root);
        }
    }

    /** 既有同根占用 fixture：单批 expectedCount=total（固定计数，不创建子实例对象）。 */
    private void seedOccupancy(int total, long batchId) {
        BpmChildBatch fixture = new BpmChildBatch();
        fixture.setId(batchId);
        fixture.setBatchKey("CHILD:fixture:" + batchId);
        fixture.setParentInstanceId("parent-fixture");
        fixture.setParentDefKey("def_race");
        fixture.setRootInstanceId(ROOT);
        fixture.setParentDepth(0);
        fixture.setTriggerId("trg");
        fixture.setActionId("act");
        fixture.setRoundNo(0L);
        fixture.setWaitPolicy("ALL");
        fixture.setExpectedCount(total);
        fixture.setStatus(BpmChildBatch.STATUS_SETTLED);
        fixture.setTenantId(TENANT);
        fixture.setDeleted(0);
        batchMapper.insert(fixture);
    }

    private BpmInstance parent() {
        BpmInstance parent = new BpmInstance();
        parent.setTenantId(TENANT);
        parent.setProcessInstanceId(ROOT);
        parent.setProcessDefKey("def_race");
        parent.setBusinessKey("rec-race");
        parent.setFormKey("form_race");
        parent.setInitiatorId(7L);
        return parent;
    }

    private com.sw.ck.bpm.api.dto.ActionConfig actionAll(String actionId) {
        com.sw.ck.bpm.api.dto.ActionConfig action = new com.sw.ck.bpm.api.dto.ActionConfig();
        action.setActionId(actionId);
        action.setType("START_EACH");
        action.setWaitPolicy("ALL");
        return action;
    }

    private Long rootOccupancySum() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(expected_count), 0) FROM sw_bpm_child_batch "
                        + "WHERE tenant_id = ? AND root_instance_id = ? AND deleted = 0",
                Long.class, TENANT, ROOT);
    }

    private Long rootBatchCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_child_batch "
                        + "WHERE tenant_id = ? AND root_instance_id = ? AND deleted = 0",
                Long.class, TENANT, ROOT);
    }

    @Test
    @DisplayName("同根两个不同 batch 各+1 并发冻结：合计至多允许 1，最终总额≤1000，被拒方零新增")
    void concurrentFreezeOnSameRootAdmitsAtMostOneAcrossLimit() throws Exception {
        seedOccupancy(999, 9001L);

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> outcomes = List.of(
                    pool.submit(() -> raceFreeze(start, "exec-race-a", "act-a")),
                    pool.submit(() -> raceFreeze(start, "exec-race-b", "act-b")));
            Object first = outcomes.get(0).get(60, TimeUnit.SECONDS);
            Object second = outcomes.get(1).get(60, TimeUnit.SECONDS);

            System.out.println("[race] outcomeA=" + describe(first));
            System.out.println("[race] outcomeB=" + describe(second));
            long admitted = countAdmitted(first, second);
            long rejected = 2 - admitted;
            // 合同：同根并发占用串行化后，999+1 允许、第 2 个并发冻结必须被拒
            assertThat(admitted).as("恰好一个并发冻结获得最后 1 个额度").isEqualTo(1);
            assertThat(rejected).as("另一个必须拒绝").isEqualTo(1);
            assertThatRejectedWithChainOverLimit(admitted == 1 ? second : first);
            // 最终总额 ≤1000：拒绝方零新增批次
            assertThat(rootOccupancySum()).as("根链自动发起实例总额").isEqualTo(1000);
            assertThat(rootBatchCount()).as("同根批次数（fixture+1）").isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 并发冻结单线程体：独立事务内执行（与生产调用方事务语义一致）。 */
    private Object raceFreeze(CyclicBarrier start, String execKey, String actionId) {
        return raceFreezeWith(start, service, execKey, actionId);
    }

    private Object raceFreezeWith(CyclicBarrier start, ChildOrchestrationService target,
                                  String execKey, String actionId) {
        try {
            start.await(30, TimeUnit.SECONDS);
            com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
            user.setUserId(7L);
            user.setTenantId(TENANT);
            com.sw.ck.security.holder.LoginUserHolder.set(user);
            return transactionTemplate.execute(status ->
                    target.freezeBatch(parent(), "trg", actionAll(actionId), execKey, 1, 1L, null, TENANT));
        } catch (Exception e) {
            return e;
        } finally {
            com.sw.ck.security.holder.LoginUserHolder.clear();
        }
    }

    private String describe(Object outcome) {
        if (outcome instanceof BpmChildBatch batch) {
            return "ADMITTED batch=" + batch.getBatchKey() + " expected=" + batch.getExpectedCount();
        }
        if (outcome instanceof Throwable t) {
            StringBuilder sb = new StringBuilder("REJECTED " + t.getClass().getSimpleName());
            for (Throwable c = t; c != null && sb.length() < 1200; c = c.getCause()) {
                sb.append(" << ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            }
            return sb.toString();
        }
        return String.valueOf(outcome);
    }

    private long countAdmitted(Object... outcomes) {
        long admitted = 0;
        for (Object outcome : outcomes) {
            if (outcome instanceof BpmChildBatch) {
                admitted++;
            }
        }
        return admitted;
    }

    private void assertThatRejectedWithChainOverLimit(Object outcome) {
        assertThat(outcome).isInstanceOfSatisfying(BaseException.class, e ->
                assertThat(e.getCode()).isEqualTo(BpmErrorCode.CHILD_CHAIN_OVER_LIMIT.getCode()));
    }
}
