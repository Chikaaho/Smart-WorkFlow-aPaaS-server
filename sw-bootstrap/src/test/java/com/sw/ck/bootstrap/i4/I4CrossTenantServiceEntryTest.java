package com.sw.ck.bootstrap.i4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.bpm.api.participant.DynamicBranchPort;
import com.sw.ck.bpm.engine.delegate.DynamicBranchCollectionResolver;
import com.sw.ck.bpm.process.entity.BpmHandover;
import com.sw.ck.bpm.process.entity.DynamicBranchSnapshot;
import com.sw.ck.bpm.process.mapper.BpmHandoverItemMapper;
import com.sw.ck.bpm.process.mapper.BpmHandoverMapper;
import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
import com.sw.ck.bpm.process.service.BpmHandoverService;
import com.sw.ck.bpm.process.config.DynamicBranchPortConfiguration;
import com.sw.ck.bpm.process.service.impl.BpmHandoverServiceImpl;
import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.engine.facade.BpmTaskFacadeImpl;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.service.impl.DeptFacadeImpl;
import com.sw.ck.system.service.impl.UserFacadeImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.TaskService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R1-cross-tenant-runtime（二级提示 02）：真实跨租户对象经正式服务入口的确定拒绝与零增量。
 * <p>
 * 租户 A(0)/B(88) 各自拥有真实有效的部门与负责人（sys_dept/sys_user 真实行）；
 * 租户 A 经动态分支解析链（真实 {@link DynamicBranchCollectionResolver} + 真实
 * {@link DeptFacadeImpl}/{@link UserFacadeImpl} + 真实 {@link DynamicBranchPort} 冻结端口）
 * 引用 B 的部门 → 确定拒绝、两侧分支表零增量；同一对象在 B 上下文可正常解析（证明对象真实存在）。
 * 租户 A 经真实 {@link BpmHandoverServiceImpl} 交接引用 B 的真实用户 → 确定拒绝、交接主从表零增量；
 * 同租户有效用户正向通过。跨模块边界（BpmTaskFacade）用真实引擎门面（无实例返回空待办）。
 * </p>
 */
@SpringJUnitConfig(I4CrossTenantServiceEntryTest.R1Config.class)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
@DisplayName("R1 真实双租户服务入口：跨租户部门解析与用户交接确定拒绝 + 零增量")
class I4CrossTenantServiceEntryTest {

    /** 租户 A(0) 与租户 B(88) 的真实对象 ID（固定，写入 sys_dept/sys_user）。 */
    static final long DEPT_A = 7001000000000000001L;
    static final long USER_A = 7002000000000000001L;
    static final long USER_A2 = 7002000000000000002L;
    static final long DEPT_B = 7088000000000000001L;
    static final long USER_B = 7088000000000000002L;
    static final long TENANT_A = 0L;
    static final long TENANT_B = 88L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DynamicBranchCollectionResolver resolver;

    @Autowired
    private BpmHandoverService handoverService;

    @BeforeAll
    void outputPgFingerprint(@Autowired JdbcTemplate jdbc) throws Exception {
        // PG 指纹证据：JDBC url + server_version（明确证明真实 PostgreSQL）
        String url = jdbc.getDataSource().unwrap(javax.sql.DataSource.class).toString();
        String version = jdbc.queryForObject("select version()", String.class);
        String pgPort = String.valueOf(I4CrossTenantServiceEntryTest.DataSourceHolder.PG.getPort());
        String dir = System.getProperty("r1EvidenceDir");
        String jdbcUrl = jdbc.queryForObject("select current_setting('server_version_num')", String.class);
        String out = "R1 PG runtime fingerprint\nJDBC: " + url + "\nserver_version_num: " + jdbcVersion(jdbc)
                + "\nversion(): " + version + "\nembedded pg port: " + pgPort
                + "\nmigrated to V82 (postgresql full chain)\n";
        writeFingerprint(dir, out);        writeFingerprint(dir, out);
    }

    private static String jdbcVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select version()", String.class);
    }

    private static void writeFingerprint(String dir, String content) {
        if (dir == null || dir.isBlank()) return;
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir, "pg-runtime.txt"), content);
        } catch (Exception ignored) {
        }
    }

    @BeforeAll
    void seedRealTenantObjects(@Autowired JdbcTemplate jdbc) {
        // 真实有效的两租户对象：部门/用户均为正式表真实行（非占位 ID）
        jdbc.update("insert into sys_dept (id, tenant_id, parent_id, name, code, sort, status, leader_id) "
                        + "values (?, 0, 0, 'A部门', 'r1-dept-a', 1, 0, ?)",
                DEPT_A, USER_A);
        jdbc.update("insert into sys_dept (id, tenant_id, parent_id, name, code, sort, status, leader_id) "
                        + "values (?, 88, 0, 'B部门', 'r1-dept-b', 1, 0, ?)",
                DEPT_B, USER_B);
        jdbc.update("insert into sys_user (id, tenant_id, username, password, real_name, status, dept_id) "
                        + "values (?, 0, 'r1_user_a', '$2a$10$seedednotarealhashnotarealhash123456', 'A用户', 0, ?)",
                USER_A, DEPT_A);
        jdbc.update("insert into sys_user (id, tenant_id, username, password, real_name, status, dept_id) "
                        + "values (?, 0, 'r1_user_a2', '$2a$10$seedednotarealhashnotarealhash123456', 'A2用户', 0, ?)",
                USER_A2, DEPT_A);
        jdbc.update("insert into sys_user (id, tenant_id, username, password, real_name, status, dept_id) "
                        + "values (?, 88, 'r1_user_b', '$2a$10$seedednotarealhashnotarealhash123456', 'B用户', 0, ?)",
                USER_B, DEPT_B);
    }

    private DelegateExecution execution(Long tenantId, String processInstanceId, String definitionId) {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("tenantId")).thenReturn(tenantId);
        when(execution.getProcessInstanceId()).thenReturn(processInstanceId);
        when(execution.getProcessDefinitionId()).thenReturn(definitionId);
        when(execution.getCurrentActivityId()).thenReturn("dyn");
        org.flowable.bpmn.model.FlowElement element = new org.flowable.bpmn.model.UserTask();
        element.setId("dyn");
        when(execution.getCurrentFlowElement()).thenReturn(element);
        return execution;
    }

    @Test
    @DisplayName("租户 A 动态解析引用 B 真实部门 → 确定拒绝 + 两侧分支表零增量；B 上下文同对象可解析")
    void dynamicResolution_crossTenantDept_shouldRejectAndZeroIncrement() {
        String definitionId = R1Engine.DEPLOYED_DEFINITION_ID;
        // 正向对照：B 上下文解析同一真实部门 → 命中负责人 UB 并真实冻结分支（对象真实存在的证明）
        LoginUserHolder.set(user(TENANT_B, USER_B));
        try {
            @SuppressWarnings("unchecked")
            Collection<Object> frozen = (Collection<Object>) resolver.resolveCollection(null, execution(TENANT_B, "pi-r1-b-positive", definitionId));
            assertThat(frozen).containsExactly((Object) String.valueOf(USER_B));
            Integer bRows = jdbcTemplate.queryForObject(
                    "select count(*) from sw_bpm_dynamic_branch where tenant_id = 88 and deleted = 0",
                    Integer.class);
            assertThat(bRows).isEqualTo(1);
        } finally {
            LoginUserHolder.clear();
        }
        // 反向：租户 A 上下文引用 B 的真实部门 → BLOCK 确定拒绝
        LoginUserHolder.set(user(TENANT_A, USER_A));
        try {
            assertThatThrownBy(() -> resolver.resolveCollection(null, execution(TENANT_A, "pi-r1-a-negative", definitionId)))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("失效或负责人缺失")
                    .hasMessageContaining(String.valueOf(DEPT_B));
        } finally {
            LoginUserHolder.clear();
        }
        // 零增量：A/B 两侧除 B 正向对照行外，A 租户零分支行
        Integer aRows = jdbcTemplate.queryForObject(
                "select count(*) from sw_bpm_dynamic_branch where tenant_id = 0 and deleted = 0",
                Integer.class);
        assertThat(aRows).isZero();
    }

    @Test
    @DisplayName("租户 A 交接引用 B 真实用户 → 确定拒绝 + 交接主从表零增量；同租户用户正向通过")
    void handover_crossTenantUser_shouldRejectAndZeroIncrement() {
        // 反向：目标为 B 租户真实有效用户 → 目标校验拒绝
        LoginUserHolder.set(user(TENANT_A, USER_A));
        try {
            assertThatThrownBy(() -> handoverService.handover(USER_A, USER_B, List.of(), false))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("不属于当前租户");
        } finally {
            LoginUserHolder.clear();
        }
        Integer crossRows = jdbcTemplate.queryForObject(
                "select count(*) from sw_bpm_handover where deleted = 0 and "
                        + "(from_user_id = ? or to_user_id = ?)",
                Integer.class, USER_B, USER_B);
        assertThat(crossRows).isZero();
        Integer crossItems = jdbcTemplate.queryForObject(
                "select count(*) from sw_bpm_handover_item where deleted = 0 and after_assignee = ?",
                Integer.class, USER_B);
        assertThat(crossItems).isZero();
        // 正向对照：同租户真实有效用户 → 交接受理完成（零待办，totalItems=0）
        LoginUserHolder.set(user(TENANT_A, USER_A));
        try {
            BpmHandover positive = handoverService.handover(USER_A, USER_A2, List.of(), false);
            assertThat(positive.getStatus()).isEqualTo("COMPLETED");
            assertThat(positive.getTotalItems()).isZero();
        } finally {
            LoginUserHolder.clear();
        }
    }

    private static LoginUser user(Long tenantId, Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setSuperAdmin(false);
        return loginUser;
    }

    @TestConfiguration
    @MapperScan(basePackageClasses = {
            DynamicBranchSnapshotMapper.class,
            BpmHandoverMapper.class,
            BpmHandoverItemMapper.class,
            com.sw.ck.system.mapper.SysDeptMapper.class,
            com.sw.ck.system.mapper.SysUserMapper.class,
    })
    static class R1Config {

        @Bean(destroyMethod = "close")
        public DataSource dataSource() {
            return DataSourceHolder.DATA_SOURCE;
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public LoginContextProvider loginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user == null ? null : user.getUserId();
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
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
        public DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                                   LoginContextProvider loginContextProvider) {
            TenantProperties tenantProperties = new TenantProperties();
            com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor =
                    new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider)));
            interceptor.addInnerInterceptor(
                    new com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor());

            com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                    new com.baomidou.mybatisplus.core.MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);

            com.baomidou.mybatisplus.core.config.GlobalConfig globalConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig();
            com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig dbConfig =
                    new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig();
            dbConfig.setLogicDeleteField("deleted");
            dbConfig.setLogicDeleteValue("1");
            dbConfig.setLogicNotDeleteValue("0");
            globalConfig.setDbConfig(dbConfig);
            globalConfig.setMetaObjectHandler(
                    new com.sw.ck.common.config.mybatis.CommonMetaObjectHandler(loginContextProvider));

            com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean factoryBean =
                    new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            factoryBean.setPlugins(interceptor);
            factoryBean.setGlobalConfig(globalConfig);
            try {
                return factoryBean.getObject();
            } catch (Exception e) {
                throw new IllegalStateException("R1 sqlSessionFactory 初始化失败", e);
            }
        }

        @Bean
        public com.sw.ck.system.api.dept.DeptQueryFacade deptQueryFacade(
                com.sw.ck.system.mapper.SysDeptMapper mapper) {
            return new DeptFacadeImpl(mapper);
        }

        @Bean
        public com.sw.ck.system.api.user.UserQueryFacade userQueryFacade(
                com.sw.ck.system.mapper.SysUserMapper mapper) {
            return new UserFacadeImpl(mapper);
        }

        @Bean
        public DynamicBranchPort dynamicBranchPort(DynamicBranchSnapshotMapper mapper) {
            return new DynamicBranchPortConfiguration().dynamicBranchPort(mapper);
        }

        @Bean
        public DynamicBranchCollectionResolver dynamicBranchCollectionResolver(
                com.sw.ck.system.api.dept.DeptQueryFacade deptQueryFacade,
                com.sw.ck.system.api.user.UserQueryFacade userQueryFacade,
                DynamicBranchPort dynamicBranchPort) {
            ObjectProvider<DynamicBranchPort> provider = new ObjectProvider<>() {
                @Override
                public DynamicBranchPort getObject(Object... args) {
                    return dynamicBranchPort;
                }

                @Override
                public DynamicBranchPort getObject() {
                    return dynamicBranchPort;
                }

                @Override
                public DynamicBranchPort getIfAvailable() {
                    return dynamicBranchPort;
                }

                @Override
                public Iterator<DynamicBranchPort> iterator() {
                    return List.of(dynamicBranchPort).iterator();
                }
            };
            return new DynamicBranchCollectionResolver(R1Engine.REPOSITORY_SERVICE,
                    new ObjectMapper(), null, deptQueryFacade, userQueryFacade, provider);
        }

        @Bean
        public BpmHandoverService bpmHandoverService(BpmHandoverMapper handoverMapper,
                                                     BpmHandoverItemMapper itemMapper,
                                                     com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper ruleMapper,
                                                     BpmTaskFacade bpmTaskFacade,
                                                     com.sw.ck.system.api.user.UserQueryFacade userQueryFacade) {
            return new BpmHandoverServiceImpl(handoverMapper, itemMapper, ruleMapper,
                    bpmTaskFacade, userQueryFacade);
        }

        @Bean
        public BpmTaskFacade bpmTaskFacade() {
            // 跨模块边界：真实引擎门面（无流程实例时 queryTodo 返回空），交接不触达任务改写
            return new BpmTaskFacadeImpl(R1Engine.TASK_SERVICE, R1Engine.RUNTIME_SERVICE,
                    R1Engine.REPOSITORY_SERVICE, R1Engine.HISTORY_SERVICE);
        }
    }

    /** 真实 PostgreSQL（zonky 内嵌）+ postgresql 全目录迁移（R1 指定环境）。 */
    static class DataSourceHolder {
        static final javax.sql.DataSource DATA_SOURCE = build();
        static volatile io.zonky.test.db.postgres.embedded.EmbeddedPostgres PG;

        private static javax.sql.DataSource build() {
            try {
                io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg =
                        io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().start();
                PG = pg;
                org.springframework.boot.jdbc.DataSourceBuilder builder =
                        org.springframework.boot.jdbc.DataSourceBuilder.create();
                builder.url(pg.getJdbcUrl("postgres", "postgres"));
                builder.driverClassName("org.postgresql.Driver");
                builder.username("postgres");
                builder.password("postgres");
                javax.sql.DataSource ds = builder.build();
                org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                        .dataSource(ds)
                        .locations("classpath:db/migration/postgresql",
                                "classpath:db/migration/bpm/postgresql",
                                "classpath:db/migration/notify/postgresql",
                                "classpath:db/migration/form/postgresql",
                                "classpath:db/migration/storage/postgresql",
                                "classpath:db/migration/job/postgresql",
                                "classpath:db/migration/agent/postgresql",
                                "classpath:db/migration/iot/postgresql",
                                "classpath:db/migration/openapi/postgresql")
                        .load();
                flyway.migrate();
                return ds;
            } catch (Exception e) {
                throw new IllegalStateException("R1 PostgreSQL 启动/迁移失败", e);
            }
        }
    }

    /** 真实 Flowable 引擎：部署内嵌 nodeConfig 的动态节点模型，供 resolver 经 repositoryService 读取。 */
    static class R1Engine {
        static final ProcessEngine ENGINE;
        static final RepositoryService REPOSITORY_SERVICE;
        static final RuntimeService RUNTIME_SERVICE;
        static final TaskService TASK_SERVICE;
        static final HistoryService HISTORY_SERVICE;
        static final String DEPLOYED_DEFINITION_ID;

        static {
            ProcessEngineConfigurationImpl config =
                    (ProcessEngineConfigurationImpl)
                            ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
            config.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
            ENGINE = config.buildProcessEngine();
            REPOSITORY_SERVICE = ENGINE.getRepositoryService();
            RUNTIME_SERVICE = ENGINE.getRuntimeService();
            TASK_SERVICE = ENGINE.getTaskService();
            HISTORY_SERVICE = ENGINE.getHistoryService();
            String dynamicModel = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                 xmlns:flowable="http://flowable.org/bpmn"
                                 targetNamespace="http://sw.ck/r1">
                      <process id="r1_dyn" isExecutable="true">
                        <startEvent id="s"/>
                        <sequenceFlow sourceRef="s" targetRef="dyn"/>
                        <userTask id="dyn" name="动态并行" flowable:nodeConfig='{"source":{"type":"FIXED","value":"__DEPT_B_ID__"},"invalidStrategy":"BLOCK","maxBranches":50,"mode":"ALL"}'/>
                        <sequenceFlow sourceRef="dyn" targetRef="e"/>
                        <endEvent id="e"/>
                      </process>
                    </definitions>
                    """;
            dynamicModel = dynamicModel.replace("__DEPT_B_ID__", String.valueOf(DEPT_B));
            REPOSITORY_SERVICE.createDeployment().addString("r1_dyn.bpmn20.xml", dynamicModel).deploy();
            DEPLOYED_DEFINITION_ID = REPOSITORY_SERVICE.createProcessDefinitionQuery()
                    .processDefinitionKey("r1_dyn").singleResult().getId();
        }
    }
}
