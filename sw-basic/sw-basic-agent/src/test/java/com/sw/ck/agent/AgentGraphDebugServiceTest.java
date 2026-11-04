package com.sw.ck.agent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.agent.dto.AgentGraphDebugNodeDTO;
import com.sw.ck.agent.dto.AgentGraphDebugSessionDTO;
import com.sw.ck.agent.dto.graph.GraphElement;
import com.sw.ck.agent.dto.graph.ProcessGraph;
import com.sw.ck.agent.entity.AgentGraphDebugSession;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.AgentGraphDebugNodeMapper;
import com.sw.ck.agent.mapper.AgentGraphDebugSessionMapper;
import com.sw.ck.agent.mapper.AgentGraphDefMapper;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.orchestration.AgentToolCallbackFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.sw.ck.agent.service.AgentGraphDebugService;
import com.sw.ck.agent.service.AgentGraphDefService;
import com.sw.ck.agent.service.impl.AgentGraphDebugServiceImpl;
import com.sw.ck.agent.service.impl.AgentGraphDefServiceImpl;
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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentGraphDebugServiceImpl} 测试（M07-F02-04 图单步调试，@SpringBootTest + H2）。
 * <p>
 * 策略与 {@code AgentGraphExecutionServiceImplTest} 同款组合装配（TestConfig 手动装配
 * MyBatis-Plus + 租户拦截器 + ObjectMapper），AgentModelRuntimeService/AgentToolCallbackFactory 为 mock（LC4j ChatModel 桩经 ModelInstance 注入），
 * AesGcmCipher 为真实实例。建表 DDL 与 V36 H2 脚本对齐 + 既有 V25/V19/V20 依赖表。
 * </p>
 */
@SpringBootTest(
        classes = AgentGraphDebugServiceTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=com.sw.ck.common.config.mybatis.MybatisPlusConfig,"
                        + "com.sw.ck.common.config.redis.RedisConfig,"
                        + "com.sw.ck.security.config.SecurityAutoConfiguration,"
                        + "com.sw.ck.security.config.WebSecurityAutoConfiguration"
        }
)
@Transactional
@DisplayName("Agent 图调试 Service 测试")
class AgentGraphDebugServiceTest {

    private static final String TEST_CIPHER_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private static final Long TENANT_100 = 100L;
    private static final Long TENANT_200 = 200L;
    private static final Long USER_1 = 1L;

    @Autowired
    private AgentGraphDebugService debugService;

    @Autowired
    private AgentGraphDefService graphDefService;

    @Autowired
    private com.sw.ck.agent.mapper.AgentProviderMapper providerMapper;

    @Autowired
    private com.sw.ck.agent.mapper.AgentModelMapper modelMapper;

    @Autowired
    private AgentToolInternalConfigMapper internalToolMapper;

    @Autowired
    private AgentGraphDebugSessionMapper debugSessionMapper;

    @Autowired
    private AgentGraphDebugNodeMapper debugNodeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentModelRuntimeService modelRuntimeService;

    @Autowired
    private AgentToolCallbackFactory toolCallbackFactory;

    private final AesGcmCipher cipher = new AesGcmCipher(TEST_CIPHER_KEY);

    // ==================== 建表 ====================

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
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_debug_session (
                    id                BIGINT      NOT NULL PRIMARY KEY,
                    graph_def_id      BIGINT      NOT NULL,
                    graph_def_version INT         NOT NULL,
                    graph_json        CLOB,
                    status            VARCHAR(20) NOT NULL,
                    input             CLOB,
                    breakpoints       CLOB,
                    state_json        CLOB,
                    result_text       CLOB,
                    error_category    VARCHAR(50),
                    error_message     CLOB,
                    latency_ms        BIGINT,
                    expires_at        TIMESTAMP,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    create_time       TIMESTAMP   NOT NULL,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT    NOT NULL DEFAULT 0,
                    tenant_id         BIGINT      NOT NULL DEFAULT 0,
                    version           BIGINT      NOT NULL DEFAULT 0
                )
                """);
        jt.execute("""
                CREATE TABLE IF NOT EXISTS sw_agent_graph_debug_node (
                    id                BIGINT       NOT NULL PRIMARY KEY,
                    debug_session_id  BIGINT       NOT NULL,
                    node_seq          INT          NOT NULL,
                    branch_id         VARCHAR(64)  NOT NULL,
                    node_id           VARCHAR(100) NOT NULL,
                    node_type         VARCHAR(20)  NOT NULL,
                    node_latency_ms   BIGINT,
                    variable_snapshot CLOB,
                    input_tokens      BIGINT,
                    output_tokens     BIGINT,
                    create_time       TIMESTAMP    NOT NULL,
                    create_by         VARCHAR(64),
                    update_time       TIMESTAMP,
                    update_by         VARCHAR(64),
                    deleted           SMALLINT     NOT NULL DEFAULT 0,
                    tenant_id         BIGINT       NOT NULL DEFAULT 0,
                    version           BIGINT       NOT NULL DEFAULT 0
                )
                """);
        jt.execute("CREATE INDEX IF NOT EXISTS idx_gexec_debug_graph ON sw_agent_graph_debug_session (graph_def_id, deleted)");
        jt.execute("CREATE INDEX IF NOT EXISTS idx_genode_debug ON sw_agent_graph_debug_node (debug_session_id, node_seq, deleted)");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM sw_agent_graph_debug_node");
        jdbcTemplate.update("DELETE FROM sw_agent_graph_debug_session");
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

    // ==================== 用例 1：createSession 成功（PUBLISHED） ====================

    @Test
    @DisplayName("用例1: createSession 已发布图 + 合法 input → PAUSED + expiresAt + nextNodeId=START")
    void createSession_publishedGraph_shouldSucceed() {
        Long modelId = insertModelConfig("openai-1", "sk-1");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO dto = debugService.createSession(graphId, "hello");
        assertThat(dto.getStatus()).isEqualTo("PAUSED");
        assertThat(dto.getGraphDefId()).isEqualTo(graphId);
        assertThat(dto.getExpiresAt()).isNotNull();
        assertThat(dto.getExpiresAt()).isAfter(LocalDateTime.now().minusMinutes(1));
        assertThat(dto.getNextNodeId()).isEqualTo("node_start");
        assertThat(dto.getVariables()).containsEntry("input", "hello");
    }

    // ==================== 用例 2：DRAFT 图 → PARAM_ERROR ====================

    @Test
    @DisplayName("用例2: createSession DRAFT 图 → PARAM_ERROR 图未发布")
    void createSession_draftGraph_shouldThrowParamError() {
        Long id = graphDefService.create("未发布图");
        graphDefService.saveDraftGraph(id, llmGraph(1L));
        assertThatThrownBy(() -> debugService.createSession(id, "hello"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("图未发布")
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.PARAM_ERROR.getCode()));
    }

    // ==================== 用例 3：blank input → PARAM_ERROR ====================

    @Test
    @DisplayName("用例3: createSession blank input → PARAM_ERROR")
    void createSession_blankInput_shouldThrowParamError() {
        Long modelId = insertModelConfig("openai-2", "sk-2");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        assertThatThrownBy(() -> debugService.createSession(graphId, ""))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("input 不能为空");
        assertThatThrownBy(() -> debugService.createSession(graphId, null))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> debugService.createSession(graphId, "   "))
                .isInstanceOf(BaseException.class);
    }

    // ==================== 用例 4：getSession 返回 PAUSED 明细 ====================

    @Test
    @DisplayName("用例4: getSession 返回 PAUSED + expiresAt + nextNodeId + variables")
    void getSession_shouldReturnPausedWithExpiresAndNextNode() {
        Long modelId = insertModelConfig("openai-3", "sk-3");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO created = debugService.createSession(graphId, "input-text");
        AgentGraphDebugSessionDTO fetched = debugService.getSession(created.getId());
        assertThat(fetched.getStatus()).isEqualTo("PAUSED");
        assertThat(fetched.getExpiresAt()).isNotNull();
        assertThat(fetched.getNextNodeId()).isEqualTo("node_start");
        assertThat(fetched.getVariables()).containsEntry("input", "input-text");
        assertThat(fetched.getVersion()).isNotNull();
    }

    // ==================== 用例 5：step 推进并落 trace ====================

    @Test
    @DisplayName("用例5: step 单步推进 — START → LLM → 产生 trace，variables 更新")
    void step_shouldProgressAndCreateTrace() {
        Long modelId = insertModelConfig("openai-4", "sk-4");
        stubAcquire(new StubChatModel("llm-out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        // step START
        AgentGraphDebugSessionDTO afterStart = debugService.step(session.getId());
        assertThat(afterStart.getStatus()).isEqualTo("PAUSED");
        assertThat(afterStart.getNextNodeId()).isEqualTo("node_llm");
        assertThat(afterStart.getTraceCount()).isEqualTo(1);
        // step LLM
        AgentGraphDebugSessionDTO afterLlm = debugService.step(afterStart.getId(), afterStart.getVersion());
        assertThat(afterLlm.getStatus()).isEqualTo("PAUSED");
        assertThat(afterLlm.getNextNodeId()).isEqualTo("node_end");
        assertThat(afterLlm.getVariables()).containsEntry("input", "llm-out");
        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).getNodeId()).isEqualTo("node_start");
        assertThat(nodes.get(1).getNodeId()).isEqualTo("node_llm");
    }

    // ==================== 用例 6：step 终态后失败 ====================

    @Test
    @DisplayName("用例6: step 已到达 COMPLETED 后再次 step → PARAM_ERROR 会话已终结")
    void step_whenTerminal_shouldThrow() {
        Long modelId = insertModelConfig("openai-5", "sk-5");
        stubAcquire(new StubChatModel("out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        debugService.step(session.getId()); // START
        AgentGraphDebugSessionDTO afterLlm = debugService.step(debugService.getSession(session.getId()).getId(),
                debugService.getSession(session.getId()).getVersion()); // LLM -> END next
        // 重新获取最新版本
        AgentGraphDebugSessionDTO latest = debugService.getSession(session.getId());
        debugService.step(latest.getId(), latest.getVersion()); // END -> COMPLETED
        AgentGraphDebugSessionDTO completed = debugService.getSession(session.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> debugService.step(completed.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("会话已终结");
    }

    // ==================== 用例 7：continueUntilBreakpoint 命中前暂停 ====================

    @Test
    @DisplayName("用例7: continueUntilBreakpoint 断点命中前暂停 — breakpoint 在 node_llm 停止于该节点前")
    void continueUntilBreakpoint_shouldStopBeforeBreakpointNode() {
        Long modelId = insertModelConfig("openai-6", "sk-6");
        stubAcquire(new StubChatModel("out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        debugService.updateBreakpoints(session.getId(), Set.of("node_llm"));
        AgentGraphDebugSessionDTO after = debugService.continueUntilBreakpoint(session.getId());
        assertThat(after.getStatus()).isEqualTo("PAUSED");
        assertThat(after.getNextNodeId()).isEqualTo("node_llm");
        // 仅执行了 START，LLM 未执行
        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).getNodeId()).isEqualTo("node_start");
    }

    // ==================== 用例 8：continue 无断点跑到 COMPLETED ====================

    @Test
    @DisplayName("用例8: continue 无断点 → 一直跑到 COMPLETED + resultText")
    void continueWithoutBreakpoints_shouldRunToCompleted() {
        Long modelId = insertModelConfig("openai-7", "sk-7");
        stubAcquire(new StubChatModel("final"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        AgentGraphDebugSessionDTO result = debugService.continueUntilBreakpoint(session.getId());
        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResultText()).isEqualTo("final");
        assertThat(result.getNextNodeId()).isNull();
    }

    // ==================== 用例 9：updateBreakpoints 校验节点存在 ====================

    @Test
    @DisplayName("用例9: updateBreakpoints 不存在的 nodeId → PARAM_ERROR 断点节点不存在")
    void updateBreakpoints_invalidNode_shouldThrow() {
        Long modelId = insertModelConfig("openai-8", "sk-8");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        assertThatThrownBy(() -> debugService.updateBreakpoints(session.getId(), Set.of("not_exist")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("断点节点不存在");
        // 合法断点更新成功
        AgentGraphDebugSessionDTO updated = debugService.updateBreakpoints(session.getId(), Set.of("node_llm"));
        assertThat(updated.getBreakpoints()).contains("node_llm");
        // 清空断点
        AgentGraphDebugSessionDTO cleared = debugService.updateBreakpoints(session.getId(), Set.of());
        assertThat(cleared.getBreakpoints()).isEmpty();
    }

    // ==================== 用例 10：stop 仅 PAUSED 可停止 ====================

    @Test
    @DisplayName("用例10: stop 仅 PAUSED 可停止 — COMPLETED 后 stop → PARAM_ERROR")
    void stop_onlyPausedAllowed() {
        Long modelId = insertModelConfig("openai-9", "sk-9");
        stubAcquire(new StubChatModel("out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        AgentGraphDebugSessionDTO stopped = debugService.stop(session.getId());
        assertThat(stopped.getStatus()).isEqualTo("STOPPED");
        // 再次 stop 失败
        assertThatThrownBy(() -> debugService.stop(stopped.getId()))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("仅 PAUSED 会话可停止");
        // COMPLETED 会话 stop 失败
        Long graphId2 = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO s2 = debugService.createSession(graphId2, "hi");
        debugService.continueUntilBreakpoint(s2.getId());
        AgentGraphDebugSessionDTO completed = debugService.getSession(s2.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> debugService.stop(completed.getId()))
                .isInstanceOf(BaseException.class);
    }

    // ==================== 用例 11：过期会话返回 EXPIRED ====================

    @Test
    @DisplayName("用例11: 过期会话 — expiresAt 过去后 getSession 置为 EXPIRED")
    void expiredSession_shouldReturnExpired() {
        Long modelId = insertModelConfig("openai-10", "sk-10");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        // 手工将过期时间置为过去
        AgentGraphDebugSession entity = debugSessionMapper.selectById(session.getId());
        entity.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        debugSessionMapper.updateById(entity);
        AgentGraphDebugSessionDTO fetched = debugService.getSession(session.getId());
        assertThat(fetched.getStatus()).isEqualTo("EXPIRED");
        assertThat(fetched.isTerminal()).isTrue();
    }

    // ==================== 用例 12：version 冲突 409 ====================

    @Test
    @DisplayName("用例12: version 冲突 — stale version step → 409 并发冲突")
    void step_versionConflict_shouldThrow409() {
        Long modelId = insertModelConfig("openai-11", "sk-11");
        stubAcquire(new StubChatModel("out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        Long staleVersion = session.getVersion();
        // 先用正确版本推进一步，使 version 递增
        debugService.step(session.getId(), staleVersion);
        // 再用旧 version 重试
        assertThatThrownBy(() -> debugService.step(session.getId(), staleVersion))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode()).isEqualTo(409));
    }

    // ==================== 用例 13：租户隔离 ====================

    @Test
    @DisplayName("用例13: 租户隔离 — 租户 B 无法查看租户 A 的调试会话")
    void tenantIsolation_shouldNotSeeOtherTenantSession() {
        Long modelId = insertModelConfig("openai-12", "sk-12");
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        setLoginUser(TENANT_200, USER_1);
        assertThatThrownBy(() -> debugService.getSession(session.getId()))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> debugService.step(session.getId()))
                .isInstanceOf(BaseException.class);
        // listNodes 同样隔离
        assertThatThrownBy(() -> debugService.listNodes(session.getId()))
                .isInstanceOf(BaseException.class);
    }

    // ==================== 用例 14：断点命中语义（断点前暂停） ====================

    @Test
    @DisplayName("用例14: 断点命中语义 — 断点在 END 前暂停，step 后才进入 END 完成")
    void breakpointHitSemantics_shouldPauseBeforeNode() {
        Long modelId = insertModelConfig("openai-13", "sk-13");
        stubAcquire(new StubChatModel("out"));
        Long graphId = createPublishedGraph(llmGraph(modelId));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hi");
        debugService.updateBreakpoints(session.getId(), Set.of("node_end"));
        AgentGraphDebugSessionDTO paused = debugService.continueUntilBreakpoint(session.getId());
        assertThat(paused.getNextNodeId()).isEqualTo("node_end");
        assertThat(paused.getStatus()).isEqualTo("PAUSED");
        // 单步 END 完成
        AgentGraphDebugSessionDTO completed = debugService.step(paused.getId(), paused.getVersion());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
    }

    // ==================== 用例 15：tool 节点幂等 — version 保证不重放 ====================

    @Test
    @DisplayName("用例15: tool 节点不重放 — step 成功后 stale version 重试抛 409 且不新增 trace")
    void toolNode_shouldNotBeReExecutedOnRetry() {
        insertInternalTool("echo_tool", 1);
        AgentToolCallbackFactory.ToolHandle echo = new AgentToolCallbackFactory.ToolHandle(
                ToolSpecification.builder().name("echo_tool").description("回声工具").build(),
                (ToolExecutionRequest request, Object memoryId) -> {
                    try {
                        String arg = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(request.arguments(), String.class);
                        return new com.fasterxml.jackson.databind.ObjectMapper()
                                .writeValueAsString("echo:" + arg);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        when(toolCallbackFactory.buildToolCallbacks(any())).thenReturn(List.of(echo));
        Long graphId = createPublishedGraph(toolGraph("echo_tool"));
        AgentGraphDebugSessionDTO session = debugService.createSession(graphId, "hello");
        // START
        AgentGraphDebugSessionDTO afterStart = debugService.step(session.getId(), session.getVersion());
        assertThat(afterStart.getNextNodeId()).isEqualTo("node_tool");
        // TOOL step 成功
        AgentGraphDebugSessionDTO afterTool = debugService.step(afterStart.getId(), afterStart.getVersion());
        assertThat(afterTool.getVariables()).containsEntry("input", "echo:hello");
        int traceCountAfterTool = afterTool.getTraceCount();
        // stale version 重试应抛 409，不产生新 trace
        assertThatThrownBy(() -> debugService.step(session.getId(), afterStart.getVersion()))
                .isInstanceOf(BaseException.class)
                .satisfies(ex -> assertThat(((BaseException) ex).getCode()).isEqualTo(409));
        List<AgentGraphDebugNodeDTO> nodes = debugService.listNodes(session.getId());
        assertThat(nodes).hasSize(traceCountAfterTool);
    }

    // ==================== 内部辅助 ====================

    private Long createPublishedGraph(ProcessGraph graph) {
        Long id = graphDefService.create(graph.getName());
        graphDefService.saveDraftGraph(id, graph);
        graphDefService.publish(id);
        return id;
    }

    private ProcessGraph llmGraph(Long modelConfigId) {
        return graphOf(
                node("node_start", "START", Map.of()),
                node("node_llm", "LLM", Map.of("agentModelConfigId", modelConfigId)),
                node("node_end", "END", Map.of()),
                edge("e1", "node_start", "node_llm", Map.of()),
                edge("e2", "node_llm", "node_end", Map.of()));
    }

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
        graph.setGraphKey("debug_test_key");
        graph.setName("调试测试图");
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

    /** 打桩运行时服务：acquire 任意模型 id 返回携带指定 ChatModel 的 ModelInstance */
    private void stubAcquire(ChatModel chatModel) {
        AgentModel model = new AgentModel();
        model.setId(1L);
        model.setName("stub-model");
        model.setEnabled(true);
        AgentProvider provider = new AgentProvider();
        provider.setId(1L);
        provider.setName("stub-provider");
        provider.setApiFormat("OPENAI_CHAT");
        provider.setEnabled(true);
        when(modelRuntimeService.acquire(any()))
                .thenReturn(new AgentModelRuntimeService.ModelInstance(model, provider, chatModel));
        // D022-05 轮换入口：委托回 acquire 打桩（每次调用仍经 acquire 解析，计数语义不变）
        when(modelRuntimeService.chatWithRotation(any(), anyList())).thenAnswer(inv -> {
        java.util.List<?> msgs = (java.util.List<?>) inv.getArgument(1);
        if (msgs == null || msgs.isEmpty()) { return null; }
        return modelRuntimeService.acquire(1L).chatModel().chat(
                                dev.langchain4j.model.chat.request.ChatRequest.builder()
                                .messages((java.util.List<dev.langchain4j.data.message.ChatMessage>) inv.getArgument(1)).build());
        });
    }

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

    static class StubChatModel implements ChatModel {
        private final String reply;
        StubChatModel(String reply) { this.reply = reply; }
        @Override public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
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
                    .url("jdbc:h2:mem:agentgraphdebug;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
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
                @Override public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }
                @Override public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }
                @Override public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }
                @Override public DataScopeType getDataScopeType() { return DataScopeType.ALL; }
                @Override public Set<Long> getCustomDeptIds() { return Set.of(); }
                @Override public boolean isSuperAdmin() {
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
        public ObjectMapper objectMapper() { return new ObjectMapper(); }

        @Bean
        public AgentGraphDefService agentGraphDefService(ObjectMapper objectMapper) {
            return new AgentGraphDefServiceImpl(objectMapper);
        }

        @Bean
        public AgentModelRuntimeService agentModelRuntimeService() { return mock(AgentModelRuntimeService.class); }

        @Bean
        public AgentToolCallbackFactory agentToolCallbackFactory() { return mock(AgentToolCallbackFactory.class); }

        @Bean
        public AesGcmCipher aesGcmCipher() { return new AesGcmCipher(TEST_CIPHER_KEY); }

        @Bean
        public AgentGraphDebugService agentGraphDebugService(
                ObjectMapper objectMapper,
                AgentGraphDefMapper graphDefMapper,
                AgentGraphDebugSessionMapper sessionMapper,
                AgentGraphDebugNodeMapper debugNodeMapper,
                AgentToolInternalConfigMapper internalToolMapper,
                AgentToolExternalConfigMapper externalToolMapper,
                AgentModelRuntimeService modelRuntimeService,
                LoginContextProvider loginContextProvider,
                com.sw.ck.common.datascope.DeptScopeProvider deptScopeProvider) {
            // AgentToolCallbackFactory 为 @Autowired(required=false) 字段注入，mock bean 由 Spring 自动填充
            return new AgentGraphDebugServiceImpl(objectMapper, graphDefMapper,
                    sessionMapper, debugNodeMapper, internalToolMapper, externalToolMapper,
                    modelRuntimeService, loginContextProvider, deptScopeProvider);
        }

        @Bean
        public com.sw.ck.common.datascope.DeptScopeProvider testDeptScopeProvider() {
            return deptId -> List.of();
        }
    }
}
