package com.sw.ck.agent.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;

import com.sw.ck.agent.entity.AgentMessage;
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
 * {@link AgentMessageMapper} 测试（M07 Step4 §11.1，H2 集成）。
 * <p>
 * 会话内消息查询（selectBySessionId 语义）经 Wrappers 链式构造（agent 模块惯例，
 * 现场验证 V3），断言 msg_order 升序与 USER/ASSISTANT 角色精确性。
 * </p>
 */
@SpringBootTest(
        classes = AgentMessageMapperTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 会话消息 Mapper 测试")
class AgentMessageMapperTest {

    private static final Long TENANT_100 = 100L;
    private static final Long USER_1 = 1L;
    private static final Long SESSION_1 = 10001L;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 建表（V22 H2 脚本 DDL + V35 token 字段） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_message (
                    id          BIGINT NOT NULL PRIMARY KEY,
                    session_id  BIGINT NOT NULL,
                    role        VARCHAR(20) NOT NULL,
                    content     CLOB NOT NULL,
                    msg_order   INT NOT NULL,
                    input_tokens BIGINT,
                    output_tokens BIGINT,
                    create_time TIMESTAMP NOT NULL,
                    create_by   VARCHAR(64),
                    update_time TIMESTAMP,
                    update_by   VARCHAR(64),
                    deleted     SMALLINT NOT NULL DEFAULT 0,
                    tenant_id   BIGINT NOT NULL DEFAULT 0,
                    version     BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_msg_session ON sw_agent_message (session_id, msg_order, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_message");
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

    private AgentMessage newMessage(int msgOrder, String role, String content) {
        AgentMessage msg = new AgentMessage();
        msg.setSessionId(SESSION_1);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMsgOrder(msgOrder);
        return msg;
    }

    // ==================== 用例 1：insert + 会话内查询（msg_order 升序 + 角色） ====================

    @Test
    @DisplayName("用例1: USER/ASSISTANT 两行写入后，按 sessionId 查询 msg_order 升序、角色与内容精确")
    void insert_thenSelectBySessionId_shouldOrderAndMatchRoles() {
        messageMapper.insert(newMessage(0, "USER", "第一轮输入"));
        messageMapper.insert(newMessage(1, "ASSISTANT", "第一轮回复"));
        messageMapper.insert(newMessage(2, "USER", "第二轮输入"));
        messageMapper.insert(newMessage(3, "ASSISTANT", "第二轮回复"));

        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, SESSION_1)
                        .orderByAsc(AgentMessage::getMsgOrder));

        assertThat(messages).hasSize(4);
        assertThat(messages).extracting(AgentMessage::getMsgOrder).containsExactly(0, 1, 2, 3);
        assertThat(messages).extracting(AgentMessage::getRole)
                .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(messages).extracting(AgentMessage::getContent)
                .containsExactly("第一轮输入", "第一轮回复", "第二轮输入", "第二轮回复");
    }

    // ==================== 用例 2：多轮 msg_order 单调递增（历史注入顺序依据） ====================

    @Test
    @DisplayName("用例2: 第 N 轮写入的 msg_order = 已有消息数（0-based 单调递增），回读顺序即多轮上下文顺序")
    void msgOrder_shouldBeMonotonic() {
        for (int i = 0; i < 3; i++) {
            messageMapper.insert(newMessage(i * 2, "USER", "输入" + i));
            messageMapper.insert(newMessage(i * 2 + 1, "ASSISTANT", "回复" + i));
        }

        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, SESSION_1)
                        .orderByAsc(AgentMessage::getMsgOrder));

        // 下一轮顺序号 = 已有消息数 = 6（ServiceImpl 计算逻辑同款）
        assertThat(messages).hasSize(6);
        int nextOrder = messages.size();
        assertThat(nextOrder).isEqualTo(6);
        assertThat(messages).extracting(AgentMessage::getMsgOrder)
                .isSorted();
    }

    // ==================== 用例 3：空会话返回空列表 ====================

    @Test
    @DisplayName("用例3: 无消息的会话查询返回空列表（首轮无历史）")
    void emptySession_shouldReturnEmptyList() {
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, SESSION_1)
                        .orderByAsc(AgentMessage::getMsgOrder));

        assertThat(messages).isEmpty();
    }

    // ==================== 组合测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentmsg;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
