package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.dto.ActionConfig;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.api.dto.ProcessGraph;
import com.sw.ck.bpm.api.dto.ProcessVariableDef;
import com.sw.ck.bpm.api.dto.TriggerConfig;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.api.script.BpmScriptEvaluatePort;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.mapper.BpmTriggerExecMapper;
import com.sw.ck.bpm.process.queue.BpmCommandQueue;
import com.sw.ck.common.event.DomainEventPublisher;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DR07 终态提交阶段行为证据（BE020-G2）：真实事务边界 + 真实持久 exec 行。
 * <p>
 * exec 行经真实 MyBatis/H2 持久化（唯一索引 uk_sw_bpm_trigger_exec 承接幂等），
 * 事务经真实 DataSourceTransactionManager 提交/回滚；外部命令队列用受控桩观察
 * 入队时机。脚本求值桩替换（GraalJS 不在 process 测试类路径——既有隔离门禁）。
 * </p>
 */
@SpringBootTest(classes = TriggerTerminalCommitPhaseTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("DR07 终态阶段：新配置提交后副作用、回滚零外发、失败不撤销终态、旧配置同事务")
class TriggerTerminalCommitPhaseTest {

    private static final long TENANT = 9L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private BpmTriggerExecMapper triggerExecMapper;

    private NodeFormDataService nodeFormDataService;
    private BpmVariableSnapshotService variableSnapshotService;
    private BpmCommandQueue commandQueue;
    private TransactionTemplate outerTx;

    @Configuration
    @MapperScan("com.sw.ck.bpm.process.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url("jdbc:h2:mem:triggertx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        }

        @Bean
        public LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override public Long getUserId() {
                    var u = LoginUserHolder.get();
                    return u == null ? null : u.getUserId();
                }
                @Override public Long getTenantId() {
                    var u = LoginUserHolder.get();
                    return u == null ? null : u.getTenantId();
                }
                @Override public Long getDeptId() { return null; }
                @Override public com.sw.ck.common.datascope.DataScopeType getDataScopeType() {
                    return com.sw.ck.common.datascope.DataScopeType.ALL;
                }
                @Override public java.util.Set<Long> getCustomDeptIds() { return java.util.Set.of(); }
                @Override public boolean isSuperAdmin() { return false; }
            };
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource, LoginContextProvider loginContextProvider) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.bpm.process.entity");
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
            globalConfig.setMetaObjectHandler(new CommonMetaObjectHandler(loginContextProvider));
            factory.setGlobalConfig(globalConfig);
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(new TenantProperties(), loginContextProvider)));
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            factory.setPlugins(interceptor);
            return factory.getObject();
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sw_bpm_trigger_exec (
                    id bigint not null primary key,
                    create_time timestamp not null default current_timestamp,
                    create_by bigint,
                    update_time timestamp not null default current_timestamp,
                    update_by bigint,
                    deleted int not null default 0,
                    tenant_id bigint not null default 0,
                    version bigint not null default 0,
                    process_instance_id varchar(64) not null,
                    process_def_key varchar(128),
                    def_version int,
                    trigger_id varchar(64) not null,
                    event_type varchar(32) not null,
                    node_key varchar(200),
                    round_no bigint not null default 0,
                    exec_key varchar(200) not null,
                    status varchar(20) not null,
                    result_value varchar(512),
                    result_type varchar(16),
                    matched_branch_id varchar(64),
                    disposition varchar(20),
                    snapshot_text text,
                    duration_ms bigint,
                    error_text text
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_bpm_trigger_exec_tx "
                + "ON sw_bpm_trigger_exec (tenant_id, exec_key)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_phase_sentinel (
                    id bigint not null primary key,
                    label varchar(64) not null
                )
                """);
        nodeFormDataService = mock(NodeFormDataService.class);
        variableSnapshotService = mock(BpmVariableSnapshotService.class);
        commandQueue = mock(BpmCommandQueue.class);
        outerTx = new TransactionTemplate(transactionManager);
        // 租户拦截器要求已认证身份（fail closed）：与生产同线程传递一致
        com.sw.ck.security.holder.LoginUser user = new com.sw.ck.security.holder.LoginUser();
        user.setUserId(7L);
        user.setTenantId(TENANT);
        LoginUserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        jdbcTemplate.execute("DELETE FROM sw_bpm_trigger_exec");
        jdbcTemplate.execute("DELETE FROM t_phase_sentinel");
    }

    /** 以真实 mapper + 真实事务管理器重建服务（协作者桩由用例给定）。 */
    private TriggerExecutionService serviceWith(BpmScriptEvaluatePort scriptPort) {
        return new TriggerExecutionService(nodeFormDataService, variableSnapshotService, scriptPort,
                commandQueue, triggerExecMapper,
                mock(com.sw.ck.bpm.process.mapper.BpmActionRefMapper.class),
                mock(BpmTaskFacade.class), new ObjectMapper(), null, null, transactionManager);
    }

    private BpmInstance instance() {
        BpmInstance instance = new BpmInstance();
        instance.setProcessInstanceId("pi-1");
        instance.setProcessDefKey("def_main");
        instance.setBusinessKey("rec-1");
        instance.setFormKey("main_form");
        instance.setInitiatorId(7L);
        instance.setTenantId(TENANT);
        instance.setDefVersion(2);
        return instance;
    }

    private TriggerConfig trigger(String script, Boolean enabled) {
        return TriggerConfig.builder()
                .triggerId("trg-1").name("t").event(TriggerExecutionService.EVENT_PROCESS_COMPLETED)
                .script(script).variables(List.of("v_set"))
                .branches(List.of(TriggerConfig.TriggerBranch.builder()
                        .branchId("b1").name("b").matchType("NUMBER").matchValue("1")
                        .actions(List.of(ActionConfig.builder()
                                .actionId("act-1").name("a").type("START_EACH")
                                .sourceVariable("v_set").targetProcessDefKey("def_target")
                                .targetFormKey("target_form").maxDispatch(50)
                                .mapping(List.of(ActionConfig.ActionMapping.builder()
                                        .targetField("owner").itemField("id").build()))
                                .build()))
                        .build()))
                .enabled(enabled)
                .build();
    }

    private void stubGraph(BpmInstance instance, TriggerConfig trigger) {
        ProcessGraph graph = ProcessGraph.builder()
                .processKey("def_main")
                .variables(List.of(ProcessVariableDef.builder()
                        .varId("v_set").type("USER_SET").source("MAIN_FORM")
                        .sourceField("handlers").aggregation("UNION").nullable(false).build()))
                .triggers(List.of(trigger))
                .build();
        when(nodeFormDataService.loadGraph("def_main", 2)).thenReturn(graph);
        when(nodeFormDataService.currentRound("pi-1")).thenReturn(1L);
        when(variableSnapshotService.buildSnapshot(eq(TENANT), eq(instance), any(), any(), any(), anyLong()))
                .thenReturn(new BpmVariableSnapshotService.SnapshotResult(
                        Map.of("v_set", List.of("11")),
                        "{\"variables\":{\"v_set\":[\"11\"]}}", false, List.of(), List.of()));
    }

    private BpmScriptEvaluatePort okScriptPort() {
        BpmScriptEvaluatePort port = mock(BpmScriptEvaluatePort.class);
        when(port.run(anyString(), any(), anyLong(), any())).thenAnswer(inv ->
                java.util.Optional.of(new BpmScriptEvaluatePort.ScriptOutcome("OK", 1L, "NUMBER", null, 1L)));
        return port;
    }

    private BpmScriptEvaluatePort failingScriptPort() {
        BpmScriptEvaluatePort port = mock(BpmScriptEvaluatePort.class);
        when(port.run(anyString(), any(), anyLong(), any())).thenAnswer(inv ->
                java.util.Optional.of(new BpmScriptEvaluatePort.ScriptOutcome(
                        "SCRIPT_ERROR", null, null, "引用错误: boom is not defined", 1L)));
        return port;
    }

    private int execRowCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_trigger_exec WHERE tenant_id = ?", Integer.class, TENANT);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("新配置：终态提交后入队且 exec 行持久；提交前零外发零落行")
    void newConfigSideEffectsHappenOnlyAfterTerminalCommit() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("return 1;", true));
        TriggerExecutionService svc = serviceWith(okScriptPort());
        AtomicInteger committed = new AtomicInteger();

        outerTx.execute(status -> {
            jdbcTemplate.update("INSERT INTO t_phase_sentinel (id, label) VALUES (1, 'terminal')");
            svc.onProcessCompleted(instance, "APPROVED");
            // 终态事务提交前：无入队、独立连接不可见 exec 行
            verify(commandQueue, never()).enqueue(any());
            assertThat(execRowCount()).as("提交前 exec 行不可见").isZero();
            return null;
        });
        // 终态事务已提交：副作用发生且持久
        verify(commandQueue, times(1)).enqueue(any());
        assertThat(execRowCount()).as("提交后 exec 行持久").isEqualTo(1);
        Integer sentinel = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_phase_sentinel", Integer.class);
        assertThat(sentinel).isEqualTo(1);
    }

    @Test
    @DisplayName("新配置：终态事务回滚零外发、零落行")
    void newConfigRollbackProducesNoSideEffect() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("return 1;", true));
        TriggerExecutionService svc = serviceWith(okScriptPort());

        try {
            outerTx.execute(status -> {
                svc.onProcessCompleted(instance, "APPROVED");
                throw new IllegalStateException("模拟办理事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 回滚由异常触发
        }
        verify(commandQueue, never()).enqueue(any());
        assertThat(execRowCount()).isZero();
    }

    @Test
    @DisplayName("新配置：监听失败不撤销已成立终态（哨兵行与 FAILED 行同存）")
    void listenerFailureDoesNotUndoTerminalState() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("boom();", true));
        TriggerExecutionService svc = serviceWith(failingScriptPort());

        outerTx.executeWithoutResult(status -> {
            jdbcTemplate.update("INSERT INTO t_phase_sentinel (id, label) VALUES (2, 'terminal')");
            svc.onProcessCompleted(instance, "APPROVED");
        });
        // 终态哨兵行与 FAILED 执行行同时持久：监听失败未回滚终态
        Integer sentinel = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_phase_sentinel WHERE id = 2", Integer.class);
        assertThat(sentinel).isEqualTo(1);
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sw_bpm_trigger_exec WHERE status = 'FAILED'", Integer.class);
        assertThat(failed).isEqualTo(1);
        verify(commandQueue, never()).enqueue(any());
    }

    @Test
    @DisplayName("旧配置：保持同事务阶段——事务内即入队，回滚连带撤销")
    void legacyConfigKeepsInTransactionPhase() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("return 1;", null));
        TriggerExecutionService svc = serviceWith(okScriptPort());

        try {
            outerTx.execute(status -> {
                svc.onProcessCompleted(instance, "APPROVED");
                // 同事务阶段：提交前已入队且 exec 行在本事务连接内可见
                verify(commandQueue, times(1)).enqueue(any());
                assertThat(execRowCount()).as("旧配置事务内可见").isEqualTo(1);
                throw new IllegalStateException("模拟办理事务回滚");
            });
        } catch (IllegalStateException expected) {
            // 回滚由异常触发
        }
        assertThat(execRowCount()).as("回滚连带撤销旧配置执行行").isZero();
    }

    @Test
    @DisplayName("重复投递：第二次终态投递幂等跳过，不重复成功副作用")
    void duplicateDeliveryIsIdempotentOnRealPersistence() {
        BpmInstance instance = instance();
        stubGraph(instance, trigger("return 1;", true));
        TriggerExecutionService svc = serviceWith(okScriptPort());

        outerTx.executeWithoutResult(status -> svc.onProcessCompleted(instance, "APPROVED"));
        outerTx.executeWithoutResult(status -> svc.onProcessCompleted(instance, "APPROVED"));

        verify(commandQueue, times(1)).enqueue(any());
        assertThat(execRowCount()).isEqualTo(1);
    }
}
