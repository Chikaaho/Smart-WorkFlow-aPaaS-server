package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sw.ck.agent.dto.AgentOrchestrationRunReqDTO;
import com.sw.ck.agent.dto.AgentOrchestrationRunRespDTO;
import com.sw.ck.agent.dto.AgentToolExternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolExternalConfigQuery;
import com.sw.ck.agent.dto.AgentToolInternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolInternalConfigQuery;
import com.sw.ck.agent.entity.AgentModelConfig;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentModelConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentGraphFactory;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import com.sw.ck.agent.orchestration.ChatModelFactory;
import com.sw.ck.agent.service.AgentOrchestrationService;
import com.sw.ck.agent.service.AgentToolConfigService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentToolConfigService} 测试（M07 Step3 §10，H2 集成，参照
 * {@code AgentModelConfigServiceImplTest} 风格：{@code @SpringBootTest} + TestConfig
 * 组合装配 + {@code @Transactional} 每用例回滚）。
 * <p>
 * 内部/外部工具 CRUD 全组覆盖（创建/查询/更新/删除/启用禁用/分页 + 校验 + 工厂侧
 * 查询）。另含编排集成（用例 7）：TestConfig 装配完整编排栈（ChatModelFactory +
 * CompiledGraph + AgentToolCallbackFactory + AgentOrchestrationServiceImpl），验证
 * 白名单工具经 {@code run()} 加载绑定、正常完成与异常完成（模型服务不可达）后
 * {@code clearTools()} 均执行（无 ThreadLocal 泄漏）——泄漏可观测点：run() 返回后
 * 直接 invoke 图（不绑定工具），若泄漏则 callModel 的 Prompt 会携带
 * {@code ToolCallingChatOptions}。
 * </p>
 */
@SpringBootTest(
        classes = AgentToolConfigServiceImplTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("工具沙箱配置 Service 测试")
class AgentToolConfigServiceImplTest {

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_1 = 1L;
    private static final String TEST_API_KEY = "sk-test-123456";

    @Autowired
    private AgentToolConfigService service;

    @Autowired
    private AgentToolInternalConfigMapper internalMapper;

    @Autowired
    private AgentToolExternalConfigMapper externalMapper;

    @Autowired
    private AgentModelConfigMapper modelMapper;

    @Autowired
    private AgentOrchestrationService orchestrationService;

    @Autowired
    private CompiledGraph<AgentState> agentCompiledGraph;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AesGcmCipher cipher;

    // ==================== 建表（V19 + V20 H2 脚本 DDL） ====================

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
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_model_name ON sw_agent_model_config (tenant_id, name)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_group ON sw_agent_model_config (tenant_id, group_key, sort)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tool_internal_tenant_deleted ON sw_agent_tool_internal (tenant_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tool_external_tenant_deleted ON sw_agent_tool_external (tenant_id, deleted)");
        // M07 Step4 F04：V21/V22/V23 H2 脚本 DDL（用例 7 端到端 run() 需写会话/消息/工具日志）
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
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_tool_internal");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_external");
        jdbcTemplate.update("DELETE FROM sw_agent_model_config");
        jdbcTemplate.update("DELETE FROM sw_agent_message");
        jdbcTemplate.update("DELETE FROM sw_agent_tool_call_log");
        jdbcTemplate.update("DELETE FROM sw_agent_session");
        setLoginUser(TENANT_100, USER_1);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
        // 兜底清理编排 ThreadLocal（防用例间相互污染）
        AgentGraphFactory.clearChatModel();
        AgentGraphFactory.clearTools();
    }

    private void setLoginUser(Long tenantId, Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setTenantId(tenantId);
        loginUser.setUsername("user_" + userId);
        LoginUserHolder.set(loginUser);
    }

    // ==================== 用例 1：内部工具 create + get ====================

    @Test
    @DisplayName("用例1: 内部工具 create 返回 id，get 字段一致（含审计字段）")
    void internal_createAndGet() {
        Long id = service.createInternalTool(internalDto("calc_tool", "计算工具",
                "{\"type\":\"string\"}", "calcBean", "execute", true, null));

        AgentToolInternalConfigDTO dto = service.getInternalTool(id);
        assertThat(dto.getId()).isEqualTo(id);
        assertThat(dto.getName()).isEqualTo("calc_tool");
        assertThat(dto.getDescription()).isEqualTo("计算工具");
        assertThat(dto.getInputSchema()).isEqualTo("{\"type\":\"string\"}");
        assertThat(dto.getBeanName()).isEqualTo("calcBean");
        assertThat(dto.getMethodName()).isEqualTo("execute");
        assertThat(dto.getEnabled()).isTrue();
        assertThat(dto.getCreateTime()).isNotNull();
    }

    // ==================== 用例 2：内部工具 update + toggle + 工厂侧查询 ====================

    @Test
    @DisplayName("用例2: 内部工具 update 生效；toggle 禁用后 listEnabledInternalTools 不再包含")
    void internal_updateAndToggle() {
        Long id = service.createInternalTool(internalDto("calc_tool", "计算工具",
                null, "calcBean", "execute", true, null));

        AgentToolInternalConfigDTO updateReq = internalDto("calc_tool", "计算工具 v2",
                "{\"type\":\"object\"}", "calcBean2", "run", true, "备注");
        service.updateInternalTool(id, updateReq);

        AgentToolInternalConfigDTO afterUpdate = service.getInternalTool(id);
        assertThat(afterUpdate.getDescription()).isEqualTo("计算工具 v2");
        assertThat(afterUpdate.getBeanName()).isEqualTo("calcBean2");
        assertThat(afterUpdate.getMethodName()).isEqualTo("run");

        // 启用状态查询
        List<AgentToolInternalConfig> enabled = service.listEnabledInternalTools(null);
        assertThat(enabled).extracting(AgentToolInternalConfig::getId).contains(id);

        // toggle 禁用 → 工厂侧查询不再返回
        service.toggleInternalTool(id, false);
        AgentToolInternalConfigDTO disabled = service.getInternalTool(id);
        assertThat(disabled.getEnabled()).isFalse();
        assertThat(service.listEnabledInternalTools(null))
                .extracting(AgentToolInternalConfig::getId)
                .doesNotContain(id);
    }

    // ==================== 用例 3：内部工具 delete（逻辑删除） ====================

    @Test
    @DisplayName("用例3: 内部工具 delete 后 get → NOT_FOUND，mapper.selectById 不可见（逻辑删除）")
    void internal_deleteLogical() {
        Long id = service.createInternalTool(internalDto("calc_tool", "计算工具",
                null, "calcBean", "execute", true, null));

        service.deleteInternalTool(id);

        assertThatThrownBy(() -> service.getInternalTool(id))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
        assertThat(internalMapper.selectById(id)).isNull();
    }

    // ==================== 用例 4：内部工具分页 + 校验 ====================

    @Test
    @DisplayName("用例4: pageInternalTools 按 nameKeyword 过滤；空工具名/空 beanName 拒绝")
    void internal_pageAndValidation() {
        service.createInternalTool(internalDto("alpha_tool", "a", null, "b1", "execute", true, null));
        service.createInternalTool(internalDto("beta_tool", "b", null, "b2", "execute", false, null));

        AgentToolInternalConfigQuery query = new AgentToolInternalConfigQuery();
        query.setNameKeyword("alpha");
        PageResult<AgentToolInternalConfigDTO> filtered = service.pageInternalTools(query);
        assertThat(filtered.getRecords()).hasSize(1);
        assertThat(filtered.getTotal()).isEqualTo(1);
        assertThat(filtered.getRecords().get(0).getName()).isEqualTo("alpha_tool");

        // enabled=false 过滤
        AgentToolInternalConfigQuery disabledQuery = new AgentToolInternalConfigQuery();
        disabledQuery.setEnabled(false);
        PageResult<AgentToolInternalConfigDTO> disabled = service.pageInternalTools(disabledQuery);
        assertThat(disabled.getRecords()).extracting(AgentToolInternalConfigDTO::getName)
                .containsExactly("beta_tool");

        // 空 name → PARAM_ERROR
        assertThatThrownBy(() -> service.createInternalTool(
                internalDto("", "desc", null, "b1", "execute", true, null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("工具名不能为空");
        // 空 beanName → PARAM_ERROR
        assertThatThrownBy(() -> service.createInternalTool(
                internalDto("tool-x", "desc", null, " ", "execute", true, null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("beanName 不能为空");
    }

    // ==================== 用例 5：外部工具 create + get（默认值） ====================

    @Test
    @DisplayName("用例5: 外部工具 create 返回 id；httpMethod/timeoutSeconds 未传时落库默认 POST/30")
    void external_createAndGet() {
        Long id = service.createExternalTool(externalDto("weather_tool", "天气查询",
                null, "http://127.0.0.1:1/weather", null, null, true, null));

        AgentToolExternalConfigDTO dto = service.getExternalTool(id);
        assertThat(dto.getName()).isEqualTo("weather_tool");
        assertThat(dto.getUrl()).isEqualTo("http://127.0.0.1:1/weather");
        assertThat(dto.getHttpMethod()).isEqualTo("POST");
        assertThat(dto.getEffectScope()).isEqualTo("QUERY");
        assertThat(dto.getTimeoutSeconds()).isEqualTo(30);
        assertThat(dto.getEnabled()).isTrue();
    }

    // ==================== 用例 6：外部工具 update/toggle/delete/分页 + 校验 ====================

    @Test
    @DisplayName("用例6: 外部工具 update/toggle/delete/分页 全链路；非法 HTTP 方法拒绝")
    void external_updateToggleDeletePageAndValidation() {
        Long id = service.createExternalTool(externalDto("weather_tool", "天气查询",
                null, "http://127.0.0.1:1/weather", "POST", 5, true, null));

        service.updateExternalTool(id, externalDto("weather_tool", "天气查询 v2",
                "{\"type\":\"object\"}", "http://127.0.0.1:2/weather2", "PUT", 10, true, "备注"));
        AgentToolExternalConfigDTO updated = service.getExternalTool(id);
        assertThat(updated.getDescription()).isEqualTo("天气查询 v2");
        assertThat(updated.getHttpMethod()).isEqualTo("PUT");
        assertThat(updated.getTimeoutSeconds()).isEqualTo(10);

        service.toggleExternalTool(id, false);
        assertThat(service.getExternalTool(id).getEnabled()).isFalse();
        assertThat(service.listEnabledExternalTools(null))
                .extracting(AgentToolExternalConfig::getId)
                .doesNotContain(id);

        // 分页
        AgentToolExternalConfigQuery query = new AgentToolExternalConfigQuery();
        query.setNameKeyword("weather");
        PageResult<AgentToolExternalConfigDTO> page = service.pageExternalTools(query);
        assertThat(page.getRecords()).hasSize(1);

        service.deleteExternalTool(id);
        assertThat(externalMapper.selectById(id)).isNull();

        // 非法 HTTP 方法 → PARAM_ERROR
        assertThatThrownBy(() -> service.createExternalTool(externalDto("bad_tool", "非法",
                null, "http://127.0.0.1:1/x", "DELETE", 5, true, null)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("仅 GET/POST/PUT");
    }

    // ==================== 用例 7：编排集成 —— bind/clear 对称（无 ThreadLocal 泄漏） ====================

    @Test
    @DisplayName("用例7: 白名单内部工具经 run() 加载绑定；正常完成与异常完成（模型服务不可达）后 clearTools 均执行（泄漏检查）")
    void orchestration_runWithInternalTool_shouldBindAndClearToolsSymmetrically() throws Exception {
        // 正常完成路径
        HttpServer server = startChatServer();
        try {
            int port = server.getAddress().getPort();
            Long modelId = insertModelConfig("openai", "http://127.0.0.1:" + port);
            Long toolId = service.createInternalTool(internalDto("echo_tool", "回声工具",
                    null, "echoToolBean", "execute", true, null));
            assertThat(toolId).isNotNull();

            AgentOrchestrationRunRespDTO resp = orchestrationService.run(req(modelId, "你好"));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getOutput()).isEqualTo("你好，mock 回复");
        } finally {
            server.stop(0);
        }
        // 泄漏检查：run() 返回后直接 invoke 图（不绑定工具）——若 clearTools 未执行，
        // callModel 构造的 Prompt 会携带 ToolCallingChatOptions
        assertNoToolsLeaked();

        // 异常完成路径（模型服务不可达 → run() 内部 catch → finally 仍须清除）
        int unusedPort = findUnusedPort();
        Long downModelId = insertModelConfig("openai", "http://127.0.0.1:" + unusedPort);
        AgentOrchestrationRunRespDTO failed = orchestrationService.run(req(downModelId, "hello"));
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.getErrorMessage()).isNotBlank();
        assertNoToolsLeaked();
    }

    // ==================== 内部辅助 ====================

    private AgentOrchestrationRunReqDTO req(Long id, String input) {
        AgentOrchestrationRunReqDTO req = new AgentOrchestrationRunReqDTO();
        req.setAgentModelConfigId(id);
        req.setInput(input);
        return req;
    }

    private Long insertModelConfig(String protocol, String baseUrl) {
        AgentModelConfig entity = new AgentModelConfig();
        entity.setName("tool-orch-" + System.nanoTime());
        entity.setProtocolType(protocol);
        entity.setBaseUrl(baseUrl);
        entity.setModelName("gpt-4o");
        entity.setApiKeyCipher(cipher.encrypt(TEST_API_KEY));
        entity.setTimeoutSeconds(10);
        entity.setRetryCount(0);
        entity.setEnabled(true);
        modelMapper.insert(entity);
        return entity.getId();
    }

    /** 泄漏检查：绑定捕获型 ChatModel 直接 invoke 图，断言 callModel 收到的 Prompt 不携带工具 options */
    private void assertNoToolsLeaked() throws Exception {
        CapturingChatModel stub = new CapturingChatModel("leak-check");
        AgentGraphFactory.bindChatModel(stub);
        try {
            Optional<AgentState> result = agentCompiledGraph.invoke(
                    Map.of("input", "leak-check", "chatModel", stub));
            assertThat(result).isPresent();
            assertThat(stub.capturedPrompt.getOptions())
                    .as("run() 结束后工具回调不得残留在 ThreadLocal（bind/clear 对称）")
                    .isNull();
        } finally {
            AgentGraphFactory.clearChatModel();
        }
    }

    private HttpServer startChatServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String json = "{\"id\":\"chatcmpl-tool-1\",\"object\":\"chat.completion\",\"created\":1720000000,"
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

    private static int findUnusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private AgentToolInternalConfigDTO internalDto(String name, String description, String inputSchema,
                                                   String beanName, String methodName,
                                                   boolean enabled, String remark) {
        AgentToolInternalConfigDTO dto = new AgentToolInternalConfigDTO();
        dto.setName(name);
        dto.setDescription(description);
        dto.setInputSchema(inputSchema);
        dto.setBeanName(beanName);
        dto.setMethodName(methodName);
        dto.setEnabled(enabled);
        dto.setRemark(remark);
        return dto;
    }

    private AgentToolExternalConfigDTO externalDto(String name, String description, String inputSchema,
                                                   String url, String httpMethod, Integer timeoutSeconds,
                                                   boolean enabled, String remark) {
        AgentToolExternalConfigDTO dto = new AgentToolExternalConfigDTO();
        dto.setName(name);
        dto.setDescription(description);
        dto.setInputSchema(inputSchema);
        dto.setUrl(url);
        dto.setHttpMethod(httpMethod);
        dto.setEffectScope("QUERY");
        dto.setTimeoutSeconds(timeoutSeconds);
        dto.setEnabled(enabled);
        dto.setRemark(remark);
        return dto;
    }

    /** 记录收到的 Prompt 的 ChatModel 桩 */
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

    /** 白名单内部工具 mock bean：约定签名 String execute(String params) */
    public static class EchoToolBean {
        public String execute(String params) {
            return "echo:" + params;
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
                    .url("jdbc:h2:mem:agenttool;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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

        // ==================== M07 Step3 业务 Bean ====================

        @Bean
        public AgentToolConfigService agentToolConfigService(
                AgentToolExternalConfigMapper agentToolExternalConfigMapper) {
            return new AgentToolConfigServiceImpl(agentToolExternalConfigMapper);
        }

        /** 白名单内部工具测试 bean（bean 名 = 方法名 echoToolBean） */
        @Bean
        public EchoToolBean echoToolBean() {
            return new EchoToolBean();
        }

        @Bean
        public ChatModelFactory chatModelFactory() {
            return new ChatModelFactory();
        }

        @Bean
        public CompiledGraph<AgentState> agentCompiledGraph() throws GraphStateException {
            return new AgentGraphFactory().buildGraph();
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
            // 4 参直构（Step2 同款）；agentToolCallbackFactory 经 @Autowired(required=false)
            // 字段注入（容器后处理器对 @Bean 实例生效）
            return new AgentOrchestrationServiceImpl(
                    agentModelConfigMapper, aesGcmCipher, chatModelFactory, agentCompiledGraph);
        }
    }
}
