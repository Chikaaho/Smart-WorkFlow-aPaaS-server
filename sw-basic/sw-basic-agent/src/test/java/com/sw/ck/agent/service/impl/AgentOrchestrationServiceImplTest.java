package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.dto.AgentOrchestrationRunRespDTO;
import com.sw.ck.agent.entity.AgentMessage;
import com.sw.ck.agent.entity.AgentModelConfig;
import com.sw.ck.agent.entity.AgentSession;
import com.sw.ck.agent.entity.AgentToolCallLog;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentMessageMapper;
import com.sw.ck.agent.mapper.AgentModelConfigMapper;
import com.sw.ck.agent.mapper.AgentSessionMapper;
import com.sw.ck.agent.mapper.AgentToolCallLogMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphFactory;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentOrchestrationService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sun.net.httpserver.HttpExchange;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentOrchestrationServiceImpl} 测试（M07 Step2 §13.2）。
 * <p>
 * 策略与 Step1 的 {@code AgentModelConfigServiceImplTest} 同款：{@code @SpringBootTest}
 * + H2（TestConfig 组合装配）+ {@code @Transactional} 回滚；端到端用 JDK 内置
 * {@code com.sun.net.httpserver.HttpServer}（localhost:0 随机端口）mock OpenAI Chat
 * Completions 服务（监听 baseUrl + {@code /v1/chat/completions}，Spring AI 默认
 * completionsPath，实测确认）。
 * </p>
 * <p>
 * 用例 3 安全断言：模型服务不可达时 {@code errorMessage} 非空且不含明文 API Key
 * （密文经真实 AesGcmCipher 解密后进入客户端构造，异常信息不得泄漏）。
 * </p>
 */
@SpringBootTest(
        classes = AgentOrchestrationServiceImplTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("编排执行引擎 Service 测试")
class AgentOrchestrationServiceImplTest {

    private static final Long TENANT_100 = 100L;
    private static final Long USER_1 = 1L;
    private static final String TEST_API_KEY = "sk-test-123456";

    @Autowired
    private AgentOrchestrationService service;

    @Autowired
    private AgentModelConfigMapper mapper;

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private AgentToolCallLogMapper toolCallLogMapper;

    @Autowired
    private AgentToolInternalConfigMapper toolInternalMapper;

    @Autowired
    private CompiledGraph<AgentState> agentCompiledGraph;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AesGcmCipher cipher;

    // ==================== 建表（V19 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_model_config (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    protocol_type   VARCHAR(32) NOT NULL,
                    base_url        VARCHAR(500) NOT NULL,
                    model_name      VARCHAR(100) NOT NULL,
                    api_key_cipher  CLOB,
                    temperature     DECIMAL(4,2),
                    max_tokens      INT,
                    top_p           DECIMAL(4,2),
                    timeout_seconds INT NOT NULL DEFAULT 30,
                    retry_count     INT NOT NULL DEFAULT 0,
                    enabled         SMALLINT NOT NULL DEFAULT 1,
                    remark          VARCHAR(500),
                    create_time     TIMESTAMP,
                    create_by       VARCHAR(64),
                    update_time     TIMESTAMP,
                    update_by       VARCHAR(64),
                    deleted         SMALLINT NOT NULL DEFAULT 0,
                    tenant_id       BIGINT NOT NULL DEFAULT 0,
                    version         BIGINT NOT NULL DEFAULT 0,
                    group_key       VARCHAR(100),
                    sort            INT NOT NULL DEFAULT 0,
                    locked_until    TIMESTAMP,
                    quota_cooldown_seconds INT NOT NULL DEFAULT 60
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_model_name ON sw_agent_model_config (tenant_id, name)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_tenant_deleted ON sw_agent_model_config (tenant_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_group ON sw_agent_model_config (tenant_id, group_key, sort)");
        // M07 Step4 F04：V21/V22/V23 H2 脚本 DDL（会话主表 + 消息明细 + 工具调用日志）
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
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_message (
                    id            BIGINT NOT NULL PRIMARY KEY,
                    session_id    BIGINT NOT NULL,
                    role          VARCHAR(20) NOT NULL,
                    content       CLOB NOT NULL,
                    msg_order     INT NOT NULL,
                    input_tokens  BIGINT,
                    output_tokens BIGINT,
                    create_time   TIMESTAMP NOT NULL,
                    create_by     VARCHAR(64),
                    update_time   TIMESTAMP,
                    update_by     VARCHAR(64),
                    deleted       SMALLINT NOT NULL DEFAULT 0,
                    tenant_id     BIGINT NOT NULL DEFAULT 0,
                    version       BIGINT NOT NULL DEFAULT 0
                )
                """);
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
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_session_user ON sw_agent_session (tenant_id, create_by, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_msg_session ON sw_agent_message (session_id, msg_order, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tcl_session ON sw_agent_tool_call_log (session_id, deleted)");
        // V20 H2 脚本 DDL（用例 8 端到端 tool_calls 需要内部工具白名单表）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_tool_internal (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    description     VARCHAR(500) NOT NULL,
                    input_schema    CLOB,
                    bean_name       VARCHAR(100) NOT NULL,
                    method_name     VARCHAR(100) NOT NULL,
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
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tool_internal_tenant_deleted ON sw_agent_tool_internal (tenant_id, deleted)");
        // V20 外部工具白名单表（AgentToolCallbackFactory.buildToolCallbacks 同时查询两张表）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_tool_external (
                    id              BIGINT NOT NULL PRIMARY KEY,
                    name            VARCHAR(100) NOT NULL,
                    description     VARCHAR(500) NOT NULL,
                    input_schema    CLOB,
                    url             VARCHAR(500) NOT NULL,
                    http_method     VARCHAR(10) NOT NULL DEFAULT 'POST',
                    effect_scope    VARCHAR(16),
                    timeout_seconds INT NOT NULL DEFAULT 30,
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
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tool_external_tenant_deleted ON sw_agent_tool_external (tenant_id, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_model_config");
        jdbcTemplate.update("DELETE FROM sw_agent_message");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_call_log");
        jdbcTemplate.update("DELETE FROM sw_agent_session");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_internal");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_external");
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

    // ==================== 用例 1：配置不存在 ====================

    @Test
    @DisplayName("用例1: agentModelConfigId 不存在 → 抛 NOT_FOUND 业务异常（404 语义，不进入图执行）")
    void run_unknownId_shouldThrowNotFound() {
        AgentOrchestrationRunReqDTO req = new AgentOrchestrationRunReqDTO();
        req.setAgentModelConfigId(999999L);
        req.setInput("hello");

        assertThatThrownBy(() -> service.run(req))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 2：端到端成功路径 ====================

    @Test
    @DisplayName("用例2: openai 配置 + 本地 mock Chat Completions 服务 → success=true，output 与 mock 回复一致")
    void run_openaiWithMockServer_shouldSucceed() throws Exception {
        HttpServer server = startChatServer();
        try {
            int port = server.getAddress().getPort();
            Long id = insertConfig("openai", "http://127.0.0.1:" + port, TEST_API_KEY);

            AgentOrchestrationRunRespDTO resp = service.run(req(id, "你好"));

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getOutput()).isEqualTo("你好，mock 回复");
            assertThat(resp.getErrorMessage()).isNull();
            assertThat(resp.getLatencyMs()).isGreaterThanOrEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    // ==================== 用例 3：模型服务不可达 ====================

    @Test
    @DisplayName("用例3: mock 服务器未监听 → success=false，errorMessage 非空且不含明文 API Key")
    void run_unreachableServer_shouldReturnFailure() throws Exception {
        int unusedPort = findUnusedPort();
        Long id = insertConfig("openai", "http://127.0.0.1:" + unusedPort, TEST_API_KEY);

        AgentOrchestrationRunRespDTO resp = service.run(req(id, "hello"));

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).isNotBlank();
        // 安全断言：异常摘要不得泄漏明文 Key（方案 §12 风险表）
        assertThat(resp.getErrorMessage())
                .as("errorMessage 不得包含明文 API Key")
                .doesNotContain(TEST_API_KEY)
                .doesNotContain("sk-");
        assertThat(resp.getLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    // ==================== 用例 4-7：M07 Step4 F04 会话持久化 ====================

    @Test
    @DisplayName("用例4: sessionId=null 时 run() 自动创建 sw_agent_session，resp.sessionId 非空且 DB 可查回")
    void run_withoutSessionId_shouldCreateSession() throws Exception {
        HttpServer server = startChatServer();
        try {
            Long id = insertConfig("openai", "http://127.0.0.1:" + server.getAddress().getPort(), TEST_API_KEY);

            AgentOrchestrationRunRespDTO resp = service.run(req(id, "你好"));

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getSessionId()).isNotNull();
            AgentSession session = sessionMapper.selectById(resp.getSessionId());
            assertThat(session).isNotNull();
            assertThat(session.getStatus()).isEqualTo("ACTIVE");
            assertThat(session.getAgentModelConfigId()).isEqualTo(id);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("用例5: 携带已有 sessionId 时 run() 不新建会话，消息追加到现有会话")
    void run_withExistingSession_shouldReuse() throws Exception {
        HttpServer server = startChatServer();
        try {
            Long id = insertConfig("openai", "http://127.0.0.1:" + server.getAddress().getPort(), TEST_API_KEY);
            AgentSession session = new AgentSession();
            session.setAgentModelConfigId(id);
            session.setStatus("ACTIVE");
            sessionMapper.insert(session);

            AgentOrchestrationRunReqDTO req = req(id, "续聊");
            req.setSessionId(session.getId());
            AgentOrchestrationRunRespDTO resp = service.run(req);

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getSessionId()).isEqualTo(session.getId());
            // 会话数不变（不新建）
            assertThat(sessionMapper.selectCount(Wrappers.lambdaQuery())).isEqualTo(1);
            // 消息追加到现有会话
            assertThat(messageMapper.selectCount(
                    Wrappers.<AgentMessage>lambdaQuery()
                            .eq(AgentMessage::getSessionId, session.getId()))).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("用例6: run() 成功后持久化 USER + ASSISTANT 两行，role 精确、msg_order 为 0/1")
    void run_shouldPersistUserAndAssistantMessages() throws Exception {
        HttpServer server = startChatServer();
        try {
            Long id = insertConfig("openai", "http://127.0.0.1:" + server.getAddress().getPort(), TEST_API_KEY);

            AgentOrchestrationRunRespDTO resp = service.run(req(id, "第一轮"));

            assertThat(resp.isSuccess()).isTrue();
            List<AgentMessage> messages = messageMapper.selectList(
                    Wrappers.<AgentMessage>lambdaQuery()
                            .eq(AgentMessage::getSessionId, resp.getSessionId())
                            .orderByAsc(AgentMessage::getMsgOrder));
            assertThat(messages).hasSize(2);
            assertThat(messages).extracting(AgentMessage::getRole).containsExactly("USER", "ASSISTANT");
            assertThat(messages).extracting(AgentMessage::getMsgOrder).containsExactly(0, 1);
            assertThat(messages.get(0).getContent()).isEqualTo("第一轮");
            assertThat(messages.get(1).getContent()).isEqualTo("你好，mock 回复");

            // 第二轮 → msg_order 单调递增（2/3），历史注入顺序正确
            AgentOrchestrationRunRespDTO resp2 = service.run(reqWithSession(id, "第二轮", resp.getSessionId()));
            assertThat(resp2.isSuccess()).isTrue();
            List<AgentMessage> messages2 = messageMapper.selectList(
                    Wrappers.<AgentMessage>lambdaQuery()
                            .eq(AgentMessage::getSessionId, resp.getSessionId())
                            .orderByAsc(AgentMessage::getMsgOrder));
            assertThat(messages2).extracting(AgentMessage::getMsgOrder).containsExactly(0, 1, 2, 3);
            assertThat(messages2).extracting(AgentMessage::getRole)
                    .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("用例7: 模型不可达（invoke 抛异常）后 historyMessages/tools ThreadLocal 均被清除（泄漏检查）")
    void run_failure_shouldClearThreadLocals() throws Exception {
        int unusedPort = findUnusedPort();
        Long id = insertConfig("openai", "http://127.0.0.1:" + unusedPort, TEST_API_KEY);

        AgentOrchestrationRunRespDTO resp = service.run(req(id, "hello"));
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).isNotBlank();

        // 泄漏检查：直接 invoke 图（不绑定历史/工具）——若 clearHistoryMessages/clearTools
        // 未执行，callModel 收到的 Prompt 会包含泄漏的历史消息或 ToolCallingChatOptions
        CapturingChatModel stub = new CapturingChatModel("leak-check");
        AgentGraphFactory.bindChatModel(stub);
        try {
            Optional<AgentState> result = agentCompiledGraph.invoke(
                    Map.of("input", "leak-check", "chatModel", stub));
            assertThat(result).isPresent();
            assertThat(stub.capturedPrompt.getInstructions())
                    .as("run() 失败后历史消息不得残留在 ThreadLocal（bind/clear 对称）")
                    .hasSize(1);
            assertThat(stub.capturedPrompt.getOptions()).isNull();
        } finally {
            AgentGraphFactory.clearChatModel();
        }
    }

    // ==================== 用例 8：端到端 tool_calls → 工具执行 → 日志落库 ====================

    @Test
    @DisplayName("用例8: LLM 返回 tool_calls 时内部工具真实执行，sw_agent_tool_call_log 增加一行（args/result 非空）")
    void run_withToolCalls_shouldPersistToolCallLog() throws Exception {
        // 按请求内容判定的 mock server：请求体含 role=tool 消息（loop 带回工具结果）→ 返回最终文本，
        // 否则返回 tool_calls（arguments 为 JSON 字符串字面量，inputType=String 约定，回执 §3.4 实测）。
        // 基于内容而非调用计数：即使框架层出现重试/乱序，响应语义始终自洽（见 §7 问题 3）。
        final AtomicInteger callCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String json;
            if (reqBody.contains("\"role\":\"tool\"")) {
                json = "{\"id\":\"chatcmpl-tool-2\",\"object\":\"chat.completion\",\"created\":1720000000,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"工具执行完成\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}";
            } else {
                json = "{\"id\":\"chatcmpl-tool-1\",\"object\":\"chat.completion\",\"created\":1720000000,"
                        + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"echo_tool\",\"arguments\":\"\\\"你好\\\"\"}}]},"
                        + "\"finish_reason\":\"tool_calls\"}],"
                        + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}";
            }
            callCount.incrementAndGet();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Long id = insertConfig("openai", "http://127.0.0.1:" + server.getAddress().getPort(), TEST_API_KEY);
            // 白名单内部工具（echo_tool → echoToolBean.execute）
            AgentToolInternalConfig tool = new AgentToolInternalConfig();
            tool.setName("echo_tool");
            tool.setDescription("回声工具");
            tool.setBeanName("echoToolBean");
            tool.setMethodName("execute");
            tool.setEnabled(true);
            toolInternalMapper.insert(tool);

            AgentOrchestrationRunRespDTO resp = service.run(req(id, "调用工具"));

            assertThat(resp.isSuccess())
                    .withFailMessage("run 失败 errorMessage=%s", resp.getErrorMessage())
                    .isTrue();
            assertThat(resp.getOutput()).isEqualTo("工具执行完成");
            assertThat(callCount.get()).isGreaterThanOrEqualTo(2);
            // 工具调用日志落库：args/result 非空
            List<AgentToolCallLog> logs = toolCallLogMapper.selectList(
                    Wrappers.<AgentToolCallLog>lambdaQuery()
                            .eq(AgentToolCallLog::getSessionId, resp.getSessionId()));
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getToolName()).isEqualTo("echo_tool");
            // 实测：落库的 args 是 arguments（JSON 字符串字面量）反序列化后的纯字符串
            assertThat(logs.get(0).getToolCallArgs()).isEqualTo("你好");
            assertThat(logs.get(0).getToolCallResult()).isEqualTo("echo:你好");
            assertThat(logs.get(0).getLatencyMs()).isGreaterThanOrEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    // ==================== 用例 9-14：M07-Step5 多Key轮询/额度限流 ====================

    @Test
    @DisplayName("用例9: 同组高优先级 Key 遇 429 → 锁定并切换到 sort 次之候选 → success=true 且 usedModelConfigId 为第二条")
    void run_switchesToNextKeyOnQuotaExceeded() throws Exception {
        AtomicInteger hits429 = new AtomicInteger();
        HttpServer server429 = start429Server(hits429);
        HttpServer server200 = startChatServer();
        try {
            // 同组 2 条：sort 0 指向 429 服务，sort 1 指向正常服务（retryCount=0 → maxAttempts=1，不重试）
            Long idA = insertGroupConfig("openai", "http://127.0.0.1:" + server429.getAddress().getPort(),
                    TEST_API_KEY, "g-switch", 0);
            Long idB = insertGroupConfig("openai", "http://127.0.0.1:" + server200.getAddress().getPort(),
                    TEST_API_KEY, "g-switch", 1);

            AgentOrchestrationRunRespDTO resp = service.run(req(idA, "你好"));

            assertThat(resp.isSuccess())
                    .withFailMessage("run 失败 errorMessage=%s", resp.getErrorMessage())
                    .isTrue();
            assertThat(resp.getOutput()).isEqualTo("你好，mock 回复");
            // 实际服务本次请求的配置 id 为切换后的第二条
            assertThat(resp.getUsedModelConfigId()).isEqualTo(idB);
            // 429 服务恰好被请求 1 次（切换后不再尝试已限流 Key），正常服务恰好 1 次
            assertThat(hits429.get()).isEqualTo(1);
        } finally {
            server429.stop(0);
            server200.stop(0);
        }
    }

    @Test
    @DisplayName("用例10: 限流触发后当前配置 lockedUntil 被持久化为 now + quotaCooldownSeconds")
    void run_locksCurrentConfigOnQuotaExceeded() throws Exception {
        AtomicInteger hits429 = new AtomicInteger();
        HttpServer server429 = start429Server(hits429);
        HttpServer server200 = startChatServer();
        try {
            Long idA = insertGroupConfig("openai", "http://127.0.0.1:" + server429.getAddress().getPort(),
                    TEST_API_KEY, "g-lock", 0);
            Long idB = insertGroupConfig("openai", "http://127.0.0.1:" + server200.getAddress().getPort(),
                    TEST_API_KEY, "g-lock", 1);

            AgentOrchestrationRunRespDTO resp = service.run(req(idA, "你好"));
            assertThat(resp.isSuccess()).isTrue();

            LocalDateTime lockedUntil = mapper.selectById(idA).getLockedUntil();
            assertThat(lockedUntil).as("限流后当前配置必须被锁定").isNotNull();
            // quotaCooldownSeconds=60 → lockedUntil ≈ now+60s（宽容边界：不早于 now-10s，不晚于 now+70s）
            assertThat(lockedUntil).isAfter(LocalDateTime.now().minusSeconds(10));
            assertThat(lockedUntil).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(70));
            // 切换到的候选不被锁定
            assertThat(mapper.selectById(idB).getLockedUntil()).isNull();
        } finally {
            server429.stop(0);
            server200.stop(0);
        }
    }

    @Test
    @DisplayName("用例11: 组内候选全部限流 → 每条恰好尝试 1 次后 success=false，无多余重试")
    void run_failsWhenAllCandidatesExhausted() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicInteger hitsB = new AtomicInteger();
        AtomicInteger hitsC = new AtomicInteger();
        HttpServer server429a = start429Server(hitsA);
        HttpServer server429b = start429Server(hitsB);
        HttpServer server429c = start429Server(hitsC);
        try {
            Long idA = insertGroupConfig("openai", "http://127.0.0.1:" + server429a.getAddress().getPort(),
                    TEST_API_KEY, "g-exhaust", 0);
            Long idB = insertGroupConfig("openai", "http://127.0.0.1:" + server429b.getAddress().getPort(),
                    TEST_API_KEY, "g-exhaust", 1);
            Long idC = insertGroupConfig("openai", "http://127.0.0.1:" + server429c.getAddress().getPort(),
                    TEST_API_KEY, "g-exhaust", 2);

            AgentOrchestrationRunRespDTO resp = service.run(req(idA, "你好"));

            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorMessage()).isNotBlank();
            assertThat(resp.getUsedModelConfigId()).isNull();
            // 组内 3 条各恰好尝试 1 次（triedIds 去重，无多余重试）
            assertThat(hitsA.get()).isEqualTo(1);
            assertThat(hitsB.get()).isEqualTo(1);
            assertThat(hitsC.get()).isEqualTo(1);
            // 三条均被锁定
            assertThat(mapper.selectById(idA).getLockedUntil()).isNotNull();
            assertThat(mapper.selectById(idB).getLockedUntil()).isNotNull();
            assertThat(mapper.selectById(idC).getLockedUntil()).isNotNull();
        } finally {
            server429a.stop(0);
            server429b.stop(0);
            server429c.stop(0);
        }
    }

    @Test
    @DisplayName("用例12: groupKey=null 的独立配置遇 429 直接失败，不查询任何候选（向后兼容）")
    void run_noSwitchWhenGroupKeyNull() throws Exception {
        AtomicInteger hits429 = new AtomicInteger();
        AtomicInteger hits200 = new AtomicInteger();
        HttpServer server429 = start429Server(hits429);
        HttpServer server200 = startCountingChatServer(hits200);
        try {
            // 独立配置（无组）+ 其他组的正常候选：均不得被使用
            Long idSolo = insertGroupConfig("openai", "http://127.0.0.1:" + server429.getAddress().getPort(),
                    TEST_API_KEY, null, 0);
            insertGroupConfig("openai", "http://127.0.0.1:" + server200.getAddress().getPort(),
                    TEST_API_KEY, "g-other", 0);

            AgentOrchestrationRunRespDTO resp = service.run(req(idSolo, "你好"));

            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorMessage()).isNotBlank();
            assertThat(hits429.get()).isEqualTo(1);
            assertThat(hits200.get()).as("独立配置不得切换到其他组的候选").isZero();
            // 独立配置也不被锁定（groupKey null 不进入锁定/切换分支）
            assertThat(mapper.selectById(idSolo).getLockedUntil()).isNull();
        } finally {
            server429.stop(0);
            server200.stop(0);
        }
    }

    @Test
    @DisplayName("用例13: 非 429 异常（连接拒绝）不触发候选切换，行为与 Step2-4 一致")
    void run_noSwitchOnNonQuotaException() throws Exception {
        int unusedPort = findUnusedPort();
        AtomicInteger hits200 = new AtomicInteger();
        HttpServer server200 = startCountingChatServer(hits200);
        try {
            // 同组 2 条：sort 0 指向未监听端口（连接拒绝 → 网络异常），sort 1 正常
            Long idA = insertGroupConfig("openai", "http://127.0.0.1:" + unusedPort,
                    TEST_API_KEY, "g-nonquota", 0);
            Long idB = insertGroupConfig("openai", "http://127.0.0.1:" + server200.getAddress().getPort(),
                    TEST_API_KEY, "g-nonquota", 1);

            AgentOrchestrationRunRespDTO resp = service.run(req(idA, "你好"));

            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorMessage()).isNotBlank();
            // 非限流异常：不锁定、不切换
            assertThat(mapper.selectById(idA).getLockedUntil()).isNull();
            assertThat(mapper.selectById(idB).getLockedUntil()).isNull();
            assertThat(hits200.get()).as("非 429 异常不得触发候选切换").isZero();
        } finally {
            server200.stop(0);
        }
    }

    @Test
    @DisplayName("用例14: isQuotaExceededException 按 V1 实测语义判断 429（NonTransientAiException 消息 + RestClientResponseException 兜底 + 沿 cause 链）")
    void isQuotaExceededException_detects429() throws Exception {
        Method m = AgentOrchestrationServiceImpl.class
                .getDeclaredMethod("isQuotaExceededException", Throwable.class);
        m.setAccessible(true);

        // V1 实测形态：Spring AI 默认错误处理器对 4xx 直接抛 NonTransientAiException("429 - ...")
        assertThat(invokeQuotaCheck(m, new NonTransientAiException("429 - too many requests"))).isTrue();
        // 其他状态码不命中
        assertThat(invokeQuotaCheck(m, new NonTransientAiException("401 - unauthorized"))).isFalse();
        assertThat(invokeQuotaCheck(m, new NonTransientAiException("500 - server error"))).isFalse();
        // 非 Spring AI 异常不命中
        assertThat(invokeQuotaCheck(m, new IllegalArgumentException("bad config"))).isFalse();
        // 沿 cause 链穿透包装层（langgraph4j CompletionException 同款）
        assertThat(invokeQuotaCheck(m,
                new CompletionException(new NonTransientAiException("429 - too many requests")))).isTrue();
        // RestClientResponseException 兜底：真实 HttpClientErrorException.TooManyRequests 实例
        HttpClientErrorException tooMany = HttpClientErrorException.create(
                HttpStatusCode.valueOf(429), "Too Many Requests",
                new HttpHeaders(), "too many".getBytes(StandardCharsets.UTF_8), null);
        assertThat(invokeQuotaCheck(m, tooMany)).isTrue();
        // 非 429 的 RestClientResponseException 不命中
        HttpClientErrorException serverError = HttpClientErrorException.create(
                HttpStatusCode.valueOf(500), "Server Error",
                new HttpHeaders(), "boom".getBytes(StandardCharsets.UTF_8), null);
        assertThat(invokeQuotaCheck(m, serverError)).isFalse();
        // null 不命中
        assertThat(invokeQuotaCheck(m, null)).isFalse();
    }

    private boolean invokeQuotaCheck(Method m, Throwable t) throws Exception {
        return (boolean) m.invoke(service, t);
    }

    // ==================== 用例15-16：候选切换/失败链的 token 语义（M07-F04-02 D164 补证） ====================

    @Test
    @DisplayName("用例15: 标准5 — 候选切换后成功轮次只落当前生产调用链的 usage：429 失败轮不落 token，切换成功轮落 3/5")
    void run_switchSuccess_shouldPersistOnlyCurrentCallChainUsage() throws Exception {
        AtomicInteger hits429 = new AtomicInteger();
        HttpServer server429 = start429Server(hits429);
        HttpServer server200 = startChatServer();
        try {
            Long idA = insertGroupConfig("openai", "http://127.0.0.1:" + server429.getAddress().getPort(),
                    TEST_API_KEY, "g-token-switch", 0);
            Long idB = insertGroupConfig("openai", "http://127.0.0.1:" + server200.getAddress().getPort(),
                    TEST_API_KEY, "g-token-switch", 1);

            AgentOrchestrationRunRespDTO resp = service.run(req(idA, "你好"));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getUsedModelConfigId()).isEqualTo(idB);

            // 成功轮次（候选 B）的 ASSISTANT 消息落库 usage（3/5）——失败轮（429）不产生消息/token
            List<AgentMessage> messages = messageMapper.selectList(
                    Wrappers.<AgentMessage>lambdaQuery()
                            .eq(AgentMessage::getSessionId, resp.getSessionId())
                            .eq(AgentMessage::getRole, "ASSISTANT"));
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getInputTokens()).isEqualTo(3L);
            assertThat(messages.get(0).getOutputTokens()).isEqualTo(5L);
        } finally {
            server429.stop(0);
            server200.stop(0);
        }
    }

    @Test
    @DisplayName("用例16: 标准5 — 有 usage 后调用失败（500）→ 不把已取得的 usage 当成功事实写入，无 ASSISTANT 消息 token")
    void run_failureAfterUsage_shouldNotRecordTokenFacts() throws Exception {
        AtomicInteger hits500 = new AtomicInteger();
        HttpServer server500 = start500Server(hits500);
        try {
            Long id = insertGroupConfig("openai", "http://127.0.0.1:" + server500.getAddress().getPort(),
                    TEST_API_KEY, null, 0);

            AgentOrchestrationRunRespDTO resp = service.run(req(id, "触发失败"));
            assertThat(resp.isSuccess()).isFalse();
            assertThat(resp.getErrorMessage()).isNotBlank();
            assertThat(hits500.get()).isEqualTo(1);

            // 失败请求：会话可能不存在或无 ASSISTANT 消息；即使存在也不得写入 token 事实
            if (resp.getSessionId() != null) {
                List<AgentMessage> messages = messageMapper.selectList(
                        Wrappers.<AgentMessage>lambdaQuery()
                                .eq(AgentMessage::getSessionId, resp.getSessionId())
                                .eq(AgentMessage::getRole, "ASSISTANT"));
                assertThat(messages).isEmpty();
            }
        } finally {
            server500.stop(0);
        }
    }

    /** 返回 500 的服务桩（计数） */
    private HttpServer start500Server(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            String json = "{\"error\":{\"message\":\"server exploded\",\"type\":\"server_error\"}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    // ==================== 测试数据工厂 ====================

    private AgentOrchestrationRunReqDTO req(Long id, String input) {
        AgentOrchestrationRunReqDTO req = new AgentOrchestrationRunReqDTO();
        req.setAgentModelConfigId(id);
        req.setInput(input);
        return req;
    }

    private AgentOrchestrationRunReqDTO reqWithSession(Long id, String input, Long sessionId) {
        AgentOrchestrationRunReqDTO req = req(id, input);
        req.setSessionId(sessionId);
        return req;
    }

    /** 记录收到的 Prompt 的 ChatModel 桩（ThreadLocal 泄漏检查用） */
    static class CapturingChatModel implements ChatModel {
        private final String reply;
        Prompt capturedPrompt;

        CapturingChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.capturedPrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    private Long insertConfig(String protocol, String baseUrl, String apiKey) {
        AgentModelConfig entity = new AgentModelConfig();
        entity.setName("orch-test-" + System.nanoTime());
        entity.setProtocolType(protocol);
        entity.setBaseUrl(baseUrl);
        entity.setModelName("gpt-4o");
        entity.setApiKeyCipher(apiKey == null ? null : cipher.encrypt(apiKey));
        entity.setTimeoutSeconds(10);
        entity.setRetryCount(0);
        entity.setEnabled(true);
        mapper.insert(entity);
        return entity.getId();
    }

    /** 多Key轮询组配置：同 insertConfig + groupKey/sort/quotaCooldownSeconds=60 */
    private Long insertGroupConfig(String protocol, String baseUrl, String apiKey, String groupKey, int sort) {
        AgentModelConfig entity = new AgentModelConfig();
        entity.setName("orch-group-" + System.nanoTime());
        entity.setProtocolType(protocol);
        entity.setBaseUrl(baseUrl);
        entity.setModelName("gpt-4o");
        entity.setApiKeyCipher(apiKey == null ? null : cipher.encrypt(apiKey));
        entity.setTimeoutSeconds(10);
        entity.setRetryCount(0);
        entity.setEnabled(true);
        entity.setGroupKey(groupKey);
        entity.setSort(sort);
        entity.setQuotaCooldownSeconds(60);
        mapper.insert(entity);
        return entity.getId();
    }

    // ==================== 本地假 OpenAI Chat Completions 服务 ====================

    private HttpServer startChatServer() throws IOException {
        return startCountingChatServer(new AtomicInteger());
    }

    /** 恒 200 Chat Completions 服务（请求计数，多Key轮询用例用） */
    private HttpServer startCountingChatServer(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            // 响应结构为 OpenAI Chat Completions 合法 JSON（Spring AI 1.0.4 实测可解析）
            String json = "{\"id\":\"chatcmpl-test-1\",\"object\":\"chat.completion\",\"created\":1720000000,"
                    + "\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"你好，mock 回复\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    /** 恒 HTTP 429 服务（请求计数；M07-Step5 限流场景，Spike 同款手法） */
    private HttpServer start429Server(AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "too many requests".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static int findUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    // ==================== 组合测试配置 ====================

    /** 测试加密密钥：32 字节 "0123456789abcdef0123456789abcdef" 的 Base64（Step1 同款） */
    static final String TEST_BASE64_KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentorch;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public AesGcmCipher aesGcmCipher() {
            return new AesGcmCipher(TEST_BASE64_KEY);
        }

        // ==================== M07 Step2 业务 Bean ====================

        @Bean
        public ChatModelFactory chatModelFactory() {
            return new ChatModelFactory();
        }

        @Bean
        public CompiledGraph<AgentState> agentCompiledGraph() throws GraphStateException {
            return new AgentGraphFactory().buildGraph();
        }

        // ==================== M07 Step4 F04 工具链 Bean（用例 8 端到端 tool_calls） ====================

        /** 白名单内部工具测试 bean（bean 名 = 方法名 echoToolBean） */
        @Bean
        public EchoToolBean echoToolBean() {
            return new EchoToolBean();
        }

        @Bean
        public AgentToolCallbackFactory agentToolCallbackFactory(
                AgentToolInternalConfigMapper agentToolInternalConfigMapper,
                AgentToolExternalConfigMapper agentToolExternalConfigMapper,
                ApplicationContext applicationContext) {
            return new AgentToolCallbackFactory(
                    agentToolInternalConfigMapper, agentToolExternalConfigMapper, applicationContext);
        }

        @Bean
        public AgentOrchestrationService agentOrchestrationService(
                AgentModelConfigMapper agentModelConfigMapper,
                AesGcmCipher aesGcmCipher,
                ChatModelFactory chatModelFactory,
                CompiledGraph<AgentState> agentCompiledGraph) {
            return new AgentOrchestrationServiceImpl(
                    agentModelConfigMapper, aesGcmCipher, chatModelFactory, agentCompiledGraph);
        }
    }

    /** 白名单内部工具 mock bean：约定签名 String execute(String params) */
    public static class EchoToolBean {
        public String execute(String params) {
            return "echo:" + params;
        }
    }
}
