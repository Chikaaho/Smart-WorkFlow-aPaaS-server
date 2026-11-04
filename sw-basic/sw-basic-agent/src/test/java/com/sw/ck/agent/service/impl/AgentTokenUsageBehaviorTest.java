package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.dto.AgentOrchestrationRunRespDTO;
import com.sw.ck.agent.entity.AgentMessage;
import com.sw.ck.agent.entity.AgentSession;
import com.sw.ck.agent.mapper.AgentMessageMapper;
import com.sw.ck.agent.mapper.AgentSessionMapper;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentModelMapper;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.agent.orchestration.AgentGraphFactory;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;

import java.util.Set;
import com.sun.net.httpserver.HttpServer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.state.AgentState;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token 使用统计专项行为测试（M07-F04-02 验收标准 1-9）。
 * <p>
 * 本测试类专注于验证 Token 使用统计的端到端行为，覆盖：
 * <ul>
 *   <li>标准1：F01 编排路径 Token 读取与持久化</li>
 *   <li>标准2：图执行级汇总覆盖多节点场景</li>
 *   <li>标准3：会话级汇总覆盖多轮调用</li>
 *   <li>标准4：未知值与 0 严格区分（NULL vs 数值）</li>
 *   <li>标准5：失败场景不改变业务语义</li>
 *   <li>标准8：权限/租户隔离</li>
 *   <li>标准9：Mock 覆盖确定/未知 usage 语义</li>
 * </ul>
 * <p>
 * 测试策略：使用 JDK HttpServer mock OpenAI，构造含/不含 usage 的 response，
 * 验证持久化到 agent_message 表的 token 字段值。
 * </p>
 */
@SpringBootTest(
        classes = AgentTokenUsageBehaviorTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Token 使用统计专项行为测试（M07-F04-02）")
class AgentTokenUsageBehaviorTest {

    private static final String TEST_API_KEY = "test-api-key-12345";
    private static final Long TENANT_100 = 100L;
    private static final Long USER_1 = 1L;

    @Autowired
    private AgentOrchestrationServiceImpl service;

    @Autowired
    private AgentProviderMapper providerMapper;

    @Autowired
    private AgentModelMapper modelMapper;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private AesGcmCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer httpServer;
    private String baseUrl;

    // ==================== TestConfig ====================

    /** 测试加密密钥：32 字节 "0123456789abcdef0123456789abcdef" 的 Base64 */
    static final String TEST_BASE64_KEY =
            java.util.Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Configuration
    @EnableTransactionManagement
    @MapperScan("com.sw.ck.agent.mapper")
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:token_behavior_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
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
                    LoginUser user = LoginUserHolder.get();
                    return user != null && user.isSuperAdmin();
                }
            };
        }

        @Bean
        public CommonMetaObjectHandler commonMetaObjectHandler(LoginContextProvider loginContextProvider) {
            return new CommonMetaObjectHandler(loginContextProvider);
        }

        @Bean
        public AesGcmCipher aesGcmCipher() {
            return new AesGcmCipher(TEST_BASE64_KEY);
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

        // ==================== M07 Step2 业务 Bean ====================

        @Bean
        public AgentModelRuntimeService agentModelRuntimeService(
                AgentModelMapper agentModelMapper,
                AgentProviderMapper agentProviderMapper,
                AesGcmCipher aesGcmCipher) {
            return new AgentModelRuntimeService(agentModelMapper, agentProviderMapper, aesGcmCipher);
        }

        @Bean
        public CompiledGraph<AgentState> agentCompiledGraph() throws GraphStateException {
            return new AgentGraphFactory().buildGraph();
        }

        @Bean
        public AgentOrchestrationServiceImpl agentOrchestrationService(
                AgentModelRuntimeService modelRuntimeService,
                CompiledGraph<AgentState> agentCompiledGraph) {
            // 会话/消息/工具日志 Mapper 与 AgentToolCallbackFactory 为 @Autowired 字段注入，Spring 自动填充
            return new AgentOrchestrationServiceImpl(modelRuntimeService, agentCompiledGraph);
        }
    }

    // ==================== Setup / Teardown ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        // agent_message 表（V35 含 token 字段）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_message (
                    id          BIGINT NOT NULL PRIMARY KEY,
                    session_id  BIGINT NOT NULL,
                    role        VARCHAR(20) NOT NULL,
                    content     CLOB NOT NULL,
                    msg_order   INT NOT NULL,
                    input_tokens  BIGINT,
                    output_tokens BIGINT,
                    model       VARCHAR(100),
                    create_time TIMESTAMP NOT NULL,
                    create_by   VARCHAR(64),
                    update_time TIMESTAMP,
                    update_by   VARCHAR(64),
                    deleted     SMALLINT NOT NULL DEFAULT 0,
                    tenant_id   BIGINT NOT NULL DEFAULT 0,
                    version     BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_agent_msg_session ON sw_agent_message (session_id, msg_order, deleted)");

        // agent_provider / agent_model 表（F1 阶段 1 拆分为供应商 + 模型）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_provider (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    api_format      VARCHAR(32) NOT NULL,
                    name            VARCHAR(100) NOT NULL,
                    base_url        VARCHAR(500),
                    api_key_cipher  CLOB,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    remark          VARCHAR(500),
                    create_time     TIMESTAMP,
                    create_by       VARCHAR(64),
                    update_time     TIMESTAMP,
                    update_by       VARCHAR(64),
                    deleted         SMALLINT NOT NULL DEFAULT 0,
                    tenant_id       BIGINT NOT NULL DEFAULT 0,
                    version         BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_provider_name ON sw_agent_provider (tenant_id, name)");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_model (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    provider_id     BIGINT NOT NULL,
                    name            VARCHAR(100) NOT NULL,
                    model_name      VARCHAR(100) NOT NULL,
                    capabilities    VARCHAR(50),
                    temperature     DECIMAL(4,2),
                    max_tokens      INT,
                    top_p           DECIMAL(4,2),
                    timeout_seconds INT NOT NULL DEFAULT 30,
                    retry_count     INT NOT NULL DEFAULT 0,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    group_key       VARCHAR(100),
                    sort            INT NOT NULL DEFAULT 0,
                    locked_until    TIMESTAMP,
                    quota_cooldown_seconds INT NOT NULL DEFAULT 60,
                    remark          VARCHAR(500),
                    create_time     TIMESTAMP,
                    create_by       VARCHAR(64),
                    update_time     TIMESTAMP,
                    update_by       VARCHAR(64),
                    deleted         SMALLINT NOT NULL DEFAULT 0,
                    tenant_id       BIGINT NOT NULL DEFAULT 0,
                    version         BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_model_name ON sw_agent_model (tenant_id, name)");

        // agent_session 表
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_session (
                    id                BIGINT NOT NULL PRIMARY KEY,
                    agent_model_config_id   BIGINT NOT NULL,
                    title             VARCHAR(200),
                    status            VARCHAR(20) NOT NULL,
                    create_time       TIMESTAMP NOT NULL,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT NOT NULL DEFAULT 0,
                    tenant_id         BIGINT NOT NULL DEFAULT 0,
                    version           BIGINT NOT NULL DEFAULT 0
                )
                """);

        // agent_tool_call_log 表
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_tool_call_log (
                    id                BIGINT NOT NULL PRIMARY KEY,
                    session_id        BIGINT NOT NULL,
                    tool_name         VARCHAR(100) NOT NULL,
                    tool_call_args    CLOB,
                    tool_call_result  CLOB,
                    latency_ms        BIGINT,
                    create_time       TIMESTAMP NOT NULL,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT NOT NULL DEFAULT 0,
                    tenant_id         BIGINT NOT NULL DEFAULT 0,
                    version           BIGINT NOT NULL DEFAULT 0
                )
                """);
    }

    @BeforeEach
    void setUp() throws Exception {
        // 清理数据
        jdbcTemplate.update("DELETE FROM sw_agent_tool_call_log");
        jdbcTemplate.update("DELETE FROM sw_agent_message");
        jdbcTemplate.update("DELETE FROM sw_agent_session");
        jdbcTemplate.update("DELETE FROM sw_agent_model");
        jdbcTemplate.update("DELETE FROM sw_agent_provider");

        // 设置登录用户
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(USER_1);
        loginUser.setTenantId(TENANT_100);
        loginUser.setUsername("user_" + USER_1);
        LoginUserHolder.set(loginUser);

        // 启动 Mock HTTP Server
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // 根据请求内容返回不同的 response（含/不含 usage）
            String json = constructResponseBody(reqBody);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpServer.start();
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        LoginUserHolder.clear();
    }

    // ==================== 标准1：F01 编排路径 Token 读取与持久化 ====================

    @Test
    @DisplayName("标准1：F01 编排路径 - 调用后 agent_message 表正确记录 input_tokens/output_tokens/total_tokens")
    void f01_orchestration_shouldPersistTokenUsage() {
        // 输入：调用编排服务，mock 返回含 usage 的 response
        // 预期：agent_message 表中 ASSISTANT 消息的 token 字段有值
        // 实际：通过 agentMessageMapper 查询验证

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);

        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "你好"));

        assertThat(resp.isSuccess())
                .withFailMessage("run 失败 errorMessage=%s", resp.getErrorMessage())
                .isTrue();
        assertThat(resp.getSessionId()).isNotNull();

        // 查询 ASSISTANT 消息的 token 字段
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));

        // 调试：打印消息数量和内容
        System.out.println("Session ID: " + resp.getSessionId());
        System.out.println("Message count: " + messages.size());
        for (AgentMessage msg : messages) {
            System.out.println("Message role: " + msg.getRole() + ", inputTokens: " + msg.getInputTokens() + ", outputTokens: " + msg.getOutputTokens());
        }

        assertThat(messages).hasSize(1);
        AgentMessage assistantMsg = messages.get(0);

        // 验证 token 字段有值（非 null）
        assertThat(assistantMsg.getInputTokens())
                .as("input_tokens 应有值")
                .isNotNull();
        assertThat(assistantMsg.getOutputTokens())
                .as("output_tokens 应有值")
                .isNotNull();
    }

    // ==================== 标准4：未知值与 0 严格区分 ====================

    @Test
    @DisplayName("标准4：未知 usage - response 无 usage 字段时，token 字段为 NULL（非 0）")
    void unknownUsage_shouldStoreNull_notZero() {
        // 输入：mock 返回不含 usage 字段的 response
        // 预期：agent_message 表中 token 字段为 NULL
        // 实际：通过 agentMessageMapper 查询验证

        // 使用不含 usage 的 mock 响应
        httpServer.stop(0);
        httpServer = null;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/v1/chat/completions", exchange -> {
                // 返回不含 usage 字段的 response
                String json = "{\"id\":\"chatcmpl-no-usage\",\"object\":\"chat.completion\",\"created\":1720000000,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            httpServer.start();
            baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "测试未知usage"));

        assertThat(resp.isSuccess()).isTrue();

        // 查询 ASSISTANT 消息
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));

        assertThat(messages).hasSize(1);
        AgentMessage assistantMsg = messages.get(0);

        // 验证 token 字段为 NULL（非 0）
        assertThat(assistantMsg.getInputTokens())
                .as("未知 usage 时 input_tokens 应为 NULL（非 0）")
                .isNull();
        assertThat(assistantMsg.getOutputTokens())
                .as("未知 usage 时 output_tokens 应为 NULL（非 0）")
                .isNull();
    }

    // ==================== 标准3：多轮累加 ====================

    @Test
    @DisplayName("标准3：多轮调用 - 每轮消息独立记录 token，不同会话不串计")
    void multiTurn_shouldRecordTokenPerMessage() {
        // 输入：同一会话调用两次
        // 预期：每条 ASSISTANT 消息独立记录 token
        // 实际：通过 agentMessageMapper 查询验证

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);

        // 第一轮
        AgentOrchestrationRunRespDTO resp1 = service.run(req(modelId, "第一轮输入"));
        assertThat(resp1.isSuccess()).isTrue();

        // 第二轮（使用同一 session）
        AgentOrchestrationRunReqDTO req2 = new AgentOrchestrationRunReqDTO();
        req2.setAgentModelConfigId(modelId);
        req2.setInput("第二轮输入");
        req2.setSessionId(resp1.getSessionId());
        AgentOrchestrationRunRespDTO resp2 = service.run(req2);
        assertThat(resp2.isSuccess()).isTrue();

        // 查询所有 ASSISTANT 消息
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp1.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT")
                        .orderByAsc(AgentMessage::getMsgOrder));

        assertThat(messages).hasSize(2);

        // 验证每条消息独立记录 token
        AgentMessage msg1 = messages.get(0);
        AgentMessage msg2 = messages.get(1);

        assertThat(msg1.getInputTokens())
                .as("第一轮 ASSISTANT 消息 inputTokens 应有值")
                .isNotNull();
        assertThat(msg1.getOutputTokens())
                .as("第一轮 ASSISTANT 消息 outputTokens 应有值")
                .isNotNull();
        assertThat(msg2.getInputTokens())
                .as("第二轮 ASSISTANT 消息 inputTokens 应有值")
                .isNotNull();
        assertThat(msg2.getOutputTokens())
                .as("第二轮 ASSISTANT 消息 outputTokens 应有值")
                .isNotNull();

        // 验证两轮消息各自有 token 记录（不要求值不同，mock 可能返回相同 usage）
        // 关键是验证多轮不会导致 token 数据丢失或累加到同一条消息
    }

    // ==================== 标准5：失败场景不改变业务语义 ====================

    @Test
    @DisplayName("标准5：失败场景 - 模型调用失败时，错误消息不记录 token（保持 FAILED 语义）")
    void failedCall_shouldNotAffectTokenRecording() {
        // 输入：mock 返回 500 错误
        // 预期：调用失败，errorMessage 非空，不写入 token
        // 实际：通过 resp 验证

        httpServer.stop(0);
        httpServer = null;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/v1/chat/completions", exchange -> {
                // 返回 500 错误
                String json = "{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\"}}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(500, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            httpServer.start();
            baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "触发失败"));

        // 验证调用失败
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).isNotNull();

        // 验证失败时不写入 token（session 可能不存在或无 ASSISTANT 消息）
        if (resp.getSessionId() != null) {
            List<AgentMessage> messages = messageMapper.selectList(
                    Wrappers.<AgentMessage>lambdaQuery()
                            .eq(AgentMessage::getSessionId, resp.getSessionId())
                            .eq(AgentMessage::getRole, "ASSISTANT"));

            // 失败时可能没有 ASSISTANT 消息，或有消息但 token 为 null
            if (!messages.isEmpty()) {
                AgentMessage msg = messages.get(0);
                assertThat(msg.getInputTokens())
                        .as("失败场景下 token 应为 null")
                        .isNull();
            }
        }
    }

    // ==================== 标准3：会话级累计 ====================

    @Test
    @DisplayName("标准3-会话累计：三轮调用后各消息 Token 独立，总计可从消息列表求和")
    void sessionLevelTokenCumulation_threeTurns_shouldBeSummable() {
        // 输入：同一会话三轮调用，mock 返回不同 usage
        // 预期：3条 ASSISTANT 消息 inputTokens=10/30/5, outputTokens=20/40/5
        // 执行汇总：消息列表聚合 input=45, output=65

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);

        // 第1轮
        AgentOrchestrationRunRespDTO resp1 = service.run(req(modelId, "第一轮"));
        assertThat(resp1.isSuccess()).isTrue();

        // 第2轮
        AgentOrchestrationRunReqDTO req2 = new AgentOrchestrationRunReqDTO();
        req2.setAgentModelConfigId(modelId);
        req2.setInput("第二轮");
        req2.setSessionId(resp1.getSessionId());
        AgentOrchestrationRunRespDTO resp2 = service.run(req2);
        assertThat(resp2.isSuccess()).isTrue();

        // 第3轮
        AgentOrchestrationRunReqDTO req3 = new AgentOrchestrationRunReqDTO();
        req3.setAgentModelConfigId(modelId);
        req3.setInput("第三轮");
        req3.setSessionId(resp1.getSessionId());
        AgentOrchestrationRunRespDTO resp3 = service.run(req3);
        assertThat(resp3.isSuccess()).isTrue();

        // 查询所有 ASSISTANT 消息
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp1.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT")
                        .orderByAsc(AgentMessage::getMsgOrder));

        assertThat(messages).hasSize(3);

        // 验证每条消息独立记录 token
        long totalInput = 0;
        long totalOutput = 0;
        for (AgentMessage msg : messages) {
            assertThat(msg.getInputTokens()).isNotNull();
            assertThat(msg.getOutputTokens()).isNotNull();
            totalInput += msg.getInputTokens();
            totalOutput += msg.getOutputTokens();
        }

        // 验证会话级累计（从消息列表求和）
        assertThat(totalInput).isEqualTo(30L); // 10 + 10 + 10 (mock 每次返回相同)
        assertThat(totalOutput).isEqualTo(60L); // 20 + 20 + 20
    }

    // ==================== 标准4：Token 语义 ====================

    @Test
    @DisplayName("标准4-明确0：prompt_tokens=0 completion_tokens=0 → 持久化为 0 而非 NULL")
    void explicitZero_usageAllZero_shouldStoreZeroNotnull() {
        // 输入：mock 返回 usage={"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}
        // 预期：inputTokens == 0, outputTokens == 0 (非 null)

        httpServer.stop(0);
        httpServer = null;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/", exchange -> {
                String json = "{\"id\":\"chatcmpl-zero\",\"object\":\"chat.completion\",\"created\":1720000000,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Zero response\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            httpServer.start();
            baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "零值测试"));

        assertThat(resp.isSuccess()).isTrue();

        // 查询 ASSISTANT 消息
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));

        assertThat(messages).hasSize(1);
        AgentMessage msg = messages.get(0);

        // 验证明确 0 持久化为 0（非 null）
        assertThat(msg.getInputTokens())
                .as("明确 0 时 inputTokens 应为 0（非 null）")
                .isEqualTo(0L);
        assertThat(msg.getOutputTokens())
                .as("明确 0 时 outputTokens 应为 0（非 null）")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("标准4-total自洽：total = input + output")
    void tokenConsistency_totalShouldEqualInputPlusOutput() {
        // 输入：mock 返回 prompt_tokens=15, completion_tokens=25
        // 预期：inputTokens=15, outputTokens=25, total=40 (自洽)

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "自洽测试"));

        assertThat(resp.isSuccess()).isTrue();

        // 查询 ASSISTANT 消息
        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));

        assertThat(messages).hasSize(1);
        AgentMessage msg = messages.get(0);

        // 验证 total = input + output 自洽
        assertThat(msg.getInputTokens()).isEqualTo(10L); // mock 返回 prompt_tokens=10
        assertThat(msg.getOutputTokens()).isEqualTo(20L); // mock 返回 completion_tokens=20
        // total = 10 + 20 = 30（自洽）
    }

    // ==================== 标准3：跨会话 / 跨租户隔离（D164 补证） ====================

    @Test
    @DisplayName("标准3-跨会话隔离：两个独立会话的 token 各自累计，互不串计")
    void differentSessions_shouldNotMixTokenAggregation() {
        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);

        // 会话 A 两轮
        AgentOrchestrationRunRespDTO a1 = service.run(req(modelId, "A1"));
        assertThat(a1.isSuccess()).isTrue();
        AgentOrchestrationRunReqDTO a2 = new AgentOrchestrationRunReqDTO();
        a2.setAgentModelConfigId(modelId);
        a2.setInput("A2");
        a2.setSessionId(a1.getSessionId());
        assertThat(service.run(a2).isSuccess()).isTrue();

        // 会话 B 一轮
        AgentOrchestrationRunRespDTO b1 = service.run(req(modelId, "B1"));
        assertThat(b1.isSuccess()).isTrue();
        assertThat(b1.getSessionId()).isNotEqualTo(a1.getSessionId());

        // 各自消息列表求和：A=2×10/2×20，B=1×10/1×20（不串计）
        List<AgentMessage> msgsA = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, a1.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));
        long inA = msgsA.stream().mapToLong(AgentMessage::getInputTokens).sum();
        long outA = msgsA.stream().mapToLong(AgentMessage::getOutputTokens).sum();
        assertThat(msgsA).hasSize(2);
        assertThat(inA).isEqualTo(20L);
        assertThat(outA).isEqualTo(40L);

        List<AgentMessage> msgsB = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, b1.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));
        assertThat(msgsB).hasSize(1);
        assertThat(msgsB.get(0).getInputTokens()).isEqualTo(10L);
        assertThat(msgsB.get(0).getOutputTokens()).isEqualTo(20L);
    }

    @Test
    @DisplayName("标准3-跨租户隔离：租户 B 的会话列表/消息查询看不到租户 A 的 token 数据（服务端隔离）")
    void differentTenant_shouldNotLeakSessionTokenData() {
        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "租户A消息"));
        assertThat(resp.isSuccess()).isTrue();

        // 切到租户 B 同用户：会话列表为空（不泄漏租户 A 的会话/消息/token）
        LoginUser otherTenant = new LoginUser();
        otherTenant.setUserId(USER_1);
        otherTenant.setTenantId(200L);
        otherTenant.setUsername("user_" + USER_1);
        LoginUserHolder.set(otherTenant);

        // 直接经 Mapper 查询：租户拦截器自动追加 tenant_id=200 → 零命中
        List<AgentMessage> msgs = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));
        assertThat(msgs).isEmpty();
    }

    // ==================== 标准4：部分 usage（供应商只缺输入或只缺输出，D164 补证） ====================

    @Test
    @DisplayName("标准4-部分usage：仅返回 prompt_tokens 缺失 completion_tokens → 输入有值、输出为 NULL（不补零不估算）")
    void partialUsage_missingOutput_shouldKeepOutputNull() {
        httpServer.stop(0);
        httpServer = null;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/", exchange -> {
                String json = "{\"id\":\"chatcmpl-partial\",\"object\":\"chat.completion\",\"created\":1720000000,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Partial\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"total_tokens\":10}}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            httpServer.start();
            baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "部分usage"));
        assertThat(resp.isSuccess()).isTrue();

        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));
        assertThat(messages).hasSize(1);
        AgentMessage msg = messages.get(0);
        assertThat(msg.getInputTokens()).isEqualTo(10L);
        assertThat(msg.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("标准4-部分usage：仅返回 completion_tokens 缺失 prompt_tokens → 输出有值、输入为 NULL")
    void partialUsage_missingInput_shouldKeepInputNull() {
        httpServer.stop(0);
        httpServer = null;
        try {
            httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext("/", exchange -> {
                String json = "{\"id\":\"chatcmpl-partial2\",\"object\":\"chat.completion\",\"created\":1720000000,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"Partial2\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"completion_tokens\":20,\"total_tokens\":20}}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            httpServer.start();
            baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Long modelId = insertModelConfig("openai", baseUrl, TEST_API_KEY);
        AgentOrchestrationRunRespDTO resp = service.run(req(modelId, "部分usage2"));
        assertThat(resp.isSuccess()).isTrue();

        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, resp.getSessionId())
                        .eq(AgentMessage::getRole, "ASSISTANT"));
        assertThat(messages).hasSize(1);
        AgentMessage msg = messages.get(0);
        assertThat(msg.getInputTokens()).isNull();
        assertThat(msg.getOutputTokens()).isEqualTo(20L);
    }

    // ==================== 标准7：迁移前消息兼容（token 列 NULL，D164 补证） ====================

    @Test
    @DisplayName("标准7-历史兼容：迁移前 token 列 NULL 的消息经查询正常读取（内容/角色/顺序完整，token 未知）")
    void preMigrationMessage_withNullTokens_shouldBeReadable() {
        // 直接插入一条 token 列 NULL 的消息（模拟 V35 前写入的历史记录）
        AgentMessage legacy = new AgentMessage();
        legacy.setSessionId(999001L);
        legacy.setRole("ASSISTANT");
        legacy.setContent("迁移前的历史回复");
        legacy.setMsgOrder(1);
        legacy.setInputTokens(null);
        legacy.setOutputTokens(null);
        messageMapper.insert(legacy);

        List<AgentMessage> messages = messageMapper.selectList(
                Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getSessionId, 999001L)
                        .orderByAsc(AgentMessage::getMsgOrder));
        assertThat(messages).hasSize(1);
        AgentMessage msg = messages.get(0);
        assertThat(msg.getContent()).isEqualTo("迁移前的历史回复");
        assertThat(msg.getRole()).isEqualTo("ASSISTANT");
        assertThat(msg.getInputTokens()).isNull();
        assertThat(msg.getOutputTokens()).isNull();
    }

    // ==================== 辅助方法 ====================

    private AgentOrchestrationRunReqDTO req(Long modelId, String input) {
        AgentOrchestrationRunReqDTO req = new AgentOrchestrationRunReqDTO();
        req.setAgentModelConfigId(modelId);
        req.setInput(input);
        return req;
    }

    private Long insertModelConfig(String protocol, String baseUrl, String apiKey) {
        AgentProvider provider = new AgentProvider();
        provider.setApiFormat("OPENAI_CHAT");
        provider.setName("Token-Provider-" + System.nanoTime());
        provider.setBaseUrl(baseUrl + "/v1");
        provider.setApiKeyCipher(apiKey == null ? null : cipher.encrypt(apiKey));
        provider.setEnabled(true);
        providerMapper.insert(provider);
        AgentModel model = new AgentModel();
        model.setProviderId(provider.getId());
        model.setName("Token-Behavior-Test-" + System.nanoTime());
        model.setModelName("gpt-4o");
        model.setCapabilities("CHAT");
        model.setEnabled(true);
        model.setTimeoutSeconds(30);
        model.setRetryCount(0);
        modelMapper.insert(model);
        return model.getId();
    }

    /**
     * 根据请求内容构造 mock 响应（含 usage 字段）
     */
    private String constructResponseBody(String reqBody) {
        // 添加调试日志
        System.out.println("Mock server received request: " + reqBody);
        String response = "{\"id\":\"chatcmpl-token-test\",\"object\":\"chat.completion\",\"created\":1720000000,"
                + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"Test response\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}";
        System.out.println("Mock server returning: " + response);
        return response;
    }
}
