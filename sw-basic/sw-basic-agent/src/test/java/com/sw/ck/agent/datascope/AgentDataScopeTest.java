package com.sw.ck.agent.datascope;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.agent.dto.AgentGraphExecutionDTO;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.DataScope;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sw_agent_graph_execution 分页数据范围真过滤测试（验收标准 2）。
 * <p>
 * F1 两级模型改造（D022-02）后，供应商/模型管理页不再做部门级数据范围过滤
 * （租户隔离 + 权限码控制可见性），模型配置分页用例已随旧表下线；
 * 图执行记录按 create_by 的部门数据范围过滤保持不变。
 * </p>
 * <p>
 * 两表均无 dept_id 列、归属用户列为 create_by（VARCHAR(64)，存 userId 字符串形态），
 * 等效条件（SELF → create_by = CAST(userId AS VARCHAR)；部门三档 → create_by IN
 * (SELECT CAST(id AS VARCHAR) FROM sys_user WHERE dept_id IN (...))；空集恒假）在
 * {@code selectExecutionPage} 内实现。
 * </p>
 * <p>
 * 用户：u1/u2 ∈ 部门 11，u3 ∈ 111，u4 ∈ 112，u5 ∈ 12（子部门映射：11 → {111, 112}）。
 * 数据：执行记录 5 行，create_by 分别为 '1'..'5'。
 * </p>
 */
@SpringBootTest(
        classes = AgentDataScopeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 图执行记录分页数据范围过滤测试")
class AgentDataScopeTest {

    private static final Long TENANT = 100L;

    @Autowired
    private AgentGraphExecutionService graphExecutionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 建表（V19/V24 + V27 + sys_user 测试支撑表） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_execution (
                    id                BIGINT NOT NULL PRIMARY KEY,
                    graph_def_id      BIGINT NOT NULL,
                    graph_def_version INT NOT NULL,
                    status            VARCHAR(20) NOT NULL,
                    input             CLOB,
                    result_text       CLOB,
                    latency_ms        BIGINT,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    create_time       TIMESTAMP,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT NOT NULL DEFAULT 0,
                    tenant_id         BIGINT NOT NULL DEFAULT 0,
                    version           BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sys_user (
                    id          BIGINT NOT NULL PRIMARY KEY,
                    username    VARCHAR(50) NOT NULL,
                    password    VARCHAR(200) NOT NULL,
                    status      SMALLINT NOT NULL DEFAULT 0,
                    dept_id     BIGINT,
                    create_by   BIGINT,
                    tenant_id   BIGINT NOT NULL DEFAULT 0,
                    deleted     SMALLINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_username ON sys_user (username)");
    }

    @AfterAll
    static void clearLogin() {
        LoginUserHolder.clear();
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_execution");
        jdbcTemplate.update("DELETE FROM sys_user");
        seedUser(1L, 11L, "u1");
        seedUser(2L, 11L, "u2");
        seedUser(3L, 111L, "u3");
        seedUser(4L, 112L, "u4");
        seedUser(5L, 12L, "u5");
        seedExecution(1L, 10L, "1");
        seedExecution(2L, 10L, "2");
        seedExecution(3L, 10L, "3");
        seedExecution(4L, 10L, "4");
        seedExecution(5L, 10L, "5");
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void seedUser(Long id, Long deptId, String username) {
        jdbcTemplate.update("""
                        INSERT INTO sys_user (id, username, password, status, dept_id, tenant_id)
                        VALUES (?, ?, 'x', 0, ?, ?)
                        """,
                id, username, deptId, TENANT);
    }

    private void seedExecution(Long id, Long graphDefId, String createBy) {
        jdbcTemplate.update("""
                        INSERT INTO sw_agent_graph_execution
                        (id, graph_def_id, graph_def_version, status, create_time, create_by, tenant_id)
                        VALUES (?, ?, 1, 'SUCCESS', CURRENT_TIMESTAMP, ?, ?)
                        """,
                id, graphDefId, createBy, TENANT);
    }

    // ==================== 登录上下文 ====================

    private void loginAs(DataScopeType scopeType, Long userId, Long deptId, Set<Long> customDeptIds,
                         boolean superAdmin) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        user.setTenantId(TENANT);
        user.setDeptId(deptId);
        user.setDataScope(DataScope.valueOf(scopeType.name()));
        user.setCustomDeptIds(customDeptIds);
        user.setSuperAdmin(superAdmin);
        LoginUserHolder.set(user);
    }

    private List<Long> executionIds(PageResult<AgentGraphExecutionDTO> result) {
        return result.getRecords().stream().map(AgentGraphExecutionDTO::getId).toList();
    }

    private PageResult<AgentGraphExecutionDTO> pageExecutions() {
        PageParam param = new PageParam();
        param.setPageNum(1);
        param.setPageSize(50);
        return graphExecutionService.pageExecutions(param, null);
    }

    // ==================== sw_agent_graph_execution ====================

    @Nested
    @DisplayName("sw_agent_graph_execution.pageExecutions")
    class GraphExecutionScopeTests {

        @Test
        @DisplayName("ALL：返回全量 5 条")
        void all_shouldReturnAll() {
            loginAs(DataScopeType.ALL, 1L, 11L, Set.of(), false);

            PageResult<AgentGraphExecutionDTO> result = pageExecutions();

            assertThat(result.getTotal()).isEqualTo(5L);
            assertThat(executionIds(result)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        }

        @Test
        @DisplayName("SELF：仅本人（1）执行的 e1")
        void self_shouldReturnOnlyOwn() {
            loginAs(DataScopeType.SELF, 1L, 11L, Set.of(), false);

            PageResult<AgentGraphExecutionDTO> result = pageExecutions();

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(executionIds(result)).containsExactly(1L);
        }

        @Test
        @DisplayName("DEPT：仅本部门（11）成员执行的 e1/e2")
        void dept_shouldReturnOnlySameDeptCreators() {
            loginAs(DataScopeType.DEPT, 1L, 11L, Set.of(), false);

            PageResult<AgentGraphExecutionDTO> result = pageExecutions();

            assertThat(result.getTotal()).isEqualTo(2L);
            assertThat(executionIds(result)).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("DEPT_AND_CHILD：本部门及子部门 → e1..e4")
        void deptAndChild_shouldIncludeChildDeptCreators() {
            loginAs(DataScopeType.DEPT_AND_CHILD, 1L, 11L, Set.of(), false);

            PageResult<AgentGraphExecutionDTO> result = pageExecutions();

            assertThat(result.getTotal()).isEqualTo(4L);
            assertThat(executionIds(result)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        }

        @Test
        @DisplayName("CUSTOM：仅关联部门（111+12）→ e3/e5")
        void custom_shouldReturnOnlyCustomDeptCreators() {
            loginAs(DataScopeType.CUSTOM, 1L, 11L, Set.of(111L, 12L), false);

            PageResult<AgentGraphExecutionDTO> result = pageExecutions();

            assertThat(result.getTotal()).isEqualTo(2L);
            assertThat(executionIds(result)).containsExactlyInAnyOrder(3L, 5L);
        }

        @Test
        @DisplayName("CUSTOM：空关联 → 恒假返回 0 行")
        void custom_withEmptyDeptIds_shouldReturnEmpty() {
            loginAs(DataScopeType.CUSTOM, 1L, 11L, Set.of(), false);

            PageResult<AgentGraphExecutionDTO> result = pageExecutions();

            assertThat(result.getTotal()).isZero();
            assertThat(result.getRecords()).isEmpty();
        }

        @Test
        @DisplayName("SELF + graphDefId：范围条件与业务过滤叠加")
        void self_combinedWithGraphDefIdFilter() {
            loginAs(DataScopeType.SELF, 1L, 11L, Set.of(), false);

            PageParam param = new PageParam();
            param.setPageNum(1);
            param.setPageSize(50);
            PageResult<AgentGraphExecutionDTO> result = graphExecutionService.pageExecutions(param, 10L);

            assertThat(result.getTotal()).isEqualTo(1L);
            assertThat(executionIds(result)).containsExactly(1L);
        }
    }

    // ==================== 测试上下文配置 ====================

    /** 32 字节测试密钥（与 agent 模块既有测试同款，Base64 编码传入 AesGcmCipher） */
    private static final String TEST_BASE64_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.sw.ck.agent.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentds;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            // 与 agent 模块既有测试同款：TestConfig 手动装配（本测试不启用自动配置）
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }

        @Bean
        public com.sw.ck.agent.orchestration.AgentModelRuntimeService agentModelRuntimeService() {
            return org.mockito.Mockito.mock(com.sw.ck.agent.orchestration.AgentModelRuntimeService.class);
        }

        @Bean
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }

                @Override
                public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    LoginUser user = LoginUserHolder.get();
                    if (user == null || user.getDataScope() == null) {
                        return DataScopeType.ALL;
                    }
                    return DataScopeType.valueOf(user.getDataScope().name());
                }

                @Override
                public Set<Long> getCustomDeptIds() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.getCustomDeptIds() != null
                            ? user.getCustomDeptIds() : Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public DeptScopeProvider testDeptScopeProvider() {
            // 与 sys_user / bpm 测试同构的部门树映射：11 → {111, 112}
            return deptId -> {
                if (deptId == null) {
                    return List.of();
                }
                if (deptId == 11L) {
                    return List.of(111L, 112L);
                }
                return List.of();
            };
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public TenantProperties tenantProperties() {
            TenantProperties props = new TenantProperties();
            props.setEnabled(true);
            return props;
        }

        @Bean
        public TenantLineInnerInterceptor tenantLineInnerInterceptor(
                TenantProperties tenantProperties,
                LoginContextProvider loginContextProvider) {
            return new TenantLineInnerInterceptor(
                    new CommonTenantLineHandler(tenantProperties, loginContextProvider));
        }

        @Bean
        public MybatisPlusInterceptor mybatisPlusInterceptor(TenantLineInnerInterceptor tenantLineInnerInterceptor) {
            MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
            interceptor.addInnerInterceptor(tenantLineInnerInterceptor);
            interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
            interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
            return interceptor;
        }

        @Bean
        public org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory(
                DataSource dataSource,
                CommonMetaObjectHandler metaObjectHandler,
                MybatisPlusInterceptor interceptor) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setTypeAliasesPackage("com.sw.ck.agent.entity");
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
        public AgentGraphExecutionService agentGraphExecutionService(
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                com.sw.ck.agent.orchestration.AgentModelRuntimeService modelRuntimeService,
                com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper internalToolMapper,
                com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper externalToolMapper,
                com.sw.ck.agent.mapper.AgentGraphExecutionMapper executionMapper,
                com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper executionNodeMapper,
                LoginContextProvider loginContextProvider,
                DeptScopeProvider deptScopeProvider) {
            return new com.sw.ck.agent.service.impl.AgentGraphExecutionServiceImpl(
                    objectMapper, modelRuntimeService, internalToolMapper, externalToolMapper,
                    executionMapper, executionNodeMapper,
                    loginContextProvider, deptScopeProvider);
        }
    }
}
