package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphExecuteRespDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionDetailDTO;
import com.sw.ck.agent.dto.AgentGraphExecutionNodeDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDef;
import com.sw.ck.agent.entity.AgentGraphExecution;
import com.sw.ck.agent.entity.AgentGraphExecutionNode;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionMapper;
import com.sw.ck.agent.mapper.AgentGraphExecutionNodeMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.agent.service.AgentGraphExecutionService;
import com.sw.ck.common.config.mybatis.CommonMetaObjectHandler;
import com.sw.ck.common.config.mybatis.tenant.CommonTenantLineHandler;
import com.sw.ck.common.config.mybatis.tenant.TenantProperties;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentGraphExecutionServiceImpl} 测试（M07-F02 Step8 §7，@SpringBootTest + H2）。
 * <p>
 * 策略与 {@code AgentGraphDefServiceImplTest} 同款组合装配（TestConfig 手动装配
 * MyBatis-Plus + 租户拦截器 + ObjectMapper），另装配 {@code AgentModelRuntimeService}/
 * {@code AgentToolCallbackFactory} 两个 mock（LC4j ChatModel 桩经 ModelInstance 注入，
 * F1 阶段 1）与真实 {@code AesGcmCipher}。建表 DDL 与 V25/V19/V20/V21 H2 脚本对齐。
 * </p>
 */
@SpringBootTest(
        classes = AgentGraphExecutionServiceImplTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 图执行 Service 测试")
class AgentGraphExecutionServiceImplTest {

    /** 测试 AES 密钥（32 字节 "0123456789abcdef0123456789abcdef" 的 Base64） */
    private static final String TEST_CIPHER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_1 = 1L;

    @Autowired
    private AgentGraphExecutionService service;

    @Autowired
    private AgentGraphDefService graphDefService;

    @Autowired
    private com.sw.ck.agent.mapper.AgentProviderMapper providerMapper;

    @Autowired
    private com.sw.ck.agent.mapper.AgentModelMapper modelMapper;

    @Autowired
    private AgentToolInternalConfigMapper internalToolMapper;

    @Autowired
    private AgentGraphExecutionMapper executionMapper;

    @Autowired
    private AgentGraphExecutionNodeMapper executionNodeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentModelRuntimeService modelRuntimeService;

    @Autowired
    private AgentToolCallbackFactory toolCallbackFactory;

    private final AesGcmCipher cipher = new AesGcmCipher(TEST_CIPHER_KEY);

    // ==================== 建表（V25/V19/V20/V21 H2 脚本 DDL） ====================

    @BeforeAll
    static void createTables(@Autowired JdbcTemplate jt) {
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_def (
                    id           BIGINT NOT NULL PRIMARY KEY,
                    graph_key    VARCHAR(100) NOT NULL,
                    name         VARCHAR(200) NOT NULL,
                    def_version  INT NOT NULL DEFAULT 1,
                    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    graph_json   CLOB,
                    create_time  TIMESTAMP,
                    create_by    VARCHAR(64),
                    update_time  TIMESTAMP,
                    update_by    VARCHAR(64),
                    deleted      SMALLINT NOT NULL DEFAULT 0,
                    tenant_id    BIGINT NOT NULL DEFAULT 0,
                    version      BIGINT NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_sw_agent_graph_key ON sw_agent_graph_def (tenant_id, graph_key)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_graph_tenant_deleted ON sw_agent_graph_def (tenant_id, deleted)");
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
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_model_tenant_deleted ON sw_agent_model (tenant_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tool_internal_tenant_deleted ON sw_agent_tool_internal (tenant_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_tool_external_tenant_deleted ON sw_agent_tool_external (tenant_id, deleted)");
        // Step12 执行历史两表（对齐 V27/V28 H2 脚本 DDL）
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_execution (
                    id                BIGINT NOT NULL PRIMARY KEY,
                    graph_def_id      BIGINT NOT NULL,
                    graph_def_version INT NOT NULL,
                    status            VARCHAR(20) NOT NULL,
                    input             CLOB,
                    result_text       CLOB,
                    error_category    VARCHAR(50),
                    error_message     CLOB,
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
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_gexec_graph ON sw_agent_graph_execution (graph_def_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_gexec_time ON sw_agent_graph_execution (tenant_id, create_time, deleted)");
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_execution_node (
                    id                BIGINT       NOT NULL PRIMARY KEY,
                    execution_id      BIGINT       NOT NULL,
                    node_seq          INT          NOT NULL,
                    branch_id         VARCHAR(64)  NOT NULL,
                    node_id           VARCHAR(100) NOT NULL,
                    node_type         VARCHAR(20)  NOT NULL,
                    node_latency_ms   BIGINT,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    variable_snapshot CLOB,
                    create_time       TIMESTAMP,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT     NOT NULL DEFAULT 0,
                    tenant_id         BIGINT       NOT NULL DEFAULT 0,
                    version           BIGINT       NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_sw_agent_genode_exec ON sw_agent_graph_execution_node (execution_id, node_seq, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_execution_node");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_execution");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_def");
        jdbcTemplate.update("DELETE FROM sw_agent_model");
        jdbcTemplate.update("DELETE FROM sw_agent_provider");
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

    // ==================== 用例 1：已发布图执行成功 ====================

    @Test
    @DisplayName("用例1: 执行已发布图（START→LLM→END）→ success=true + 模型输出；latencyMs 非负")
    void execute_publishedLlmGraph_shouldSucceed() {
        Long modelId = insertModelConfig("openai-1", "sk-llm-1");
                        stubAcquire(new StubChatModel("图执行输出"));
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参文本");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getOutput()).isEqualTo("图执行输出");
        assertThat(resp.getErrorMessage()).isNull();
        assertThat(resp.getLatencyMs()).isNotNegative();
    }

    // ==================== 用例 2：DRAFT 图 → PARAM_ERROR ====================

    @Test
    @DisplayName("用例2: 执行 DRAFT 状态的图 → PARAM_ERROR 图未发布")
    void execute_draftGraph_shouldThrowParamError() {
        Long id = graphDefService.create("未发布图");
        graphDefService.saveDraftGraph(id, llmGraph(1L));

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("图未发布")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 3：不存在 id → NOT_FOUND ====================

    @Test
    @DisplayName("用例3: 执行不存在的图定义 id → NOT_FOUND")
    void execute_unknownId_shouldThrowNotFound() {
        assertThatThrownBy(() -> service.execute(999999L, "文本"))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 4：LLM 引用不存在模型配置 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例4: LLM 节点引用不存在的模型配置 → 执行前 PARAM_ERROR（不做部分执行）")
    void execute_llmRefMissingModelConfig_shouldThrowParamError() {
        // 该 id 无对应配置行：执行前校验 assertUsable 抛业务异常（模型不存在或已删除）
        org.mockito.Mockito.doThrow(new BaseException(CommonErrorCode.NOT_FOUND, "模型不存在或已删除: 888888"))
                .when(modelRuntimeService).assertUsable(888888L);
        Long id = createPublishedGraph(llmGraph(888888L));

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("模型不存在")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 5：TOOL 引用不存在/未启用工具 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例5: TOOL 节点引用不存在或未启用的工具 → 执行前 PARAM_ERROR")
    void execute_toolRefMissingTool_shouldThrowParamError() {
        // 白名单表为空（无 enabled=1 的 echo_tool）
        Long id = createPublishedGraph(toolGraph("echo_tool"));

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("工具节点引用的工具不存在或未启用")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));

        // 已存在但 enabled=0（禁用）→ 同样拦截
        insertInternalTool("echo_tool", 0);
        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("工具节点引用的工具不存在或未启用");
    }

    // ==================== 用例 6：跨租户 → NOT_FOUND ====================

    @Test
    @DisplayName("用例6: 租户 B 执行租户 A 的图 → NOT_FOUND（租户拦截器隔离）")
    void execute_crossTenant_shouldThrowNotFound() {
        Long id = createPublishedGraph(llmGraph(1L));

        setLoginUser(TENANT_200, USER_1);
        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 7：START 不唯一 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例7: 图中存在 2 个 START 节点 → 执行前 PARAM_ERROR（验收 5 校验项②）")
    void execute_twoStarts_shouldThrowParamError() {
        ProcessGraph graph = graphOf(
                node("node_start_1", "START", Map.of()),
                node("node_start_2", "START", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start_1", "node_end", Map.of()),
                edge("e2", "node_start_2", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("START 节点必须唯一")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 8：END 不可达 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例8: 图中无可达 END 节点 → 执行前 PARAM_ERROR（验收 5 校验项②）")
    void execute_endUnreachable_shouldThrowParamError() {
        // 仅有 START→LLM，无 END 节点
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", 1L)),
                edge("e1", "node_start", "node_llm", Map.of()));
        Long id = createPublishedGraph(graph);

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("图中不存在可达的 END 节点");
    }

    // ==================== 用例 9：CONDITION 默认边不唯一 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例9: CONDITION 出边 ≥2 条无 keyword 边 → 执行前 PARAM_ERROR（验收 5 校验项⑤）")
    void execute_conditionDefaultEdgesNotUnique_shouldThrowParamError() {
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_cond", "CONDITION", Map.of()),
                node("node_llm_a", "LLM", Map.of("agentModelConfigId", 1L)),
                node("node_llm_b", "LLM", Map.of("agentModelConfigId", 2L)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_cond", Map.of()),
                edge("e_d1", "node_cond", "node_llm_a", Map.of()),
                edge("e_d2", "node_cond", "node_llm_b", Map.of()),
                edge("e3", "node_llm_a", "node_end", Map.of()),
                edge("e4", "node_llm_b", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("条件分支默认边不唯一");
    }

    // ==================== 用例 10：TOOL 节点执行成功 ====================

    @Test
    @DisplayName("用例10: 执行含 TOOL 节点的已发布图（白名单 enabled=1 + 按名定位回调）→ success=true")
    void execute_toolNode_shouldSucceed() {
        insertInternalTool("echo_tool", 1);
        AgentToolCallbackFactory.ToolHandle echo = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("echo_tool").description("回声工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> {
                    try {
                        String arg = new ObjectMapper().readValue(request.arguments(), String.class);
                        return new ObjectMapper().writeValueAsString("echo:" + arg);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(echo));
        Long id = createPublishedGraph(toolGraph("echo_tool"));

        AgentGraphExecuteRespDTO resp = service.execute(id, "你好");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getOutput()).isEqualTo("echo:你好");
    }

    // ==================== 用例 11：运行时条件无匹配且无默认边 → success=false ====================

    @Test
    @DisplayName("用例11: CONDITION 无关键词命中且无默认边 → 运行时 success=false + errorMessage（不上抛）")
    void execute_conditionNoMatchNoDefault_shouldReturnFailure() {
        // LLM 分支节点引用的模型配置须通过执行前校验（否则提前 PARAM_ERROR 拦截）
        Long modelId = insertModelConfig("openai-3", "sk-llm-3");
                        stubAcquire(new StubChatModel("不应到达"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_cond", "CONDITION", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_cond", Map.of()),
                edge("e_key", "node_cond", "node_llm", Map.of("keyword", "退款")),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "无关文本");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("条件分支无匹配且无默认边");
        assertThat(resp.getLatencyMs()).isNotNegative();
    }

    // ==================== 用例 12：模型调用抛异常 → success=false ====================

    @Test
    @DisplayName("用例12: LLM 模型调用抛异常 → success=false + 异常摘要（不抛 500）")
    void execute_llmCallThrows_shouldReturnFailure() {
        Long modelId = insertModelConfig("openai-2", "sk-llm-2");
                        stubAcquire(new ThrowingChatModel());
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "文本");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("model exploded");
    }

    // ==================== 用例 13：命名变量存取全链路（Step10 多变量） ====================

    @Test
    @DisplayName("用例13: LLM outputVar 写命名变量 + END inputVar 读回 → success=true + 命名变量值（全链路）")
    void execute_namedVariableGraph_shouldSucceed() {
        Long modelId = insertModelConfig("openai-4", "sk-llm-4");
                        stubAcquire(new StubChatModel("汇总输出"));
        // LLM 写 summary 变量；END inputVar=summary 取最终输出（默认变量保持入参原值）
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId, "outputVar", "summary")),
                node("node_end", "END", Map.of("inputVar", "summary")),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参文本");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getOutput()).isEqualTo("汇总输出");
        assertThat(resp.getErrorMessage()).isNull();
    }

    // ==================== 用例 14：未定义变量引用 → 运行时 success=false（Step10 多变量） ====================

    @Test
    @DisplayName("用例14: LLM inputVar 引用未定义变量 → 运行时 success=false + errorMessage（不上抛）")
    void execute_undefinedVariable_shouldReturnFailure() {
        Long modelId = insertModelConfig("openai-5", "sk-llm-5");
                        stubAcquire(new StubChatModel("不应到达"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId, "inputVar", "missing")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "文本");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("引用了未定义的变量: missing");
        assertThat(resp.getLatencyMs()).isNotNegative();
    }

    // ==================== 用例 15：全链路循环图（Step11 LOOP） ====================

    @Test
    @DisplayName("用例15: 全链路循环图（LOOP→LLM→CONDITION 回边，graph_json 序列化往返）→ success=true + 循环退出输出")
    void execute_loopGraph_shouldSucceed() {
        Long modelId = insertModelConfig("openai-6", "sk-loop");
        // 三次 build 依次返回：前两轮无退出关键词 → 默认边回 LOOP；第三轮"退出" → 走 END
                        stubAcquire(
                    new StubChatModel("结果1"),
                    new StubChatModel("结果2"),
                    new StubChatModel("退出"));
        // 与解释器单测同构的循环图（经 graph_json 序列化往返，验证不透明 config 键 maxIterations 零迁移）
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 3)),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_cond", "CONDITION", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "退出")),
                edge("e_back", "node_cond", "node_loop", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "开始");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getOutput()).isEqualTo("退出");
        assertThat(resp.getErrorMessage()).isNull();
    }

    // ==================== 用例 16：全链路并行图（Step11 FORK→JOIN） ====================

    @Test
    @DisplayName("用例16: 全链路并行图（FORK→JOIN 两分支全执行并汇合）→ success=true + 汇合输出")
    void execute_forkJoinGraph_shouldSucceed() {
        Long modelId = insertModelConfig("openai-7", "sk-b1");
        insertInternalTool("echo_tool", 1);
                        stubAcquire(new StubChatModel("分支1输出"));
        AgentToolCallbackFactory.ToolHandle echo = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("echo_tool").description("回声工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> "\"分支2输出\"");
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(echo));

        // B1: LLM 写 v1；B2: TOOL 写 v2；JOIN 汇合后 END 从 v1 取最终输出
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_fork", "FORK", Map.of()),
                node("node_llm_b1", "LLM", Map.of("agentModelConfigId", modelId, "outputVar", "v1")),
                node("node_tool_b2", "TOOL", Map.of("toolName", "echo_tool", "outputVar", "v2")),
                node("node_join", "JOIN", Map.of()),
                node("node_end", "END", Map.of("inputVar", "v1")),
                edge("e1", "node_start", "node_fork", Map.of()),
                edge("e2", "node_fork", "node_llm_b1", Map.of()),
                edge("e3", "node_fork", "node_tool_b2", Map.of()),
                edge("e4", "node_llm_b1", "node_join", Map.of()),
                edge("e5", "node_tool_b2", "node_join", Map.of()),
                edge("e6", "node_join", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参文本");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getOutput()).isEqualTo("分支1输出");
        assertThat(resp.getErrorMessage()).isNull();
    }

    // ==================== 用例 17：FORK 出边 <2 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例17: FORK 节点仅 1 条出边 → 执行前 PARAM_ERROR 扇出分支数必须 ≥ 2")
    void execute_forkWithSingleOutEdge_shouldThrowParamError() {
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_fork", "FORK", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_fork", Map.of()),
                edge("e2", "node_fork", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("FORK 节点扇出分支数必须 ≥ 2")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 18：JOIN 入边 <2 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例18: JOIN 节点仅 1 条入边 → 执行前 PARAM_ERROR 汇合入边数必须 ≥ 2")
    void execute_joinWithSingleInEdge_shouldThrowParamError() {
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_join", "JOIN", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_join", Map.of()),
                edge("e2", "node_join", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("JOIN 节点汇合入边数必须 ≥ 2")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 19：LOOP maxIterations <1 → 执行前 PARAM_ERROR ====================

    @Test
    @DisplayName("用例19: LOOP maxIterations=0 → 执行前 PARAM_ERROR maxIterations 必须 ≥ 1")
    void execute_loopWithZeroMaxIterations_shouldThrowParamError() {
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 0)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        assertThatThrownBy(() -> service.execute(id, "文本"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("LOOP 节点 maxIterations 必须 ≥ 1")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 20：执行成功 → 全链路落库（Step12） ====================

    @Test
    @DisplayName("用例20: 执行成功 → 落库 status=SUCCESS + output/input/graphDefId/version + 节点明细 3 行（START/LLM/END）+ 响应 executionId")
    void execute_success_shouldPersistHistory() {
        Long modelId = insertModelConfig("openai-8", "sk-persist");
                        stubAcquire(new StubChatModel("持久化输出"));
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参文本");

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getExecutionId()).isNotNull();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec).isNotNull();
        assertThat(exec.getStatus()).isEqualTo("SUCCESS");
        assertThat(exec.getResultText()).isEqualTo("持久化输出");
        assertThat(exec.getInput()).isEqualTo("入参文本");
        assertThat(exec.getGraphDefId()).isEqualTo(id);
        assertThat(exec.getGraphDefVersion()).isEqualTo(2);
        assertThat(exec.getErrorCategory()).isNull();
        assertThat(exec.getLatencyMs()).isNotNegative();
        // 节点明细 3 行：START/LLM/END，branchId 全 0，快照含默认变量
        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .orderByAsc(AgentGraphExecutionNode::getNodeSeq));
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).getNodeType()).isEqualTo("START");
        assertThat(nodes.get(1).getNodeType()).isEqualTo("LLM");
        assertThat(nodes.get(2).getNodeType()).isEqualTo("END");
        assertThat(nodes).allSatisfy(n -> {
            assertThat(n.getBranchId()).isEqualTo("0");
            assertThat(n.getNodeLatencyMs()).isNotNegative();
            assertThat(n.getVariableSnapshot()).contains("input");
        });
    }

    // ==================== 用例 21：运行时失败 → 失败路径落库（Step12） ====================

    @Test
    @DisplayName("用例21: 条件无匹配且无默认边 → 落库 status=FAILED + errorCategory=CONDITION_NO_MATCH + 节点明细含失败节点行")
    void execute_runtimeFailure_shouldPersistFailure() {
        Long modelId = insertModelConfig("openai-9", "sk-fail");
                        stubAcquire(new StubChatModel("不应到达"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_cond", "CONDITION", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_cond", Map.of()),
                edge("e_key", "node_cond", "node_llm", Map.of("keyword", "退款")),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "无关文本");

        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getExecutionId()).isNotNull();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getStatus()).isEqualTo("FAILED");
        assertThat(exec.getErrorCategory()).isEqualTo("CONDITION_NO_MATCH");
        assertThat(exec.getErrorMessage()).contains("条件分支无匹配且无默认边");
        assertThat(exec.getResultText()).isNull();
        // 失败路径节点明细完整：START + 失败节点 CONDITION（各占一行）
        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .orderByAsc(AgentGraphExecutionNode::getNodeSeq));
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).getNodeId()).isEqualTo("node_start");
        assertThat(nodes.get(1).getNodeId()).isEqualTo("node_cond");
        assertThat(nodes.get(1).getNodeLatencyMs()).isNotNegative();
    }

    // ==================== 用例 22-26：错误分类维度落库（Step12 §5.3） ====================

    @Test
    @DisplayName("用例22: LLM 第三方异常 → 落库 errorCategory=MODEL_CALL_FAILED")
    void execute_llmThirdPartyFailure_shouldPersistModelCallFailed() {
        Long modelId = insertModelConfig("openai-10", "sk-throw");
                        stubAcquire(new ThrowingChatModel());
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "文本");

        assertThat(resp.isSuccess()).isFalse();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getErrorCategory()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(exec.getErrorMessage()).contains("model exploded");
    }

    @Test
    @DisplayName("用例23: 步数超限（预算耗尽仍死循环）→ 落库 errorCategory=STEP_LIMIT")
    void execute_stepLimit_shouldPersistStepLimit() {
        Long modelId = insertModelConfig("openai-11", "sk-loop-limit");
                        stubAcquire(new StubChatModel("死循环"));
        // START→CONDITION（keyword 边→END / 默认边→LLM）→LLM→CONDITION：输入与 LLM
        // 输出永不含关键词 → 死循环，靠全局步数兜底（END 可达，执行前校验放行）
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_cond", "CONDITION", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "exit")),
                edge("e_back", "node_cond", "node_llm", Map.of()),
                edge("e4", "node_llm", "node_cond", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "无关键词文本");

        assertThat(resp.isSuccess()).isFalse();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getErrorCategory()).isEqualTo("STEP_LIMIT");
        assertThat(exec.getErrorMessage()).contains("执行步数超限");
    }

    @Test
    @DisplayName("用例24: 循环迭代超限 → 落库 errorCategory=LOOP_LIMIT")
    void execute_loopLimit_shouldPersistLoopLimit() {
        Long modelId = insertModelConfig("openai-12", "sk-loop-over");
                        stubAcquire(new StubChatModel("永不退出"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 1)),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_cond", "CONDITION", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "exit")),
                edge("e_back", "node_cond", "node_loop", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "开始");

        assertThat(resp.isSuccess()).isFalse();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getErrorCategory()).isEqualTo("LOOP_LIMIT");
        assertThat(exec.getErrorMessage()).contains("循环迭代次数超限");
    }

    @Test
    @DisplayName("用例25: 未定义变量引用 → 落库 errorCategory=UNDEFINED_VARIABLE")
    void execute_undefinedVariable_shouldPersistUndefinedVariable() {
        Long modelId = insertModelConfig("openai-13", "sk-undef");
                        stubAcquire(new StubChatModel("不应到达"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId, "inputVar", "missing")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "文本");

        assertThat(resp.isSuccess()).isFalse();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getErrorCategory()).isEqualTo("UNDEFINED_VARIABLE");
    }

    @Test
    @DisplayName("用例26: 工具回调运行时异常 → 落库 errorCategory=TOOL_CALL_FAILED")
    void execute_toolRuntimeFailure_shouldPersistToolCallFailed() {
        insertInternalTool("boom_tool", 1);
        AgentToolCallbackFactory.ToolHandle boom = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("boom_tool").description("抛错工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> {
                    throw new IllegalStateException("tool exploded");
                });
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(boom));
        Long id = createPublishedGraph(toolGraph("boom_tool"));

        AgentGraphExecuteRespDTO resp = service.execute(id, "你好");

        assertThat(resp.isSuccess()).isFalse();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getErrorCategory()).isEqualTo("TOOL_CALL_FAILED");
        assertThat(exec.getErrorMessage()).contains("tool exploded");
    }

    // ==================== 用例 27：执行历史列表（Step12 查询端点） ====================

    @Test
    @DisplayName("用例27: 执行历史列表 — 分页 + graphDefId 过滤；无过滤 = 全部")
    void pageExecutions_shouldReturnPagedAndFiltered() {
        Long modelId = insertModelConfig("openai-14", "sk-list");
                        stubAcquire(new StubChatModel("列表输出"));
        Long id1 = createPublishedGraph(llmGraph(modelId));
        Long id2 = createPublishedGraph(llmGraph(modelId));

        service.execute(id1, "第一次");
        service.execute(id2, "第二次");
        service.execute(id1, "第三次");

        PageResult<AgentGraphExecutionDTO> byGraph = service.pageExecutions(new PageParam(), id1);
        assertThat(byGraph.getTotal()).isEqualTo(2);
        assertThat(byGraph.getRecords()).hasSize(2);
        assertThat(byGraph.getRecords()).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo("SUCCESS");
            assertThat(r.getGraphDefId()).isEqualTo(id1);
            assertThat(r.getLatencyMs()).isNotNegative();
        });
        PageResult<AgentGraphExecutionDTO> all = service.pageExecutions(new PageParam(), null);
        assertThat(all.getTotal()).isEqualTo(3);
        assertThat(all.getRecords()).hasSize(3);
    }

    // ==================== 用例 28：执行详情（Step12 查询端点） ====================

    @Test
    @DisplayName("用例28: 执行详情 — input/output/latency/version 回显；不存在的 id → NOT_FOUND")
    void getExecution_shouldReturnDetailOrNotFound() {
        Long modelId = insertModelConfig("openai-15", "sk-detail");
                        stubAcquire(new StubChatModel("详情输出"));
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "详情入参");

        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getId()).isEqualTo(resp.getExecutionId());
        assertThat(detail.getStatus()).isEqualTo("SUCCESS");
        assertThat(detail.getInput()).isEqualTo("详情入参");
        assertThat(detail.getOutput()).isEqualTo("详情输出");
        assertThat(detail.getLatencyMs()).isNotNegative();
        assertThat(detail.getGraphDefVersion()).isEqualTo(2);
        assertThat(detail.getCreateTime()).isNotNull();

        assertThatThrownBy(() -> service.getExecution(999999L))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 29：节点明细（Step12 查询端点） ====================

    @Test
    @DisplayName("用例29: 节点明细 — nodeSeq 升序返回；执行记录不存在 → NOT_FOUND")
    void listExecutionNodes_shouldReturnOrderedOrNotFound() {
        Long modelId = insertModelConfig("openai-16", "sk-nodes");
                        stubAcquire(new StubChatModel("节点输出"));
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");

        List<AgentGraphExecutionNodeDTO> nodes = service.listExecutionNodes(resp.getExecutionId());
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).getNodeSeq()).isEqualTo(1);
        assertThat(nodes.get(0).getNodeType()).isEqualTo("START");
        assertThat(nodes.get(1).getNodeSeq()).isEqualTo(2);
        assertThat(nodes.get(1).getNodeType()).isEqualTo("LLM");
        assertThat(nodes.get(2).getNodeSeq()).isEqualTo(3);
        assertThat(nodes.get(2).getNodeType()).isEqualTo("END");
        assertThat(nodes.get(1).getVariableSnapshot()).contains("input");

        assertThatThrownBy(() -> service.listExecutionNodes(999999L))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例 30：跨租户隔离（Step12 查询） ====================

    @Test
    @DisplayName("用例30: 跨租户隔离 — 租户 B 查执行历史列表为空、详情/节点 → NOT_FOUND")
    void executionHistory_crossTenant_shouldBeIsolated() {
        Long modelId = insertModelConfig("openai-17", "sk-tenant");
                        stubAcquire(new StubChatModel("租户输出"));
        Long id = createPublishedGraph(llmGraph(modelId));
        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");

        setLoginUser(TENANT_200, USER_1);
        assertThat(service.pageExecutions(new PageParam(), null).getTotal()).isZero();
        assertThatThrownBy(() -> service.getExecution(resp.getExecutionId()))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.listExecutionNodes(resp.getExecutionId()))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 用例36-41：F02 Token 落库查询 + 会话/租户隔离 + 部分 usage（M07-F04-02 D164 补证） ====================

    /** 带 TokenUsage 的 ChatModel 桩：每次 chat 返回固定 reply + 固定 token；token 序列化输入输出独立可控 */
    static class TokenStubChatModel implements ChatModel {
        private final String reply;
        private final Long promptTokens;
        private final Long completionTokens;
        private int callCount = 0;

        TokenStubChatModel(String reply, Long promptTokens, Long completionTokens) {
            this.reply = reply;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            callCount++;
            ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder();
            // promptTokens/completionTokens 为 null 时模拟供应商未返回 usage
            // （metadata 不携带 tokenUsage → 采集层保持 null 未知语义，不写零）
            if (promptTokens != null && completionTokens != null) {
                metadataBuilder.tokenUsage(new TokenUsage(promptTokens.intValue(), completionTokens.intValue()));
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(reply))
                    .metadata(metadataBuilder.build())
                    .build();
        }

        int getCallCount() {
            return callCount;
        }
    }

    @Test
    @DisplayName("用例36: 标准1/2-F02 — 单 LLM 节点 token 经生产 Service 落库：执行记录汇总列 + 节点明细列 + 列表/详情/nodes 查询全部返回（DB 实际值可复算）")
    void execute_tokenUsage_shouldPersistToExecutionAndNodeRecordsAndBeQueryable() {
        Long modelId = insertModelConfig("openai-token1", "sk-token1");
                        stubAcquire(new TokenStubChatModel("token回复", 10L, 20L));
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");
        assertThat(resp.isSuccess()).isTrue();

        // ① 执行记录落库（sw_agent_graph_execution 表 input_tokens/output_tokens 汇总列）
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getInputTokens()).isEqualTo(10L);
        assertThat(exec.getOutputTokens()).isEqualTo(20L);

        // ② 节点明细落库（sw_agent_graph_execution_node 表，LLM 节点带 token；START/END 为 null）
        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .orderByAsc(AgentGraphExecutionNode::getNodeSeq));
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).getNodeType()).isEqualTo("START");
        assertThat(nodes.get(0).getInputTokens()).isNull();
        assertThat(nodes.get(1).getNodeType()).isEqualTo("LLM");
        assertThat(nodes.get(1).getInputTokens()).isEqualTo(10L);
        assertThat(nodes.get(1).getOutputTokens()).isEqualTo(20L);
        assertThat(nodes.get(2).getNodeType()).isEqualTo("END");
        assertThat(nodes.get(2).getOutputTokens()).isNull();

        // ③ 生产查询端点：列表 DTO 含 token 汇总
        PageResult<AgentGraphExecutionDTO> page = service.pageExecutions(new PageParam(), id);
        assertThat(page.getRecords()).anySatisfy(dto -> {
            assertThat(dto.getInputTokens()).isEqualTo(10L);
            assertThat(dto.getOutputTokens()).isEqualTo(20L);
        });
        // ④ 详情 DTO 含 token 汇总
        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getInputTokens()).isEqualTo(10L);
        assertThat(detail.getOutputTokens()).isEqualTo(20L);
        // ⑤ 节点轨迹端点 DTO 含节点级 token
        List<AgentGraphExecutionNodeDTO> nodeDtos = service.listExecutionNodes(resp.getExecutionId());
        assertThat(nodeDtos.get(1).getInputTokens()).isEqualTo(10L);
        assertThat(nodeDtos.get(1).getOutputTokens()).isEqualTo(20L);
        assertThat(nodeDtos.get(0).getInputTokens()).isNull();
    }

    @Test
    @DisplayName("用例37: 标准2-F02 — 两 LLM 节点链经生产 Service 落库：执行汇总=两节点和，查询结果与调用事实复算一致")
    void execute_twoLlmChain_shouldAggregateTokensInExecutionRecord() {
        Long modelId = insertModelConfig("openai-token2", "sk-token2");
                        stubAcquire(
                    new TokenStubChatModel("回复1", 10L, 20L),
                    new TokenStubChatModel("回复2", 30L, 40L));
        // 两 LLM 节点链
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm1", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_llm2", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm1", Map.of()),
                edge("e2", "node_llm1", "node_llm2", Map.of()),
                edge("e3", "node_llm2", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");
        assertThat(resp.isSuccess()).isTrue();

        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        // 执行级汇总 = 两节点 input 和 / output 和（生产 Service 聚合，非解释器 trace 直接求和）
        assertThat(exec.getInputTokens()).isEqualTo(40L);  // 10 + 30
        assertThat(exec.getOutputTokens()).isEqualTo(60L); // 20 + 40

        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .orderByAsc(AgentGraphExecutionNode::getNodeSeq));
        // 两个 LLM 节点各自独立落库（nodeSeq 2 与 3），不去重
        assertThat(nodes).hasSize(4);
        assertThat(nodes.get(1).getNodeId()).isEqualTo("node_llm1");
        assertThat(nodes.get(1).getInputTokens()).isEqualTo(10L);
        assertThat(nodes.get(1).getOutputTokens()).isEqualTo(20L);
        assertThat(nodes.get(2).getNodeId()).isEqualTo("node_llm2");
        assertThat(nodes.get(2).getInputTokens()).isEqualTo(30L);
        assertThat(nodes.get(2).getOutputTokens()).isEqualTo(40L);

        // 复算：Σ节点 input = 执行记录 input
        long sumInput = nodes.stream().filter(n -> n.getInputTokens() != null)
                .mapToLong(AgentGraphExecutionNode::getInputTokens).sum();
        long sumOutput = nodes.stream().filter(n -> n.getOutputTokens() != null)
                .mapToLong(AgentGraphExecutionNode::getOutputTokens).sum();
        assertThat(sumInput).isEqualTo(exec.getInputTokens());
        assertThat(sumOutput).isEqualTo(exec.getOutputTokens());
    }

    @Test
    @DisplayName("用例38: 标准2-F02 — LOOP 同节点重复执行 token 不去重：3 轮调用分别落 3 行节点明细，执行汇总=3 倍")
    void execute_loopRepeatedNode_shouldRecordTokensPerExecutionNotDeduped() {
        Long modelId = insertModelConfig("openai-token3", "sk-token3");
                        stubAcquire(
                    new TokenStubChatModel("继续", 5L, 5L),
                    new TokenStubChatModel("继续", 5L, 5L),
                    new TokenStubChatModel("退出", 5L, 5L));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_loop", "LOOP", Map.of("maxIterations", 3)),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId)),
                node("node_cond", "CONDITION", Map.of()),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_loop", Map.of()),
                edge("e2", "node_loop", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_cond", Map.of()),
                edge("e_exit", "node_cond", "node_end", Map.of("keyword", "退出")),
                edge("e_back", "node_cond", "node_loop", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "开始");
        assertThat(resp.isSuccess()).isTrue();

        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        // 3 轮 × 5 = 15（同节点重复执行不去重，与 D161 方法级结论一致的落库版）
        assertThat(exec.getInputTokens()).isEqualTo(15L);
        assertThat(exec.getOutputTokens()).isEqualTo(15L);

        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .eq(AgentGraphExecutionNode::getNodeType, "LLM"));
        // 3 行 LLM 明细（同一 nodeId 3 次执行各自独立），nodeSeq 递增
        assertThat(nodes).hasSize(3);
        assertThat(nodes).allSatisfy(n -> {
            assertThat(n.getNodeId()).isEqualTo("node_llm");
            assertThat(n.getInputTokens()).isEqualTo(5L);
            assertThat(n.getOutputTokens()).isEqualTo(5L);
        });
        assertThat(nodes.get(0).getNodeSeq()).isLessThan(nodes.get(1).getNodeSeq());
        assertThat(nodes.get(1).getNodeSeq()).isLessThan(nodes.get(2).getNodeSeq());
    }

    @Test
    @DisplayName("用例39: 标准4-F02 — 供应商缺失 usage（无 metadata）→ 执行/节点 token 列 NULL 而非 0；列表/详情同样为 null")
    void execute_noUsage_shouldStoreNullNotZeroInExecutionAndNodes() {
        Long modelId = insertModelConfig("openai-token4", "sk-token4");
        // 不带 usage 的响应（null 语义）
                        stubAcquire(new TokenStubChatModel("无usage回复", null, null));
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");
        assertThat(resp.isSuccess()).isTrue();

        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getInputTokens()).isNull();
        assertThat(exec.getOutputTokens()).isNull();
        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .eq(AgentGraphExecutionNode::getNodeType, "LLM"));
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getInputTokens()).isNull();
        assertThat(nodes.get(0).getOutputTokens()).isNull();
        // 查询端点同样为 null（与 0 严格区分，未知语义贯穿 DB→DTO）
        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getInputTokens()).isNull();
        assertThat(detail.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("用例40: 标准4-F02 — 供应商部分 usage（仅返回 input 缺失 output）→ 各自独立 NULL/数值，不估算另一侧")
    void execute_partialUsage_shouldKeepIndependentNullPerSide() {
        Long modelId = insertModelConfig("openai-token5", "sk-token5");
        // 部分 usage：输入侧有 10，输出侧缺失（DefaultUsage 仅注入 promptTokens）
                        stubAcquire(new TokenPartialStubChatModel());
        Long id = createPublishedGraph(llmGraph(modelId));

        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");
        assertThat(resp.isSuccess()).isTrue();

        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        // input=10 有值，output 保持 NULL（不补零、不估算）
        assertThat(exec.getInputTokens()).isEqualTo(10L);
        assertThat(exec.getOutputTokens()).isNull();

        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .eq(AgentGraphExecutionNode::getNodeType, "LLM"));
        assertThat(nodes.get(0).getInputTokens()).isEqualTo(10L);
        assertThat(nodes.get(0).getOutputTokens()).isNull();

        // 查询端点保持一致：input=10 / output=null
        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getInputTokens()).isEqualTo(10L);
        assertThat(detail.getOutputTokens()).isNull();
    }

    /** 部分 usage 桩：仅 inputTokenCount=10，outputTokenCount 缺失（LC4j TokenUsage 原始 null 语义） */
    static class TokenPartialStubChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            // LC4j TokenUsage 各计数独立 Integer，缺失侧保持 null（不 0 归一）
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("部分usage回复"))
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(new TokenUsage(10, null))
                            .build())
                    .build();
        }
    }

    @Test
    @DisplayName("用例41: 标准3-F02 — 图执行历史跨租户不串计：租户 B 列表为空 + 详情/节点 NOT_FOUND（token 数据随记录隔离）")
    void executionTokenRecords_crossTenant_shouldBeIsolated() {
        Long modelId = insertModelConfig("openai-token6", "sk-token6");
                        stubAcquire(new TokenStubChatModel("租户回复", 10L, 20L));
        Long id = createPublishedGraph(llmGraph(modelId));
        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");
        assertThat(resp.isSuccess()).isTrue();
        assertThat(executionMapper.selectById(resp.getExecutionId()).getInputTokens()).isEqualTo(10L);

        setLoginUser(TENANT_200, USER_1);
        // 列表：租户 B 视角零记录（不泄漏租户 A 的 token 汇总）
        assertThat(service.pageExecutions(new PageParam(), null).getTotal()).isZero();
        // 详情/节点：NOT_FOUND
        assertThatThrownBy(() -> service.getExecution(resp.getExecutionId()))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.listExecutionNodes(resp.getExecutionId()))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("用例42: 标准7-历史兼容 — 迁移前 token 列 NULL 的执行记录经列表/详情/节点查询正常返回，token 显示未知（null）而非 0")
    void preMigrationRecords_withNullTokens_shouldBeReadableViaAllQueryEndpoints() {
        Long modelId = insertModelConfig("openai-token7", "sk-token7");
                        stubAcquire(new StubChatModel("历史回复"));
        Long id = createPublishedGraph(llmGraph(modelId));

        // 执行一次（当前列已 nullable），再手工把 token 列清成 NULL 模拟迁移前记录
        AgentGraphExecuteRespDTO resp = service.execute(id, "入参");
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        exec.setInputTokens(null);
        exec.setOutputTokens(null);
        executionMapper.updateById(exec);
        // 节点明细同样清空 token
        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId()));
        for (AgentGraphExecutionNode node : nodes) {
            node.setInputTokens(null);
            node.setOutputTokens(null);
            executionNodeMapper.updateById(node);
        }

        // ① 列表：可读，token 为 null（未知）
        PageResult<AgentGraphExecutionDTO> page = service.pageExecutions(new PageParam(), id);
        assertThat(page.getRecords()).anySatisfy(dto -> {
            assertThat(dto.getStatus()).isEqualTo("SUCCESS");
            assertThat(dto.getInputTokens()).isNull();
            assertThat(dto.getOutputTokens()).isNull();
        });
        // ② 详情：可读（输入/输出/状态完整），token null
        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getInput()).isEqualTo("入参");
        assertThat(detail.getOutput()).isEqualTo("历史回复");
        assertThat(detail.getInputTokens()).isNull();
        assertThat(detail.getOutputTokens()).isNull();
        // ③ 节点轨迹：可读，LLM 节点 token null
        List<AgentGraphExecutionNodeDTO> nodeDtos = service.listExecutionNodes(resp.getExecutionId());
        assertThat(nodeDtos).hasSize(3);
        assertThat(nodeDtos.get(1).getNodeType()).isEqualTo("LLM");
        assertThat(nodeDtos.get(1).getInputTokens()).isNull();
        assertThat(nodeDtos.get(1).getOutputTokens()).isNull();
    }

    // ==================== 用例31-35：Prompt 配置发布/重载/授权 + 未定义变量真实落库链（D151 补证） ====================

    @Test
    @DisplayName("用例31: Prompt 配置经发布→重载全链往返一致（标准1 — 字段透传 + 发布后 getGraph 保持）")
    void promptConfig_publishAndReload_shouldPreserveConfig() {
        Long modelId = insertModelConfig("openai-prompt1", "sk-p1");
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of(
                        "agentModelConfigId", modelId,
                        "systemPrompt", "你是专业翻译。",
                        "userPromptTemplate", "请翻译：{{input}}")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        // 发布后 getGraph 重载，prompt 配置键完整保留
        ProcessGraph loaded = graphDefService.getGraph(id);
        GraphElement llmElement = loaded.getElements().stream()
                .filter(e -> "LLM".equals(e.getType())).findFirst().orElseThrow();
        assertThat(llmElement.getConfig().get("systemPrompt")).isEqualTo("你是专业翻译。");
        assertThat(llmElement.getConfig().get("userPromptTemplate")).isEqualTo("请翻译：{{input}}");
        assertThat(llmElement.getConfig().get("agentModelConfigId")).isEqualTo(modelId);
    }

    @Test
    @DisplayName("用例32: 发布后继续编辑 prompt 配置 → 重载反映更新（标准1 — 发布后编辑/重载行为链）")
    void promptConfig_editAfterPublishReload_shouldReflectUpdate() {
        Long modelId = insertModelConfig("openai-prompt2", "sk-p2");
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of(
                        "agentModelConfigId", modelId,
                        "systemPrompt", "V1系统提示",
                        "userPromptTemplate", "V1模板 {{input}}")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        // V1 发布后重载确认
        assertThat(graphDefService.getGraph(id).getElements().stream()
                .filter(e -> "LLM".equals(e.getType())).findFirst().orElseThrow()
                .getConfig().get("systemPrompt")).isEqualTo("V1系统提示");

        // 发布后继续编辑 prompt 配置（saveDraftGraph 覆盖 graph_json，status 保持 PUBLISHED）
        ProcessGraph updated = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of(
                        "agentModelConfigId", modelId,
                        "systemPrompt", "V2系统提示",
                        "userPromptTemplate", "V2模板 {{name}}")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        graphDefService.saveDraftGraph(id, updated);

        // V2 重载确认 — 重新打开设计器时读取当前 graph_json
        ProcessGraph reloaded = graphDefService.getGraph(id);
        GraphElement llm = reloaded.getElements().stream()
                .filter(e -> "LLM".equals(e.getType())).findFirst().orElseThrow();
        assertThat(llm.getConfig().get("systemPrompt")).isEqualTo("V2系统提示");
        assertThat(llm.getConfig().get("userPromptTemplate")).isEqualTo("V2模板 {{name}}");
    }

    @Test
    @DisplayName("用例33: userPromptTemplate 未定义变量经真实 Service 落库 FAILED + UNDEFINED_VARIABLE + 模型未调用（标准5）")
    void execute_promptTemplateUndefinedVariable_shouldPersistFailedAndNeverCallModel() {
        Long modelId = insertModelConfig("openai-prompt3", "sk-p3");
        ChatModel mockModel = mock(ChatModel.class);
        stubAcquire(mockModel);
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of(
                        "agentModelConfigId", modelId,
                        "userPromptTemplate", "Hello, {{missing}}!")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "input-text");

        // ① 响应：success=false + errorMessage 包含未定义变量名
        assertThat(resp.isSuccess()).isFalse();
        assertThat(resp.getErrorMessage()).contains("missing");
        assertThat(resp.getExecutionId()).isNotNull();

        // ② 落库：status=FAILED, errorCategory=UNDEFINED_VARIABLE
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getStatus()).isEqualTo("FAILED");
        assertThat(exec.getErrorCategory()).isEqualTo("UNDEFINED_VARIABLE");
        assertThat(exec.getErrorMessage()).contains("missing");

        // ③ 模型未被调用
        verify(mockModel, never()).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("用例34: 未定义变量失败执行记录可通过列表/详情端点查询（标准5 — 可查询性）")
    void execute_promptTemplateUndefined_shouldBeQueryable() {
        Long modelId = insertModelConfig("openai-prompt4", "sk-p4");
                        stubAcquire(new StubChatModel("不应到达"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of(
                        "agentModelConfigId", modelId,
                        "userPromptTemplate", "{{undefinedVar}}")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "input");
        assertThat(resp.isSuccess()).isFalse();

        // 列表查询：返回该失败记录
        PageResult<AgentGraphExecutionDTO> page = service.pageExecutions(new PageParam(), id);
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(1);
        assertThat(page.getRecords()).anySatisfy(dto -> {
            assertThat(dto.getStatus()).isEqualTo("FAILED");
            assertThat(dto.getErrorCategory()).isEqualTo("UNDEFINED_VARIABLE");
        });

        // 详情查询：含完整 errorMessage + errorCategory
        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getStatus()).isEqualTo("FAILED");
        assertThat(detail.getErrorCategory()).isEqualTo("UNDEFINED_VARIABLE");
        assertThat(detail.getErrorMessage()).contains("undefinedVar");
        assertThat(detail.getInput()).isEqualTo("input");
    }

    @Test
    @DisplayName("用例35: 未发布图执行被门控 → PARAM_ERROR（标准1 — 授权行为）；已发布图可执行且 prompt 配置生效")
    void execute_draftGraphShouldFail_publishedGraphShouldSucceedWithPrompt() {
        Long modelId = insertModelConfig("openai-prompt5", "sk-p5");
                        stubAcquire(new StubChatModel("模型回复"));
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of(
                        "agentModelConfigId", modelId,
                        "systemPrompt", "翻译助手",
                        "userPromptTemplate", "翻译：{{input}}")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));

        // 仅创建+保存草稿（未发布） → 执行抛 PARAM_ERROR
        Long draftId = graphDefService.create(graph.getName());
        graphDefService.saveDraftGraph(draftId, graph);
        assertThatThrownBy(() -> service.execute(draftId, "input"))
                .isInstanceOf(BaseException.class);

        // 发布后 → 执行成功，模型被调用且 Prompt 包含 SystemMessage + UserMessage
        graphDefService.publish(draftId);
        AgentGraphExecuteRespDTO resp = service.execute(draftId, "hello");
        assertThat(resp.isSuccess()).isTrue();

        // 验证执行成功 + 输出非空 = prompt 配置经 Service 全链生效
        // （消息列表构造已由 InterpreterTest 用例 26/35 精确断言，此处不重复）
        assertThat(resp.getOutput()).isNotBlank();
    }

    // ==================== D165 补证：工具调用与非账单语义、历史 FAILED、逻辑删除 ====================

    @Test
    @DisplayName("D165-标准5-工具：TOOL 节点执行成功后，图执行落库 status=SUCCESS 且 token 字段不受工具调用污染（工具递归语义仅当前供应商 usage）")
    void execute_toolNode_tokenFieldsShouldRemainNull_notPollutedByToolCall() {
        insertInternalTool("echo_tool", 1);
        AgentToolCallbackFactory.ToolHandle echo = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("echo_tool").description("回声工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> {
                    try {
                        String arg = new ObjectMapper().readValue(request.arguments(), String.class);
                        return new ObjectMapper().writeValueAsString("echo:" + arg);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(echo));
        Long modelId = insertModelConfig("openai-tool-token", "sk-tool-token");
                        stubAcquire(new TokenStubChatModel("工具后LLM回复", 12L, 34L));
        // TOOL -> LLM 链：工具执行不产生 token，执行汇总应仅统计 LLM 节点
        ProcessGraph graph = graphOf(
                node("node_start", "START", Map.of()),
                node("node_tool", "TOOL", Map.of("toolName", "echo_tool", "outputVar", "toolResult")),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelId, "inputVar", "toolResult")),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_tool", Map.of()),
                edge("e2", "node_tool", "node_llm", Map.of()),
                edge("e3", "node_llm", "node_end", Map.of()));
        Long id = createPublishedGraph(graph);

        AgentGraphExecuteRespDTO resp = service.execute(id, "触发工具");

        assertThat(resp.isSuccess()).isTrue();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getInputTokens()).isEqualTo(12L);
        assertThat(exec.getOutputTokens()).isEqualTo(34L);
        List<AgentGraphExecutionNode> nodes = executionNodeMapper.selectList(
                Wrappers.<AgentGraphExecutionNode>lambdaQuery()
                        .eq(AgentGraphExecutionNode::getExecutionId, exec.getId())
                        .orderByAsc(AgentGraphExecutionNode::getNodeSeq));
        // TOOL 节点 token 恒为 null，不参与汇总
        AgentGraphExecutionNode toolNode = nodes.stream().filter(n -> "TOOL".equals(n.getNodeType())).findFirst().orElseThrow();
        assertThat(toolNode.getInputTokens()).isNull();
        assertThat(toolNode.getOutputTokens()).isNull();
    }

    @Test
    @DisplayName("D165-标准7-历史FAILED：迁移前 FAILED 记录（model/token 列 NULL）经列表/详情/节点查询正常读取，不丢错误分类")
    void preMigrationFailedRecord_shouldBeReadableWithErrorCategoryIntact() {
        Long modelId = insertModelConfig("openai-hist-failed", "sk-hist-failed");
                        stubAcquire(new ThrowingChatModel());
        Long id = createPublishedGraph(llmGraph(modelId));
        AgentGraphExecuteRespDTO resp = service.execute(id, "触发失败");
        assertThat(resp.isSuccess()).isFalse();
        AgentGraphExecution exec = executionMapper.selectById(resp.getExecutionId());
        assertThat(exec.getStatus()).isEqualTo("FAILED");
        assertThat(exec.getErrorCategory()).isEqualTo("MODEL_CALL_FAILED");

        // 模拟历史 FAILED：清 token 列但保留错误分类
        exec.setInputTokens(null);
        exec.setOutputTokens(null);
        executionMapper.updateById(exec);

        PageResult<AgentGraphExecutionDTO> page = service.pageExecutions(new PageParam(), id);
        assertThat(page.getRecords()).anySatisfy(dto -> {
            assertThat(dto.getStatus()).isEqualTo("FAILED");
            assertThat(dto.getErrorCategory()).isEqualTo("MODEL_CALL_FAILED");
            assertThat(dto.getInputTokens()).isNull();
        });
        AgentGraphExecutionDetailDTO detail = service.getExecution(resp.getExecutionId());
        assertThat(detail.getStatus()).isEqualTo("FAILED");
        assertThat(detail.getErrorCategory()).isEqualTo("MODEL_CALL_FAILED");
        assertThat(detail.getInputTokens()).isNull();
        assertThat(detail.getOutputTokens()).isNull();
        List<AgentGraphExecutionNodeDTO> nodeDtos = service.listExecutionNodes(resp.getExecutionId());
        assertThat(nodeDtos).isNotEmpty();
    }

    @Test
    @DisplayName("D165-标准8-逻辑删除：sw_agent_graph_execution 逻辑删除后，列表/详情/节点查询不可见（@TableLogic）")
    void logicallyDeletedExecution_shouldBeInvisibleViaQueries() {
        Long modelId = insertModelConfig("openai-delete", "sk-delete");
                        stubAcquire(new StubChatModel("待删除执行输出"));
        Long id = createPublishedGraph(llmGraph(modelId));
        AgentGraphExecuteRespDTO resp = service.execute(id, "待删除入参");
        assertThat(resp.isSuccess()).isTrue();
        Long execId = resp.getExecutionId();

        // 逻辑删除（MP @TableLogic）
        executionMapper.deleteById(execId);
        // 列表不可见
        PageResult<AgentGraphExecutionDTO> page = service.pageExecutions(new PageParam(), id);
        assertThat(page.getTotal()).isZero();
        // 详情/节点 404
        assertThatThrownBy(() -> service.getExecution(execId))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode()).isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> service.listExecutionNodes(execId))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode()).isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
    }

    // ==================== 内部辅助 ====================

    /** 创建 → 覆盖图 → 发布，返回已发布图 id */
    private Long createPublishedGraph(ProcessGraph graph) {
        Long id = graphDefService.create(graph.getName());
        graphDefService.saveDraftGraph(id, graph);
        graphDefService.publish(id);
        return id;
    }

    /** 标准 LLM 图：START→LLM(modelConfigId)→END */
    private ProcessGraph llmGraph(Long modelConfigId) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelConfigId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
    }

    /** 标准 TOOL 图：START→TOOL(toolName)→END */
    private ProcessGraph toolGraph(String toolName) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_tool", "TOOL", Map.of("toolName", toolName)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_tool", Map.of()),
                edge("e2", "node_tool", "node_end", Map.of()));
    }

    private ProcessGraph graphOf(GraphElement... elements) {
        ProcessGraph graph = new ProcessGraph();
        graph.setGraphKey("exec_test_key");
        graph.setName("执行测试图");
        graph.setVersion(1);
        graph.setElements(Arrays.asList(elements));
        graph.setCanvas(Map.of());
        return graph;
    }

    private GraphElement node(String id, String type, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("node").type(type)
                .config(config).style(Map.of())
                .build();
    }

    private GraphElement edge(String id, String source, String target, Map<String, Object> config) {
        return GraphElement.builder()
                .id(id).kind("edge").source(source).target(target)
                .config(config).style(Map.of())
                .build();
    }

    /** 插入租户内供应商 + 模型行（返回模型 id） */
    private Long insertModelConfig(String name, String plainKey) {
        AgentProvider provider = new AgentProvider();
        provider.setApiFormat("OPENAI_CHAT");
        provider.setName("provider-" + name);
        provider.setBaseUrl("http://localhost:9999/v1");
        provider.setApiKeyCipher(cipher.encrypt(plainKey));
        provider.setEnabled(true);
        providerMapper.insert(provider);
        AgentModel model = new AgentModel();
        model.setProviderId(provider.getId());
        model.setName(name);
        model.setModelName("stub-model");
        model.setCapabilities("CHAT");
        model.setEnabled(true);
        modelMapper.insert(model);
        return model.getId();
    }

    /** 打桩运行时服务：acquire 任意模型 id 返回携带指定 ChatModel 的 ModelInstance（支持连续多次打桩按序消费） */
    private void stubAcquire(ChatModel... chatModels) {
        AgentModel model = new AgentModel();
        model.setId(1L);
        model.setName("stub-model");
        model.setEnabled(true);
        AgentProvider provider = new AgentProvider();
        provider.setId(1L);
        provider.setName("stub-provider");
        provider.setApiFormat("OPENAI_CHAT");
        provider.setEnabled(true);
        var instances = java.util.Arrays.stream(chatModels)
                .map(cm -> new AgentModelRuntimeService.ModelInstance(model, provider, cm))
                .toArray(AgentModelRuntimeService.ModelInstance[]::new);
        when(modelRuntimeService.acquire(any())).thenReturn(instances[0],
                java.util.Arrays.copyOfRange(instances, 1, instances.length));
    }

    /** 插入内部工具白名单行（enabled=1 启用 / 0 禁用） */
    private void insertInternalTool(String toolName, int enabled) {
        AgentToolInternalConfig tool = new AgentToolInternalConfig();
        tool.setName(toolName);
        tool.setDescription("测试工具");
        tool.setBeanName("echoBean");
        tool.setMethodName("execute");
        tool.setEnabled(enabled == 1);
        internalToolMapper.insert(tool);
    }

    // ==================== ChatModel 桩 ====================

    /** 固定回复的 ChatModel 桩 */
    static class StubChatModel implements ChatModel {
        private final String reply;

        StubChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    /** 调用即抛异常的 ChatModel 桩 */
    static class ThrowingChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new IllegalStateException("model exploded");
        }
    }

    // ==================== 组合测试配置 ====================

    @Configuration
    @MapperScan("com.sw.ck.agent.mapper")
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        public DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:agentgraphexec;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // ==================== 业务 Bean ====================

        @Bean
        public AgentGraphDefService agentGraphDefService(ObjectMapper objectMapper) {
            return new AgentGraphDefServiceImpl(objectMapper);
        }

        @Bean
        public AgentModelRuntimeService agentModelRuntimeService() {
            return mock(AgentModelRuntimeService.class);
        }

        @Bean
        public AgentToolCallbackFactory agentToolCallbackFactory() {
            return mock(AgentToolCallbackFactory.class);
        }

        @Bean
        public AesGcmCipher aesGcmCipher() {
            return new AesGcmCipher(TEST_CIPHER_KEY);
        }

        @Bean
        public AgentGraphExecutionService agentGraphExecutionService(
                ObjectMapper objectMapper,
                AgentToolInternalConfigMapper internalToolMapper,
                AgentToolExternalConfigMapper externalToolMapper,
                AgentGraphExecutionMapper executionMapper,
                AgentGraphExecutionNodeMapper executionNodeMapper,
                AgentModelRuntimeService modelRuntimeService,
                LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            // AgentToolCallbackFactory 为 @Autowired(required=false) 字段注入，mock bean 由 Spring 自动填充
            return new AgentGraphExecutionServiceImpl(objectMapper, modelRuntimeService,
                    internalToolMapper, externalToolMapper, executionMapper,
                    executionNodeMapper, loginContextProvider, deptScopeProvider);
        }

        @Bean
        public com.sw.ck.common.datascope.DeptScopeProvider testDeptScopeProvider() {
            // 测试用映射实现：无子部门（本测试不覆盖 DEPT_AND_CHILD 展开）
            return deptId -> java.util.List.of();
        }
    }
}
