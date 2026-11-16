package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import com.sw.ck.agent.entity.AgentToolCallLog;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentToolCallLogMapper} 测试（M07 Step4 §11.1，H2 集成）。
 * <p>
 * CLOB 大字段（>1KB JSON）写入后可原样读回（H2=CLOB 类型约定验证）。
 * </p>
 */
@SpringBootTest(
        classes = AgentToolCallLogMapperTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 工具调用日志 Mapper 测试")
class AgentToolCallLogMapperTest {

    private static final Long TENANT_100 = 100L;
    private static final Long USER_1 = 1L;
    private static final Long SESSION_1 = 20001L;

    @Autowired
    private AgentToolCallLogMapper toolCallLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 建表（V23 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_tool_call_log (
                    id               BIGINT NOT NULL PRIMARY KEY,
                    session_id       BIGINT NOT NULL,
                    tool_name        VARCHAR(100) NOT NULL,
                    tool_call_args   CLOB,
                    tool_call_result CLOB,
                    latency_ms       BIGINT,
                    create_time      TIMESTAMP NOT NULL,
                    create_by        VARCHAR(64),
                    update_time      TIMESTAMP,
                    update_by        VARCHAR(64),
                    deleted          SMALLINT NOT NULL DEFAULT 0,
                    tenant_id        BIGINT NOT NULL DEFAULT 0,
                    version          BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tcl_session ON sw_agent_tool_call_log (session_id, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_tool_call_log");
        setLoginUser(TENANT_100, USER_1);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void setLoginUser(Long tenantId, Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setUsername("user_" + userId);
        LoginUserHolder.set(loginUser);
    }

    /** 构造 >1KB 的 JSON 参数字符串（CLOB 大字段验证） */
    private String bigJson(String prefix) {
        StringBuilder sb = new StringBuilder("{\"prefix\":\"").append(prefix).append("\",\"padding\":\"");
        sb.append(UUID.randomUUID().toString().repeat(50));
        sb.append("\"}");
        return sb.toString();
    }

    // ==================== 用例 1：CLOB 大字段写入读回 ====================

    @Test
    @DisplayName("用例1: >1KB 的 args/result JSON 写入后原样读回（CLOB 字段）")
    void insert_thenReadBackBigClob() {
        String args = bigJson("args");
        String result = bigJson("result");
        assertThat(args.length()).isGreaterThan(1024);

        AgentToolCallLog log = new AgentToolCallLog();
        log.setSessionId(SESSION_1);
        log.setToolName("sum_tool");
        log.setToolCallArgs(args);
        log.setToolCallResult(result);
        log.setLatencyMs(12L);
        toolCallLogMapper.insert(log);

        AgentToolCallLog loaded = toolCallLogMapper.selectById(log.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getToolName()).isEqualTo("sum_tool");
        assertThat(loaded.getToolCallArgs()).isEqualTo(args);
        assertThat(loaded.getToolCallResult()).isEqualTo(result);
        assertThat(loaded.getLatencyMs()).isEqualTo(12L);
        assertThat(loaded.getCreateTime()).isNotNull();
    }

    // ==================== 用例 2：按会话过滤 ====================

    @Test
    @DisplayName("用例2: 按 sessionId 查询仅返回该会话的工具调用日志")
    void selectList_bySessionId_shouldFilter() {
        AgentToolCallLog log1 = new AgentToolCallLog();
        log1.setSessionId(SESSION_1);
        log1.setToolName("tool_a");
        log1.setLatencyMs(1L);
        toolCallLogMapper.insert(log1);

        AgentToolCallLog log2 = new AgentToolCallLog();
        log2.setSessionId(999L);
        log2.setToolName("tool_b");
        log2.setLatencyMs(2L);
        toolCallLogMapper.insert(log2);

        List<AgentToolCallLog> mine = toolCallLogMapper.selectList(
                Wrappers.<AgentToolCallLog>lambdaQuery()
                        .eq(AgentToolCallLog::getSessionId, SESSION_1));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).getToolName()).isEqualTo("tool_a");
    }

    // ==================== 组合测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agenttcl;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
                    return null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    return DataScopeType.ALL;
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
        public SqlSessionFactory sqlSessionFactory(
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
    }
}
