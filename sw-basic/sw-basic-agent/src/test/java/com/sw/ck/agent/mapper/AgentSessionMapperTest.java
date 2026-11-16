package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import com.sw.ck.agent.entity.AgentSession;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentSessionMapper} 测试（M07 Step4 §11.1，H2 集成，参照
 * {@code AgentToolConfigServiceImplTest} 风格）。
 * <p>
 * 用例 2 验证租户拦截器隔离：租户 100 插入的会话，租户 200 登录时 selectById 不可见
 * （= run() 的 404 语义与会话查询端点的跨租户边界）。
 * </p>
 */
@SpringBootTest(
        classes = AgentSessionMapperTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 会话主表 Mapper 测试")
class AgentSessionMapperTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_1 = 1L;
    private static final Long CONFIG_10 = 10L;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 建表（V21 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_session (
                    id                    BIGINT NOT NULL PRIMARY KEY,
                    agent_model_config_id BIGINT NOT NULL,
                    title                 VARCHAR(500),
                    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                    create_time           TIMESTAMP NOT NULL,
                    create_by             VARCHAR(64),
                    update_time           TIMESTAMP,
                    update_by             VARCHAR(64),
                    deleted               SMALLINT NOT NULL DEFAULT 0,
                    tenant_id             BIGINT NOT NULL DEFAULT 0,
                    version               BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_session_user ON sw_agent_session (tenant_id, create_by, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_session_cfg ON sw_agent_session (agent_model_config_id, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_session");
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

    private AgentSession newSession() {
        AgentSession session = new AgentSession();
        session.setAgentModelConfigId(CONFIG_10);
        session.setStatus("ACTIVE");
        return session;
    }

    // ==================== 用例 1：insert + selectById ====================

    @Test
    @DisplayName("用例1: insert 后雪花 ID 与审计字段（createBy/tenantId/deleted/version）自动填充，selectById 可回读")
    void insert_thenSelectById_shouldRoundTrip() {
        AgentSession session = newSession();
        sessionMapper.insert(session);

        assertThat(session.getId()).isNotNull();
        AgentSession loaded = sessionMapper.selectById(session.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAgentModelConfigId()).isEqualTo(CONFIG_10);
        assertThat(loaded.getStatus()).isEqualTo("ACTIVE");
        // 审计字段由 MetaObjectHandler 填充
        assertThat(loaded.getCreateTime()).isNotNull();
        assertThat(loaded.getTenantId()).isEqualTo(TENANT_100);
        assertThat(loaded.getDeleted()).isZero();
        assertThat(loaded.getVersion()).isZero();
    }

    // ==================== 用例 2：跨租户隔离 ====================

    @Test
    @DisplayName("用例2: 租户 100 的会话在租户 200 登录下 selectById 不可见（租户拦截器）")
    void crossTenant_shouldBeInvisible() {
        AgentSession session = newSession();
        sessionMapper.insert(session);
        assertThat(sessionMapper.selectById(session.getId())).isNotNull();

        // 切换登录租户 → 同一 id 不可见（拦截器自动追加 tenant_id=200）
        setLoginUser(TENANT_200, USER_1);
        assertThat(sessionMapper.selectById(session.getId())).isNull();
    }

    // ==================== 用例 3：按配置 + 创建人过滤（会话列表查询条件） ====================

    @Test
    @DisplayName("用例3: 按 createBy + agentModelConfigId 过滤（listConversations 同款条件）；其他用户不可见")
    void selectList_byUserAndConfig_shouldFilter() {
        AgentSession s1 = newSession();
        sessionMapper.insert(s1);
        AgentSession s2 = newSession();
        s2.setAgentModelConfigId(11L);
        sessionMapper.insert(s2);

        // 当前用户（USER_1）的会话列表
        List<AgentSession> mine = sessionMapper.selectList(
                Wrappers.<AgentSession>lambdaQuery()
                        .eq(AgentSession::getCreateBy, String.valueOf(USER_1))
                        .eq(AgentSession::getAgentModelConfigId, CONFIG_10));
        assertThat(mine).extracting(AgentSession::getId).containsExactly(s1.getId());

        // 其他用户 → 空列表
        List<AgentSession> others = sessionMapper.selectList(
                Wrappers.<AgentSession>lambdaQuery()
                        .eq(AgentSession::getCreateBy, String.valueOf(99L)));
        assertThat(others).isEmpty();
    }

    // ==================== 组合测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentsession;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
